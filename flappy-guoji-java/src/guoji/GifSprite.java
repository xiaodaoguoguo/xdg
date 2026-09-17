package guoji;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一张 GIF 拆成若干帧，并在游戏里按原始帧延时无限循环播放。
 *
 * <p>为什么不用 ImageIcon：ImageIcon 虽然能自动播放动画，但无法控制播放进度
 * （游戏暂停/死亡时想冻结动画就做不到），而且它缩放带透明的 GIF 会出现白边。
 * 所以这里自己解码，逐帧绘制。
 *
 * <p>三个必须自己处理的坑：
 * <ol>
 *   <li><b>帧合成</b>：GIF 后面的帧可能只存了变化区域，必须依赖解码器按
 *       disposal method 合成出完整画面，否则会得到一堆半透明的碎块。</li>
 *   <li><b>透明边白边</b>：GIF 只有 0/255 二值透明，被透明像素占位的 RGB 是白色。
 *       缩放做插值时这些白色会被平均进来，在深色背景上就是一圈白毛刺。
 *       解法是先把不透明像素的 RGB 向外扩散几层（edge bleed），填满透明区，再缩放。</li>
 *   <li><b>帧延时</b>：延时存在 GIF 的 GraphicControlExtension 里，需要读元数据。</li>
 * </ol>
 */
public final class GifSprite {

    /** 透明区域向外扩散 RGB 的层数，6 层足够覆盖双线性插值的取样半径。 */
    private static final int BLEED_PASSES = 6;
    /** GIF 未标注延时时使用的兜底值。 */
    private static final int DEFAULT_DELAY_MS = 100;

    /** 裁剪后的各帧，统一为 TYPE_INT_ARGB。 */
    private final BufferedImage[] frames;
    /** 每帧的显示时长（毫秒），与 frames 一一对应。 */
    private final int[] delays;
    /** 一轮循环的总时长。 */
    private final int loopMs;

    private final int srcW;
    private final int srcH;

    /** 按目标尺寸预缩放好的帧缓存。 */
    private BufferedImage[] scaledFrames;
    private int scaledW = -1;
    private int scaledH = -1;

    /** 自动检测出的文字/本体分界线，仅用于日志。 */
    private final int cutY;

    private GifSprite(BufferedImage[] frames, int[] delays, int cutY) {
        this.frames = frames;
        this.delays = delays;
        this.cutY = cutY;
        this.srcW = frames[0].getWidth();
        this.srcH = frames[0].getHeight();

        int total = 0;
        for (int d : delays) {
            total += d;
        }
        this.loopMs = Math.max(1, total);
    }

    // ==================================================================
    // 加载
    // ==================================================================

    /**
     * 读取 GIF，裁掉透明留白与（可选的）顶部文字行。
     *
     * @param gif         GIF 文件
     * @param keepCaption true 保留「我也要！我也要！」文字行
     */
    public static GifSprite load(File gif, boolean keepCaption) throws IOException {
        List<BufferedImage> raw = new ArrayList<>();
        int[] delays;

        ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
        try (ImageInputStream in = ImageIO.createImageInputStream(gif)) {
            if (in == null) {
                throw new IOException("无法打开 GIF 输入流：" + gif);
            }
            reader.setInput(in, false, false);
            int n = reader.getNumImages(true);
            if (n <= 0) {
                throw new IOException("GIF 里没有可读帧：" + gif);
            }

            delays = readDelays(reader, n);

            int w = reader.getWidth(0);
            int h = reader.getHeight(0);
            for (int i = 0; i < n; i++) {
                BufferedImage frame = reader.read(i);
                if (frame == null) {
                    continue;
                }
                // 统一转成 INT_ARGB：既保住透明通道，也保证后续可以逐像素改。
                BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argb.createGraphics();
                // 关掉插值，1:1 拷贝不引入任何颜色变化
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(frame, 0, 0, null);
                g.dispose();
                raw.add(argb);
            }
        } finally {
            reader.dispose();
        }

        if (raw.isEmpty()) {
            throw new IOException("GIF 解码后没有任何帧：" + gif);
        }

        // 所有帧共用同一套裁剪参数，否则每帧包围盒不同会让动画抖动。
        int w = raw.get(0).getWidth();
        int h = raw.get(0).getHeight();

        int cutY = h;
        if (!keepCaption) {
            cutY = detectCaptionCut(raw);
        }

        // 先扩散 RGB，再裁剪 —— 顺序很重要，扩散需要原始尺寸的边界信息。
        for (BufferedImage f : raw) {
            bleedEdges(f);
        }

        int[] box = unionBBox(raw, cutY);
        BufferedImage[] cropped = new BufferedImage[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            cropped[i] = raw.get(i).getSubimage(box[0], box[1], box[2], box[3]);
        }

        return new GifSprite(cropped, delays, cutY);
    }

