package guoji;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Flappy Bird 的纯逻辑层：不含任何绘制代码，也不依赖 Swing。
 *
 * <p>这么拆分有两个好处：一是可以脱离窗口跑单元验证（见 tools/HeadlessRender），
 * 二是将来想换成别的渲染方式（比如导出成 GIF）不用动逻辑。
 *
 * <p>物理用固定步长推进（1/60 秒一步），与渲染帧率解耦 ——
 * 否则高刷屏上小鸟会飞得更快，不同机器手感不一致。
 */
public final class FlappyGame {

    public enum State {
        /** 等待玩家第一次操作，果叽原地浮动。 */
        READY,
        /** 正常游戏中。 */
        PLAYING,
        /** 已撞上，正在坠落翻滚。 */
        DYING,
        /** 已落地，等待重开。 */
        OVER
    }

    /** 一组上下管道。 */
    public static final class Pipe {
        public double x;
        /** 上下管道之间的开口中心纵坐标。 */
        public final double gapCenter;
        /** 该组是否已计过分。 */
        public boolean scored;

        Pipe(double x, double gapCenter) {
            this.x = x;
            this.gapCenter = gapCenter;
        }

        public double gapTop() {
            return gapCenter - Config.PIPE_GAP / 2.0;
        }

        public double gapBottom() {
            return gapCenter + Config.PIPE_GAP / 2.0;
        }
    }

    /** 地面顶边的纵坐标，也就是可飞行区域的下界。 */
    public static final double FLOOR_Y = Config.HEIGHT - Config.GROUND_H;

    private final Random random;

    private State state = State.READY;
    private double birdY;
    private double velocity;
    private double rotation;
    private final List<Pipe> pipes = new ArrayList<>();

    private int score;
    private int best;

    /** 上一次摆动的开口中心，用来限制相邻管道的落差。 */
    private double lastGapCenter = Config.HEIGHT / 2.0;

    /** 动画时钟（毫秒）。死亡后冻结，于是果叽「定格」在那一帧。 */
    private long clockMs;

    /**
     * 世界时钟（毫秒），驱动地面与云层的滚动。
     * 它比动画时钟多走「坠落」那一段 —— 果叽定格了但背景还在动，
     * 画面才不会像卡死了一样。
     */
    private long worldMs;

    /** 小鸟落地后短暂停顿再弹出结算面板。 */
    private double overDelay;

    /** 本次死亡时是否刷新了纪录。 */
    private boolean newRecord;

    public FlappyGame() {
        this(System.nanoTime());
    }

    /**
     * @param seed 固定随机种子，让验证脚本每次跑出同一组管道
     */
    public FlappyGame(long seed) {
        this.random = new Random(seed);
        best = loadBest();
        reset();
    }

    // ==================================================================
    // 状态查询
    // ==================================================================

    public State state() {
        return state;
    }

    public double birdY() {
        return birdY;
    }

    public double birdRotation() {
        return rotation;
    }

    public List<Pipe> pipes() {
        return pipes;
    }

    public int score() {
        return score;
    }

    public int best() {
        return best;
    }

    public boolean isNewRecord() {
        return newRecord;
    }

    public long clockMs() {
        return clockMs;
    }

    public long worldMs() {
        return worldMs;
    }

    /** 竖直速度，渲染时用来决定果叽的倾斜角。 */
    public double velocity() {
        return velocity;
    }

    // ==================================================================
    // 操作
    // ==================================================================

    /** 点击 / 空格 / 上箭头。 */
    public void flap() {
        switch (state) {
            case READY -> {
                state = State.PLAYING;
                velocity = Config.JUMP_V;
            }
            case PLAYING -> velocity = Config.JUMP_V;
            case DYING -> {
                // 坠落途中不接受操作
            }
            case OVER -> {
                if (overDelay > 0) {
                    return; // 刚落地那一小会儿先别急着重开，容易误触
                }
                reset();
                state = State.PLAYING;
                velocity = Config.JUMP_V;
            }
        }
    }

