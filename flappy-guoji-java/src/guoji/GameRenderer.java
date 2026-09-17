package guoji;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * 把 {@link FlappyGame} 的状态画到任意 Graphics2D 上。
 *
 * <p>全程使用 Config 里的逻辑坐标系（360x640），窗口缩放交给调用方设置
 * 变换矩阵。这样窗口拉大拉小、导出离屏图，走的都是同一套绘制代码。
 */
public final class GameRenderer {

    // ---------------- 配色 ----------------
    private static final Color SKY_TOP = new Color(0x5F, 0xC9, 0xE8);
    private static final Color SKY_MID = new Color(0xA6, 0xE3, 0xF2);
    private static final Color SKY_LOW = new Color(0xDF, 0xF4, 0xF7);

    /**
     * 远山刻意用很淡的颜色，而且压在画面下方 ——
     * 深绿的管道要在上面清晰地立起来，背景一旦抢眼就没法玩了。
     */
    private static final Color HILL_FAR = new Color(0xC2, 0xE9, 0xE2);
    private static final Color HILL_NEAR = new Color(0xA4, 0xDC, 0xD0);

    private static final Color PIPE_DARK = new Color(0x1E, 0x5B, 0x2E);
    private static final Color PIPE_MAIN = new Color(0x46, 0xA8, 0x5A);
    private static final Color PIPE_LIGHT = new Color(0x86, 0xD6, 0x93);

    private static final Color GRASS = new Color(0xA9, 0xDD, 0x7C);
    private static final Color GRASS_DARK = new Color(0x86, 0xBE, 0x5C);
    private static final Color DIRT = new Color(0xE0, 0xD2, 0x92);
    /** 泥土斜纹只是给地面一点质感，对比度压低，别抢镜。 */
    private static final Color DIRT_DARK = new Color(0xD3, 0xC3, 0x82);

    private static final Color INK = new Color(0x33, 0x33, 0x33);
    private static final Color CREAM = new Color(0xFF, 0xFB, 0xF0);
    private static final Color BLUSH = new Color(0xFC, 0xB9, 0xBE);

    /** 管道管帽高出管身两侧的宽度。 */
    private static final int CAP_OVERHANG = 5;
    /** 管帽高度。 */
    private static final int CAP_H = 26;

    private final GifSprite bird;
    private final String fontFamily;
    private final Random decorRandom = new Random(20260917L);

    /** 背景装饰（云、山）的固定随机布局，每帧重新算会闪。 */
    private final double[] cloudX;
    private final double[] cloudY;
    private final double[] cloudScale;

    public GameRenderer(GifSprite bird) {
        this.bird = bird;
        this.fontFamily = pickFontFamily();

        int n = 5;
        cloudX = new double[n];
        cloudY = new double[n];
        cloudScale = new double[n];
        for (int i = 0; i < n; i++) {
            cloudX[i] = decorRandom.nextDouble() * Config.WIDTH * 1.6;
            cloudY[i] = 50 + decorRandom.nextDouble() * 210;
            cloudScale[i] = 0.65 + decorRandom.nextDouble() * 0.75;
        }
    }

