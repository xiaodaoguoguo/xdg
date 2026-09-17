package tools;

import guoji.Config;
import guoji.GifSprite;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 素材自检工具：把 GIF 解码后的每帧导出成 PNG，便于肉眼确认
 * 「帧数对不对、透明干不干净、文字裁掉没有、缩到游戏尺寸还看不看得清」。
 *
 * 用法：java -cp out tools.DumpFrames <gif路径> <输出目录>
 */
public class DumpFrames {

    public static void main(String[] args) throws Exception {
        File gif = new File(args.length > 0 ? args[0] : "assets/我也要.gif");
        File outDir = new File(args.length > 1 ? args[1] : "out/dump");
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("建不了输出目录: " + outDir);
        }

        GifSprite sp = GifSprite.load(gif, Config.KEEP_CAPTION);
        System.out.println("素材: " + gif.getAbsolutePath());
        System.out.println("帧数: " + sp.frameCount());
        System.out.println("循环时长: " + sp.loopDurationMs() + " ms");
        System.out.println("裁剪后尺寸: " + sp.sourceWidth() + " x " + sp.sourceHeight()
                + "  (文字分界线 y=" + sp.captionCutY() + ")");

        for (int i = 0; i < sp.frameCount(); i++) {
            int t = 0;
            for (int k = 0; k < i; k++) {
                t += sp.loopDurationMs() / sp.frameCount();
            }
            System.out.println("  frame" + i + " 起始时刻 " + t + "ms -> 应显示索引 "
                    + sp.frameIndexAt(t));
        }

        // 逐帧导出原始裁剪尺寸
        for (int i = 0; i < sp.frameCount(); i++) {
            BufferedImage f = renderOne(sp, i, sp.sourceWidth(), sp.sourceHeight());
            ImageIO.write(f, "png", new File(outDir, "sprite_" + i + ".png"));
        }

        // 导出游戏里的实际显示尺寸（62x49），下面垫深色和浅色两种底，
        // 用来检查缩放后有没有白边。
        BufferedImage small = renderStrip(sp, Config.BIRD_W, Config.BIRD_H, 2);
        ImageIO.write(small, "png", new File(outDir, "ingame_scale.png"));

        // 导出放大 4 倍的版本，方便看清边缘质量
        BufferedImage big = renderStrip(sp, Config.BIRD_W * 4, Config.BIRD_H * 4, 2);
        ImageIO.write(big, "png", new File(outDir, "ingame_scale_x4.png"));

        System.out.println("已导出到 " + outDir.getAbsolutePath());
    }

    /** 在指定时刻渲染单帧，背景透明。 */
    private static BufferedImage renderOne(GifSprite sp, int frameIdx, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // 用「刚好落在该帧起点」的时间去取帧
        long t = 0;
        for (int k = 0; k < frameIdx; k++) {
            t += sp.loopDurationMs() / sp.frameCount();
        }
        sp.draw(g, w / 2.0, h / 2.0, w, h, t, 0);
        g.dispose();
        return img;
    }

    /** 把各帧横向排开，垫深/浅棋盘底，用于检查边缘白边。 */
    private static BufferedImage renderStrip(GifSprite sp, int w, int h, int pad) {
        int n = sp.frameCount();
        int cellW = w + pad * 2;
        int cellH = h + pad * 2 + 18;
        BufferedImage img = new BufferedImage(cellW * n, cellH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (int i = 0; i < n; i++) {
            int x0 = i * cellW;
            // 左半边深底、右半边浅底：白边在这两种底上都会露馅
            g.setColor(new Color(0x2B, 0x3A, 0x4A));
            g.fillRect(x0, 0, cellW / 2, cellH - 18);
            g.setColor(new Color(0x7E, 0xD3, 0xE8));
            g.fillRect(x0 + cellW / 2, 0, cellW - cellW / 2, cellH - 18);

            long t = 0;
            for (int k = 0; k < i; k++) {
                t += sp.loopDurationMs() / sp.frameCount();
            }
            sp.draw(g, x0 + cellW / 2.0, pad + h / 2.0, w, h, t, 0);

            g.setColor(Color.WHITE);
            g.fillRect(x0, cellH - 18, cellW, 18);
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            g.drawString("frame " + i + "  " + w + "x" + h, x0 + 6, cellH - 5);
        }
        g.dispose();
        return img;
    }
}
