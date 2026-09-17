package tools;

import guoji.Config;
import guoji.FlappyGame;
import guoji.GameRenderer;
import guoji.GifSprite;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线验证：不打开窗口，把逻辑跑一遍并导出画面。
 *
 * <p>为什么要有这个工具：Swing 窗口没法在无人值守时截图核对，
 * 而「果叽有没有正常循环播放」「碰撞判定是不是太严」「缩放后有没有白边」
 * 都是必须用眼睛确认的事。这里直接渲染到离屏缓冲并落盘。
 *
 * 用法：java -cp out tools.HeadlessRender
 */
public class HeadlessRender {

    private static final StringBuilder REPORT = new StringBuilder();
    private static int passCount = 0;
    private static int failCount = 0;

    /** 预览 GIF 的输出尺寸。360x640 原尺寸会让文件胀到 10MB 以上，缩到 240 宽刚好。 */
    private static final int GIF_W = 240;
    private static final int GIF_H = 427;
    /** 预览 GIF 只截取前 15 秒，再长文件就没法分享了。 */
    private static final int GIF_MAX_STEPS = 900;

    public static void main(String[] args) throws Exception {
        File cwd = new File(".");
        File outDir = new File("out/verify");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("建不了目录 " + outDir);
        }

        GifSprite sprite = GifSprite.load(new File("assets/我也要.gif"), Config.KEEP_CAPTION);
        GameRenderer renderer = new GameRenderer(sprite);

        section("环境");
        line("工作目录        : " + cwd.getAbsolutePath());
        line("Java            : " + System.getProperty("java.version"));
        line("中文字体        : " + renderer.fontFamily());
        line("逻辑分辨率      : " + Config.WIDTH + " x " + Config.HEIGHT);

        // ---------- 1. 动画循环 ----------
        section("1. GIF 循环播放");
        int n = sprite.frameCount();
        int loopMs = sprite.loopDurationMs();
        line("帧数            : " + n);
        line("单轮时长        : " + loopMs + " ms  -> 每秒约 "
                + String.format("%.2f", 1000.0 / loopMs) + " 轮");
        line("裁剪后尺寸      : " + sprite.sourceWidth() + " x " + sprite.sourceHeight());

        check("4 帧全部解出", n == 4);
        boolean[] seen = new boolean[n];
        for (int t = 0; t < loopMs; t++) {
            seen[sprite.frameIndexAt(t)] = true;
        }
        boolean allSeen = true;
        for (boolean b : seen) {
            allSeen &= b;
        }
        check("一轮内每帧都被播放到", allSeen);

        boolean periodic = true;
        for (int t = 0; t < loopMs * 3; t++) {
            if (sprite.frameIndexAt(t) != sprite.frameIndexAt(t + loopMs)) {
                periodic = false;
                break;
            }
        }
        check("严格按 " + loopMs + "ms 无限循环", periodic);

        int firstOfLoop = sprite.frameIndexAt(0);
        int lastOfLoop = sprite.frameIndexAt(loopMs - 1);
        check("循环首尾帧不同(没卡在同一帧)", firstOfLoop != lastOfLoop);

        // 10 分钟超长时间轴也要能正确回绕
        long huge = 10L * 60 * 1000;
        check("长时间轴(10分钟)仍正确回绕",
                sprite.frameIndexAt(huge) == sprite.frameIndexAt(huge % loopMs));

        // ---------- 2. 物理与判定 ----------
        section("2. 物理与碰撞");

        FlappyGame g = new FlappyGame(20260917L);
        check("初始为 READY 状态", g.state() == FlappyGame.State.READY);

        // READY 状态下果叽应该在浮动
        double minY = 1e9, maxY = -1e9;
        for (int i = 0; i < 120; i++) {
            g.step();
            minY = Math.min(minY, g.birdY());
            maxY = Math.max(maxY, g.birdY());
        }
        check("READY 状态果叽上下浮动 (幅度 "
                + String.format("%.1f", maxY - minY) + "px)", maxY - minY > 8);

        double beforeY = g.birdY();
        g.flap();
        check("点击后进入 PLAYING", g.state() == FlappyGame.State.PLAYING);
        check("点击后获得向上速度", g.velocity() < 0);
        g.step();
        check("上升: 下一帧果叽位置变高", g.birdY() < beforeY);

        // 自由落体：不操作应该一直掉到地面并进入 OVER
        int guard = 0;
        while (g.state() != FlappyGame.State.OVER && guard++ < 2000) {
            g.step();
        }
        check("不操作最终落地面进入 OVER", g.state() == FlappyGame.State.OVER);
        check("果叽停在地面上方", Math.abs(g.birdY() + Config.HIT_RY - FlappyGame.FLOOR_Y) < 1.0);
        check("死亡后动画时钟冻结", freezeHolds(g));