    /** 挑一个装了中文字形的字体，避免标题变成一排方块。 */
    private static String pickFontFamily() {
        Set<String> available = new HashSet<>();
        try {
            available.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.CHINA)));
            available.addAll(Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames()));
        } catch (Throwable ignored) {
            // 拿不到字体列表就用兜底字体
        }
        String[] candidates = {
                "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
                "PingFang SC", "Noto Sans CJK SC", "Source Han Sans SC",
                "SimHei", "黑体", "SimSun", "Dialog", "SansSerif",
        };
        for (String c : candidates) {
            if (available.contains(c)) {
                return c;
            }
        }
        return Font.SANS_SERIF;
    }

    private Font font(int style, float size) {
        return new Font(fontFamily, style, Math.round(size));
    }

    /** 地面顶边的整数纵坐标，避免到处写强制转换。 */
    private static int floorY() {
        return (int) FlappyGame.FLOOR_Y;
    }

    // ==================================================================
    // 入口
    // ==================================================================

    public void render(Graphics2D g, FlappyGame game) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        double worldSec = game.worldMs() / 1000.0;
        double scrollX = worldSec * Config.PIPE_SPEED * 60.0;

        drawSky(g);
        drawClouds(g, scrollX * 0.18);
        drawHills(g, scrollX * 0.35);
        drawPipes(g, game);
        drawGround(g, scrollX);
        drawBird(g, game);
        drawHud(g, game);
        drawOverlays(g, game);
    }

    // ==================================================================
    // 背景
    // ==================================================================

    private void drawSky(Graphics2D g) {
        LinearGradientPaint sky = new LinearGradientPaint(
                0f, 0f, 0f, (float) FlappyGame.FLOOR_Y,
                new float[]{0f, 0.55f, 1f},
                new Color[]{SKY_TOP, SKY_MID, SKY_LOW});
        g.setPaint(sky);
        g.fillRect(0, 0, Config.WIDTH, floorY() + 1);

        // 右上角一团柔和的太阳光晕
        float cx = Config.WIDTH - 62f;
        float cy = 78f;
        for (int i = 6; i >= 1; i--) {
            float r = 26f + i * 11f;
            int alpha = 10 + (6 - i) * 5;
            g.setColor(new Color(255, 250, 220, alpha));
            g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        }
        g.setColor(new Color(0xFF, 0xF6, 0xC9, 235));
        g.fill(new Ellipse2D.Float(cx - 26, cy - 26, 52, 52));
    }

    private void drawClouds(Graphics2D g, double offset) {
        g.setColor(new Color(255, 255, 255, 205));
        double span = Config.WIDTH * 1.6;
        for (int i = 0; i < cloudX.length; i++) {
            double x = cloudX[i] - offset;
            // 循环回绕，云永远不会跑光
            x = ((x % span) + span) % span - Config.WIDTH * 0.3;
            drawCloud(g, x, cloudY[i], cloudScale[i]);
        }
    }

    /** 用几个圆叠出一朵云，比贴图省事，缩放也不会糊。 */
    private void drawCloud(Graphics2D g, double x, double y, double s) {
        g.fill(new Ellipse2D.Double(x, y, 46 * s, 34 * s));
        g.fill(new Ellipse2D.Double(x + 22 * s, y - 12 * s, 54 * s, 46 * s));
        g.fill(new Ellipse2D.Double(x + 52 * s, y + 2 * s, 40 * s, 30 * s));
    }

    /** 双层远山，越远越淡、越慢，做出纵深，并整体压在画面下部。 */
    private void drawHills(Graphics2D g, double offset) {
        drawHillLayer(g, offset, 388, 98, 178, HILL_FAR);
        drawHillLayer(g, offset + 92, 428, 76, 142, HILL_NEAR);
    }

    private void drawHillLayer(Graphics2D g, double offset, double baseY,
                               double humpH, double humpW, Color color) {
        g.setColor(color);
        double period = humpW * 1.15;
        // 多画一轮，保证左右两侧都被填满
        int count = (int) Math.ceil(Config.WIDTH / period) + 3;
        double start = -((offset % period) + period) % period - period;
        for (int i = 0; i < count; i++) {
            double cx = start + i * period;
            g.fill(new Ellipse2D.Double(cx, baseY, humpW, humpH * 2));
        }
        g.fillRect(0, (int) (baseY + humpH * 0.6), Config.WIDTH, floorY());
    }

    // ==================================================================
    // 管道
    // ==================================================================

    private void drawPipes(Graphics2D g, FlappyGame game) {
        for (FlappyGame.Pipe p : game.pipes()) {
            double x = p.x;
            // 屏幕外的直接跳过
            if (x > Config.WIDTH + 10 || x + Config.PIPE_W < -20) {
                continue;
            }
            drawPipeBody(g, x, -CAP_H, Config.PIPE_W, p.gapTop() + CAP_H);
            drawPipeCap(g, x, p.gapTop() - CAP_H);
            drawPipeBody(g, x, p.gapBottom(), Config.PIPE_W, FlappyGame.FLOOR_Y - p.gapBottom());
            drawPipeCap(g, x, p.gapBottom());
        }
    }

    private void drawPipeBody(Graphics2D g, double x, double y, double w, double h) {
        if (h <= 0) {
            return;
        }
        // 横向渐变：左亮右暗，圆柱感
        g.setPaint(new LinearGradientPaint(
                (float) x, 0, (float) (x + w), 0,
                new float[]{0f, 0.18f, 0.62f, 1f},
                new Color[]{PIPE_DARK, PIPE_LIGHT, PIPE_MAIN, PIPE_DARK}));
        g.fill(new java.awt.geom.Rectangle2D.Double(x, y, w, h));
        g.setColor(PIPE_DARK);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(new java.awt.geom.Rectangle2D.Double(x + 1.25, y, w - 2.5, h));
    }

    private void drawPipeCap(Graphics2D g, double x, double y) {
        double cx = x - CAP_OVERHANG;
        double cw = Config.PIPE_W + CAP_OVERHANG * 2.0;
        Shape cap = new RoundRectangle2D.Double(cx, y, cw, CAP_H, 7, 7);
        g.setPaint(new LinearGradientPaint(
                (float) cx, 0, (float) (cx + cw), 0,
                new float[]{0f, 0.18f, 0.62f, 1f},
                new Color[]{PIPE_DARK, PIPE_LIGHT, PIPE_MAIN, PIPE_DARK}));
        g.fill(cap);
        g.setColor(PIPE_DARK);
        g.setStroke(new BasicStroke(2.5f));
        g.draw(cap);
    }

    // ==================================================================
    // 地面
    // ==================================================================

    private void drawGround(Graphics2D g, double scrollX) {
        int gy = (int) FlappyGame.FLOOR_Y;

        g.setColor(DIRT);
        g.fillRect(0, gy, Config.WIDTH, Config.HEIGHT - gy);

        // 斜纹泥土，滚动方向与管道一致
        g.setColor(DIRT_DARK);
        int stripe = 26;
        int shift = (int) (-scrollX % stripe);
        for (int x = shift - stripe * 2; x < Config.WIDTH + stripe; x += stripe) {
            g.fillPolygon(
                    new int[]{x, x + 13, x + 13 + 40, x + 40},
                    new int[]{gy + 18, gy + 18, Config.HEIGHT, Config.HEIGHT},
                    4);
        }

        // 草皮
        g.setColor(GRASS);
        g.fillRect(0, gy, Config.WIDTH, 17);
        g.setColor(GRASS_DARK);
        g.fillRect(0, gy + 17, Config.WIDTH, 5);

        // 草叶：用确定性伪随机，滚动时形状保持一致
        g.setColor(GRASS_DARK);
        int tooth = 16;
        int tShift = (int) (-scrollX % tooth);
        for (int x = tShift - tooth * 2; x < Config.WIDTH + tooth; x += tooth) {
            g.fillPolygon(
                    new int[]{x, x + 8, x + 16},
                    new int[]{gy + 5, gy + 17, gy + 5},
                    3);
        }

        g.setColor(new Color(0x9E, 0x8F, 0x5A));
        g.fillRect(0, gy, Config.WIDTH, 2);
    }

    // ==================================================================
    // 果叽
    // ==================================================================

    private void drawBird(Graphics2D g, FlappyGame game) {
        double cx = Config.BIRD_X;
        double cy = game.birdY();

        // 落地后贴一层淡影，让它和地面有接触感
        if (game.birdY() + Config.HIT_RY >= FlappyGame.FLOOR_Y - 2) {
            g.setColor(new Color(0, 0, 0, 38));
            g.fill(new Ellipse2D.Double(cx - 26, FlappyGame.FLOOR_Y - 6, 52, 11));
        }

        bird.draw(g, cx, cy, Config.BIRD_W, Config.BIRD_H, game.clockMs(), game.birdRotation());
    }

    // ==================================================================
    // HUD 与浮层
    // ==================================================================

    private void drawHud(Graphics2D g, FlappyGame game) {
        if (game.state() == FlappyGame.State.READY) {
            return;
        }
        String s = Integer.toString(game.score());
        Font f = font(Font.BOLD, 52);
        drawOutlined(g, s, Config.WIDTH / 2.0, 96, f, CREAM, INK, 6f);
    }

    private void drawOverlays(Graphics2D g, FlappyGame game) {
        switch (game.state()) {
            case READY -> drawReady(g, game);
            case OVER -> drawGameOver(g, game);
            default -> {
            }
        }
    }

    private void drawReady(Graphics2D g, FlappyGame game) {
        // 标题
        drawOutlined(g, "果叽飞飞", Config.WIDTH / 2.0, 150, font(Font.BOLD, 46),
                CREAM, INK, 7f);
        drawOutlined(g, "Flappy 果叽", Config.WIDTH / 2.0, 186, font(Font.PLAIN, 17),
                new Color(0x4A, 0x6A, 0x72), new Color(255, 255, 255, 200), 3f);

        // 操作提示，轻轻上下浮动
        double t = game.clockMs() / 1000.0;
        double bob = Math.sin(t * 3.0) * 6;
        drawOutlined(g, "点击 / 空格 起飞", Config.WIDTH / 2.0, 470 + bob,
                font(Font.BOLD, 20), CREAM, INK, 5f);

        // 一只会上下点的小手，暗示「点这里」
        double hx = Config.WIDTH / 2.0 + 62;
        double hy = 500 + bob;
        g.setColor(new Color(0xFF, 0xD8, 0x8A));
        g.fill(new Ellipse2D.Double(hx - 11, hy - 11, 22, 22));
        g.setColor(new Color(0xC9, 0x9A, 0x4A));
        g.setStroke(new BasicStroke(2.2f));
        g.draw(new Ellipse2D.Double(hx - 11, hy - 11, 22, 22));

        if (game.best() > 0) {
            drawOutlined(g, "最高分 " + game.best(), Config.WIDTH / 2.0, 570,
                    font(Font.PLAIN, 15), new Color(0x4A, 0x6A, 0x72),
                    new Color(255, 255, 255, 190), 3f);
        }
    }

    private void drawGameOver(Graphics2D g, FlappyGame game) {
        g.setColor(new Color(0, 0, 0, 70));
        g.fillRect(0, 0, Config.WIDTH, Config.HEIGHT);

        double pw = 268;
        double ph = 216;
        double px = (Config.WIDTH - pw) / 2.0;
        double py = 190;

        // 面板投影
        g.setColor(new Color(0, 0, 0, 45));
        g.fill(new RoundRectangle2D.Double(px + 4, py + 6, pw, ph, 22, 22));

        g.setColor(new Color(0xFF, 0xFD, 0xF6));
        g.fill(new RoundRectangle2D.Double(px, py, pw, ph, 22, 22));
        g.setColor(INK);
        g.setStroke(new BasicStroke(3f));
        g.draw(new RoundRectangle2D.Double(px, py, pw, ph, 22, 22));

        drawOutlined(g, "撞啦！", Config.WIDTH / 2.0, py + 54, font(Font.BOLD, 30),
                new Color(0xFF, 0x8A, 0x8A), INK, 5f);

        // 分数 / 最高分两栏
        drawOutlined(g, "本次", Config.WIDTH / 2.0 - 62, py + 96, font(Font.PLAIN, 15),
                new Color(0x77, 0x88, 0x8C), null, 0);
        drawOutlined(g, Integer.toString(game.score()), Config.WIDTH / 2.0 - 62, py + 136,
                font(Font.BOLD, 38), INK, null, 0);

        drawOutlined(g, "最高", Config.WIDTH / 2.0 + 62, py + 96, font(Font.PLAIN, 15),
                new Color(0x77, 0x88, 0x8C), null, 0);
        drawOutlined(g, Integer.toString(game.best()), Config.WIDTH / 2.0 + 62, py + 136,
                font(Font.BOLD, 38), BLUSH, INK, 3f);

        if (game.isNewRecord()) {
            // 新纪录缎带
            Shape tag = new RoundRectangle2D.Double(Config.WIDTH / 2.0 - 48, py + 152, 96, 24, 12, 12);
            g.setColor(new Color(0xFF, 0xB1, 0xB8));
            g.fill(tag);
            g.setColor(INK);
            g.setStroke(new BasicStroke(2f));
            g.draw(tag);
            drawOutlined(g, "新纪录！", Config.WIDTH / 2.0, py + 170, font(Font.BOLD, 15),
                    CREAM, INK, 2.5f);
        }

        if (game.state() == FlappyGame.State.OVER) {
            double bob = Math.sin(game.worldMs() / 1000.0 * 3.0) * 4;
            drawOutlined(g, "点击 / 空格 再来一次", Config.WIDTH / 2.0, py + ph + 52 + bob,
                    font(Font.BOLD, 18), CREAM, INK, 5f);
        }
    }

    // ==================================================================
    // 描边文字
    // ==================================================================

    /**
     * 画带描边的文字，居中在 cx 上，基线在 baseline。
     * 卡通风格的关键 —— 光靠 fillText 白字会飘在背景里看不清。
     */
    private void drawOutlined(Graphics2D g, String text, double cx, double baseline,
                              Font font, Color fill, Color outline, float strokeW) {
        FontRenderContext frc = g.getFontRenderContext();
        GlyphVector gv = font.createGlyphVector(frc, text);
        double x = cx - gv.getVisualBounds().getWidth() / 2.0 - gv.getVisualBounds().getX();

        if (outline != null && strokeW > 0) {
            Shape s = gv.getOutline((float) x, (float) baseline);
            g.setColor(outline);
            g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
        g.setColor(fill);
        g.fill(gv.getOutline((float) x, (float) baseline));
    }

    /** 供外部（如离屏导出）查询当前选用字体。 */
    public String fontFamily() {
        return fontFamily;
    }
}