    /** 回到准备状态。 */
    public void reset() {
        state = State.READY;
        birdY = Config.HEIGHT * 0.42;
        velocity = 0;
        rotation = 0;
        pipes.clear();
        score = 0;
        clockMs = 0;
        worldMs = 0;
        overDelay = 0;
        newRecord = false;
        lastGapCenter = Config.HEIGHT / 2.0;
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    /**
     * 推进一个固定步长。调用方负责按真实时间累积后多次调用。
     */
    public void step() {
        // 动画时钟只在「还没死」的时候走，死了就定格在这一帧上。
        if (state != State.DYING && state != State.OVER) {
            clockMs += (long) (Config.STEP_SEC * 1000);
        }
        // 世界时钟一直走到落地为止
        if (state != State.OVER) {
            worldMs += (long) (Config.STEP_SEC * 1000);
        }

        switch (state) {
            case READY -> stepReady();
            case PLAYING -> stepPlaying();
            case DYING -> stepDying();
            case OVER -> {
                if (overDelay > 0) {
                    overDelay -= Config.STEP_SEC;
                }
            }
        }
    }

    /** 准备状态：果叽上下浮动，顺便摆出即将起飞的姿态。 */
    private void stepReady() {
        double t = clockMs / 1000.0;
        birdY = Config.HEIGHT * 0.42 + Math.sin(t * 3.2) * 11;
        velocity = 0;
        // 微微仰头，像是蓄势待发
        rotation = -0.06;
    }

    private void stepPlaying() {
        // 重力积分
        velocity += Config.GRAVITY;
        if (velocity > Config.MAX_FALL) {
            velocity = Config.MAX_FALL;
        }
        birdY += velocity;

        // 撞顶只挡不杀 —— 原版就是这个手感，撞天花板死太劝退了
        if (birdY < Config.HIT_RY) {
            birdY = Config.HIT_RY;
            velocity = 0;
        }

        spawnPipes();
        movePipes();
        checkCollision();
        updateRotation();
    }

    private void stepDying() {
        velocity += Config.GRAVITY;
        if (velocity > Config.MAX_FALL) {
            velocity = Config.MAX_FALL;
        }
        birdY += velocity;
        rotation = Math.min(Math.PI / 2, rotation + Config.DIE_SPIN);

        if (birdY + Config.HIT_RY >= FLOOR_Y) {
            birdY = FLOOR_Y - Config.HIT_RY;
            state = State.OVER;
            overDelay = 0.35; // 秒
        }
    }

    /** 按距离投放管道，比按时间投放更稳，改速度也不会连带改难度。 */
    private void spawnPipes() {
        double spawnX = Config.WIDTH + Config.FIRST_PIPE_OFFSET;
        if (pipes.isEmpty()) {
            pipes.add(new Pipe(spawnX, pickGapCenter()));
            return;
        }
        Pipe last = pipes.get(pipes.size() - 1);
        if (last.x <= Config.WIDTH - Config.PIPE_SPACING) {
            pipes.add(new Pipe(last.x + Config.PIPE_SPACING, pickGapCenter()));
        }
    }

    /** 随机开口位置，但限制与上一组的落差，避免出现必死组合。 */
    private double pickGapCenter() {
        double half = Config.PIPE_GAP / 2.0;
        double min = Config.PIPE_MARGIN_TOP + half;
        double max = FLOOR_Y - Config.PIPE_MARGIN_BOTTOM - half;
        double c = min + random.nextDouble() * (max - min);
        // 相邻开口落差不超过 120 像素，保证有时间反应
        double delta = c - lastGapCenter;
        if (delta > 120) {
            c = lastGapCenter + 120;
        } else if (delta < -120) {
            c = lastGapCenter - 120;
        }
        c = Math.max(min, Math.min(max, c));
        lastGapCenter = c;
        return c;
    }

    private void movePipes() {
        for (int i = pipes.size() - 1; i >= 0; i--) {
            Pipe p = pipes.get(i);
            p.x -= Config.PIPE_SPEED;

            if (!p.scored && p.x + Config.PIPE_W < Config.BIRD_X) {
                p.scored = true;
                score++;
            }
            if (p.x + Config.PIPE_W < -10) {
                pipes.remove(i);
            }
        }
    }

    private void checkCollision() {
        // 落到地面
        if (birdY + Config.HIT_RY >= FLOOR_Y) {
            birdY = FLOOR_Y - Config.HIT_RY;
            die();
            return;
        }

        for (Pipe p : pipes) {
            if (Config.BIRD_X + Config.HIT_RX < p.x || Config.BIRD_X - Config.HIT_RX > p.x + Config.PIPE_W) {
                continue; // 横向没重叠，直接跳过
            }
            // 上下两段矩形，任一相交即判定撞上
            if (ellipseHitsRect(Config.BIRD_X, birdY, Config.HIT_RX, Config.HIT_RY,
                    p.x, -1000, Config.PIPE_W, p.gapTop() + 1000)) {
                die();
                return;
            }
            if (ellipseHitsRect(Config.BIRD_X, birdY, Config.HIT_RX, Config.HIT_RY,
                    p.x, p.gapBottom(), Config.PIPE_W, FLOOR_Y - p.gapBottom() + 1000)) {
                die();
                return;
            }
        }
    }

    private void die() {
        if (state == State.OVER || state == State.DYING) {
            return;
        }
        newRecord = score > best;
        if (newRecord) {
            best = score;
            saveBest(best);
        }
        state = State.DYING;
        velocity = Math.max(velocity, 1.2);
        // 死亡瞬间把动画定格在当前帧，看起来就像「僵住」了
    }

    /** 椭圆与矩形是否相交：把圆心夹到矩形上取最近点，再代入椭圆方程。 */
    private static boolean ellipseHitsRect(double cx, double cy, double rx, double ry,
                                           double rectX, double rectY, double rectW, double rectH) {
        double nx = Math.max(rectX, Math.min(cx, rectX + rectW));
        double ny = Math.max(rectY, Math.min(cy, rectY + rectH));
        double dx = (cx - nx) / rx;
        double dy = (cy - ny) / ry;
        return dx * dx + dy * dy <= 1.0;
    }

    /** 抬头上升、低头俯冲，是 Flappy Bird 最直观的手感反馈。 */
    private void updateRotation() {
        if (!Config.TILT) {
            rotation = 0;
            return;
        }
        double target = velocity < 0
                ? -0.42                        // 上升：最多仰到约 -24°
                : Math.min(Math.PI / 2, velocity * 0.10); // 下落：最多俯到 90°
        // 一阶低通，避免角度突跳
        rotation += (target - rotation) * 0.22;
    }

    // ==================================================================
    // 最高分持久化
    // ==================================================================

    private static File bestFile() {
        return new File(Config.BEST_SCORE_FILE);
    }

    private static int loadBest() {
        try {
            File f = bestFile();
            if (f.isFile()) {
                String s = Files.readString(f.toPath(), StandardCharsets.UTF_8).trim();
                return Integer.parseInt(s);
            }
        } catch (Exception ignored) {
            // 读不到就当 0，不打扰玩家
        }
        return 0;
    }

    private static void saveBest(int v) {
        try {
            Files.writeString(bestFile().toPath(), Integer.toString(v), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 存不了就算了，游戏照常玩
        }
    }
}