        // 撞顶不致死：先核对撞顶瞬间的钳位行为。
        // 注意测试窗口要短于第一组管道抵达的时间（约 245 步），
        // 否则测的就不是撞顶、而是贴着天花板撞上管口了。
        FlappyGame g2 = new FlappyGame(7L);
        g2.flap();
        for (int i = 0; i < 120; i++) {
            g2.flap();
            g2.step();
        }
        check("持续拍翅膀撞顶不死(只被挡)", g2.state() == FlappyGame.State.PLAYING);
        check("撞顶后被钳在画布内且速度归零",
                Math.abs(g2.birdY() - Config.HIT_RY) < 0.6 && g2.velocity() == 0);

        // ---------- 3. 自动驾驶跑一局 ----------
        section("3. 自动驾驶实测");
        FlappyGame auto = new FlappyGame(20260917L);
        auto.flap();

        List<BufferedImage> shots = new ArrayList<>();
        int maxScore = 0;
        int steps = 0;
        // 20 秒游戏时间（1200 步），每隔 3 步存一帧 -> 20fps 预览
        int totalSteps = 1200;
        List<BufferedImage> gifFrames = new ArrayList<>();

        while (steps < totalSteps) {
            if (auto.state() == FlappyGame.State.PLAYING) {
                autopilot(auto);
            } else if (auto.state() == FlappyGame.State.READY) {
                auto.flap();
            }
            auto.step();
            steps++;
            maxScore = Math.max(maxScore, auto.score());

            if (steps % 4 == 0 && steps <= GIF_MAX_STEPS) {
                gifFrames.add(scaleDown(render(renderer, auto, Config.WIDTH, Config.HEIGHT),
                        GIF_W, GIF_H));
            }
            if (steps == 300 || steps == 600 || steps == 900) {
                shots.add(render(renderer, auto, Config.WIDTH, Config.HEIGHT));
            }
        }
        line("自动驾驶 20 秒得分 : " + maxScore);
        line("结束时状态         : " + auto.state());
        check("自动驾驶能吃到管道分数 (>5)", maxScore > 5);

        // ---------- 4. 导出 ----------
        section("4. 导出产出的图");

        // 四个关键时刻的竖排对比
        BufferedImage strip = vstrip(shots, 2, 8);
        ImageIO.write(strip, "png", new File(outDir, "playthrough.png"));
        line("关键帧对比      : playthrough.png (" + strip.getWidth() + "x" + strip.getHeight() + ")");

        // READY 画面
        FlappyGame ready = new FlappyGame(1L);
        for (int i = 0; i < 40; i++) {
            ready.step();
        }
        ImageIO.write(render(renderer, ready, Config.WIDTH, Config.HEIGHT),
                "png", new File(outDir, "screen_ready.png"));

        // 结算画面：必须先跑到「撞上」，再跑到「落地」，最后等 overDelay 走完，
        // 三步都不能省 —— 否则拍到的是坠落中间帧，面板还没弹出来。
        FlappyGame over = new FlappyGame(20260917L);
        over.flap();
        int guard2 = 0;
        while (over.state() != FlappyGame.State.DYING && guard2++ < 8000) {
            if (over.state() == FlappyGame.State.PLAYING) {
                autopilot(over);
            }
            over.step();
        }
        int guard3 = 0;
        while (over.state() != FlappyGame.State.OVER && guard3++ < 600) {
            over.step();
        }
        for (int i = 0; i < 30; i++) {
            over.step();
        }
        check("死亡后能落到地面进入 OVER", over.state() == FlappyGame.State.OVER);
        ImageIO.write(render(renderer, over, Config.WIDTH, Config.HEIGHT),
                "png", new File(outDir, "screen_gameover.png"));
        line("结算画面        : screen_gameover.png  (得分 " + over.score() + ")");

        // 游玩 GIF
        File gifOut = new File(outDir, "gameplay.gif");
        writeGif(gifFrames, gifOut, 6); // 每帧 6/100 秒 ≈ 16.7fps
        line("游玩动画        : gameplay.gif (" + gifFrames.size() + " 帧 "
                + GIF_W + "x" + GIF_H + ", " + (gifOut.length() / 1024) + " KB)");

        // ---------- 汇总 ----------
        section("结论");
        line("通过 " + passCount + " 项，失败 " + failCount + " 项");
        if (failCount > 0) {
            line("");
            line("!!! 有检查项未通过，见上方 [FAIL] !!!");
        }

        try (PrintWriter pw = new PrintWriter(new File(outDir, "report.txt"), "UTF-8")) {
            pw.print(REPORT);
        }
        System.out.println(REPORT);

        // 验证过程会写最高分文件，别把这次的分数留给玩家
        new File(Config.BEST_SCORE_FILE).delete();