    /** 从 GIF 帧元数据里读每帧延时，单位毫秒。 */
    private static int[] readDelays(ImageReader reader, int n) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            int ms = DEFAULT_DELAY_MS;
            try {
                IIOMetadata md = reader.getImageMetadata(i);
                String fmt = md.getNativeMetadataFormatName();
                if (fmt != null) {
                    IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(fmt);
                    IIOMetadataNode gce = firstChild(root, "GraphicControlExtension");
                    if (gce != null) {
                        String dt = gce.getAttribute("delayTime");
                        if (dt != null && !dt.isEmpty()) {
                            int cs = Integer.parseInt(dt.trim());
                            // GIF 单位是 1/100 秒；0 表示「尽快」，按 100ms 处理。
                            if (cs > 0) {
                                ms = cs * 10;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 元数据读不到就用默认值，不影响画面。
            }
            out[i] = Math.max(20, ms);
        }
        return out;
    }

    /** 在直接子节点里按名字找第一个匹配节点，找不到返回 null。 */
    private static IIOMetadataNode firstChild(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        return null;
    }

    /**
     * 找出「顶部文字」与「本体」之间的空白行。
     *
     * <p>做法：统计全部帧并集后的每行不透明像素数，在画面上半部分找第一段
     * 长度 &gt;= 3 的空行带，且它前面必须已经出现过墨迹。找不到就返回整幅高度，
     * 也就是「不裁」。这样换成别的素材也不会误裁。
     */
    private static int detectCaptionCut(List<BufferedImage> frames) {
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        int[] opaque = new int[h];

        // 只统计左上相对干净的区域容易漏，这里逐帧逐行扫全宽，代价很小（240x240x4）。
        for (BufferedImage f : frames) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((f.getRGB(x, y) >>> 24) > 16) {
                        opaque[y]++;
                    }
                }
            }
        }

        boolean seenInk = false;
        int runStart = -1;
        int limit = h / 2;
        for (int y = 0; y < limit; y++) {
            if (opaque[y] == 0) {
                if (runStart < 0) {
                    runStart = y;
                }
            } else {
                if (runStart >= 0 && seenInk && y - runStart >= 3) {
                    return y; // 空行带的下一行就是本体起点
                }
                runStart = -1;
                seenInk = true;
            }
        }
        return h;
    }

    /**
     * 把所有不透明像素的 RGB 向透明区域扩散若干层（alpha 保持 0 不变）。
     *
     * <p>目的是让透明区的 RGB 从「白」变成「附近的真实颜色」，
     * 这样之后缩放插值时边缘不会渗出白色。
     */
    private static void bleedEdges(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);

        for (int pass = 0; pass < BLEED_PASSES; pass++) {
            int[] next = px.clone();
            boolean changed = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    if ((px[i] >>> 24) != 0) {
                        continue; // 本身不透明，跳过
                    }
                    int r = 0, g = 0, b = 0, cnt = 0;
                    // 四邻域取样：扩散形状比八角形更贴合描边
                    if (x > 0) {
                        int c = px[i - 1];
                        if ((c >>> 24) != 0) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++; }
                    }
                    if (x < w - 1) {
                        int c = px[i + 1];
                        if ((c >>> 24) != 0) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++; }
                    }
                    if (y > 0) {
                        int c = px[i - w];
                        if ((c >>> 24) != 0) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++; }
                    }
                    if (y < h - 1) {
                        int c = px[i + w];
                        if ((c >>> 24) != 0) { r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; cnt++; }
                    }
                    if (cnt > 0) {
                        // alpha 保持 0：视觉上依然是透明的，只是 RGB 有意义了
                        next[i] = (r / cnt) << 16 | (g / cnt) << 8 | (b / cnt);
                        changed = true;
                    }
                }
            }
            px = next;
            if (!changed) {
                break;
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
    }

    /** 计算所有帧在 y &gt;= fromY 区域内的不透明像素并集包围盒，返回 {x, y, w, h}。 */
    private static int[] unionBBox(List<BufferedImage> frames, int fromY) {
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;

        for (BufferedImage f : frames) {
            for (int y = Math.max(0, fromY); y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if ((f.getRGB(x, y) >>> 24) > 16) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }

        if (maxX < 0) {
            return new int[]{0, Math.max(0, fromY), w, Math.max(1, h - fromY)};
        }
        return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    // ==================================================================
    // 播放 / 绘制
    // ==================================================================

    public int frameCount() {
        return frames.length;
    }

    public int loopDurationMs() {
        return loopMs;
    }

    public int sourceWidth() {
        return srcW;
    }

    public int sourceHeight() {
        return srcH;
    }

    public int captionCutY() {
        return cutY;
    }

    /**
     * 按时间取当前该显示第几帧。
     *
     * <p>用「时间 → 帧号」直接映射，而不是每帧 i++，好处是：
     * 掉帧不会让动画变慢，也永远不会因为边界判断写错而停住，
     * {@code % loopMs} 天然就是无限循环。
     */
    public int frameIndexAt(long elapsedMs) {
        if (frames.length == 1) {
            return 0;
        }
        long t = elapsedMs % loopMs;
        if (t < 0) {
            t += loopMs;
        }
        int acc = 0;
        for (int i = 0; i < delays.length; i++) {
            acc += delays[i];
            if (t < acc) {
                return i;
            }
        }
        return frames.length - 1;
    }

    /** 预缩放到指定尺寸；尺寸没变则复用缓存。 */
    private void ensureScaled(int w, int h) {
        if (scaledFrames != null && scaledW == w && scaledH == h) {
            return;
        }
        w = Math.max(1, w);
        h = Math.max(1, h);

        BufferedImage[] out = new BufferedImage[frames.length];
        for (int i = 0; i < frames.length; i++) {
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = dst.createGraphics();
            // 双线性 + 高质量渲染：alpha 通道也会被插值，得到柔和边缘
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(frames[i], 0, 0, w, h, null);
            g.dispose();
            out[i] = dst;
        }
        scaledFrames = out;
        scaledW = w;
        scaledH = h;
    }

    /**
     * 在 (cx, cy) 处绘制当前帧，中心对齐。
     *
     * @param elapsedMs 游戏自身累计的时间；死亡时不再增长即可冻结动画
     * @param rotation  旋转弧度，0 为水平
     */
    public void draw(Graphics2D g, double cx, double cy, int w, int h, long elapsedMs, double rotation) {
        ensureScaled(w, h);
        BufferedImage img = scaledFrames[frameIndexAt(elapsedMs)];

        AffineTransform old = g.getTransform();
        if (rotation != 0) {
            g.translate(cx, cy);
            g.rotate(rotation);
            g.drawImage(img, -w / 2, -h / 2, null);
        } else {
            g.drawImage(img, (int) Math.round(cx - w / 2.0), (int) Math.round(cy - h / 2.0), null);
        }
        g.setTransform(old);
    }
}