        if (failCount > 0) {
            System.exit(1);
        }
    }

    /**
     * 一个够用的自动驾驶：朝下一组开口飞。
     *
     * <p>关键是瞄准点要**低于**开口中心 —— 每次拍翅膀会上升
     * {@code JUMP_V² / (2·GRAVITY)} ≈ 68 像素，如果瞄着开口中心拍，
     * 顶点会冲到开口上沿之上直接撞管。取「开口中心 + 32」，
     * 上升区间就落在 [中心-36, 中心+32]，留够上下余量。
     *
     * <p>只要它能源源不断拿分，就证明管道间距、开口大小和碰撞判定
     * 都还在「人类可玩」的范围内。
     */
    private static void autopilot(FlappyGame g) {
        double target = FlappyGame.FLOOR_Y / 2;
        double bestDx = Double.MAX_VALUE;
        for (FlappyGame.Pipe p : g.pipes()) {
            double dx = p.x + Config.PIPE_W - Config.BIRD_X;
            if (dx > -25 && dx < bestDx) {
                bestDx = dx;
                target = p.gapCenter;
            }
        }
        double aim = target + 32;
        if (g.birdY() > aim && g.velocity() > -1.5) {
            g.flap();
        }
    }

    /** 死亡后动画时钟应当不再变化。 */
    private static boolean freezeHolds(FlappyGame g) {
        long a = g.clockMs();
        for (int i = 0; i < 30; i++) {
            g.step();
        }
        return g.clockMs() == a;
    }

    private static BufferedImage render(GameRenderer r, FlappyGame g, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D gg = img.createGraphics();
        r.render(gg, g);
        gg.dispose();
        return img;
    }

    /** 等比缩放到指定尺寸，用于压小预览 GIF。 */
    private static BufferedImage scaleDown(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // 分两步降采样，直接从 640 缩到 533 会有锯齿
        BufferedImage mid = new BufferedImage(src.getWidth() / 2, src.getHeight() / 2,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D mg = mid.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        mg.drawImage(src, 0, 0, mid.getWidth(), mid.getHeight(), null);
        mg.dispose();
        g.drawImage(mid, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** 把若干画面横向拼成一排，带标题。 */
    private static BufferedImage vstrip(List<BufferedImage> shots, int cols, int pad) {
        if (shots.isEmpty()) {
            return new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        }
        int cw = shots.get(0).getWidth();
        int ch = shots.get(0).getHeight();
        int rows = (shots.size() + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * cw + pad * (cols + 1),
                rows * ch + pad * (rows + 1), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x1B, 0x20, 0x26));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        for (int i = 0; i < shots.size(); i++) {
            int r = i / cols, c = i % cols;
            int x = pad + c * (cw + pad);
            int y = pad + r * (ch + pad);
            g.drawImage(shots.get(i), x, y, null);
            g.setColor(new Color(0x66, 0x77, 0x88));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString("#" + (i + 1), x + 4, y + 14);
        }
        g.dispose();
        return img;
    }

    /**
     * 把一串图片写成可循环播放的 GIF。
     * JDK 只提供底层 ImageWriter，延时和 loop 标记要自己塞进元数据 —— 这就是那段
     * 手工构造 GraphicControlExtension / ApplicationExtension 的原因。
     */
    private static void writeGif(List<BufferedImage> frames, File out, int delayCs) throws IOException {
        if (frames.isEmpty()) {
            return;
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);

            for (int i = 0; i < frames.size(); i++) {
                BufferedImage src = frames.get(i);
                ImageWriteParam param = writer.getDefaultWriteParam();
                IIOMetadata meta = writer.getDefaultImageMetadata(
                        ImageTypeSpecifier.createFromRenderedImage(src), param);
                String fmt = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);

                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", Integer.toString(delayCs));
                gce.setAttribute("transparentColorIndex", "0");

                if (i == 0) {
                    // 只在第一帧写一次 NETSCAPE 循环块，声明无限循环
                    IIOMetadataNode app = new IIOMetadataNode("ApplicationExtensions");
                    IIOMetadataNode ext = new IIOMetadataNode("ApplicationExtension");
                    ext.setAttribute("applicationID", "NETSCAPE");
                    ext.setAttribute("authenticationCode", "2.0");
                    ext.setUserObject(new byte[]{1, 0, 0});
                    app.appendChild(ext);
                    root.appendChild(app);
                }

                meta.setFromTree(fmt, root);
                writer.writeToSequence(new IIOImage(src, null, meta), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static IIOMetadataNode child(IIOMetadataNode parent, String name) {
        for (int i = 0; i < parent.getLength(); i++) {
            if (parent.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) parent.item(i);
            }
        }
        IIOMetadataNode n = new IIOMetadataNode(name);
        parent.appendChild(n);
        return n;
    }

    // ---------- 报告 ----------
    private static void section(String s) {
        REPORT.append("\n== ").append(s).append(" ==\n");
    }

    private static void line(String s) {
        REPORT.append("  ").append(s).append('\n');
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passCount++;
        } else {
            failCount++;
        }
        REPORT.append(ok ? "  [PASS] " : "  [FAIL] ").append(what).append('\n');
    }
}
