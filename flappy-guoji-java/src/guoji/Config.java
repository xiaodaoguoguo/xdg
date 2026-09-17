package guoji;

/**
 * 全局可调参数。手感不对就改这里，改完重新编译即可。
 */
public final class Config {

    private Config() {
    }

    // ---------- 窗口 / 画布 ----------
    /** 逻辑画布宽（像素）。渲染时按窗口大小等比缩放。 */
    public static final int WIDTH = 360;
    /** 逻辑画布高。 */
    public static final int HEIGHT = 640;
    /** 地面高度。 */
    public static final int GROUND_H = 96;

    // ---------- 小鸟 ----------
    /** 小鸟固定横坐标。 */
    public static final double BIRD_X = 92;
    /** 每帧重力加速度（像素/帧²，60 帧/秒）。 */
    public static final double GRAVITY = 0.38;
    /** 起跳瞬时速度（负数向上）。 */
    public static final double JUMP_V = -7.2;
    /** 下落速度上限，防止坠落过快穿透管道。 */
    public static final double MAX_FALL = 11.0;
    /** 果叽精灵绘制宽度。 */
    public static final int BIRD_W = 74;
    /** 果叽精灵绘制高度（保持素材 230:180 的原始比例）。 */
    public static final int BIRD_H = 58;
    /**
     * 碰撞椭圆半轴。故意比贴图小一圈，让判定宽容些 ——
     * 这是原作手感的关键，果叽张开的手臂和蓬松的轮廓不该算进碰撞体。
     * 实际本体约占贴图宽度的 0.7，所以 17 大约是本体的外接圆半径。
     */
    public static final double HIT_RX = 17.0;
    public static final double HIT_RY = 14.0;
    /** 死亡后小鸟的翻滚速度（弧度/帧），越大越狼狈。 */
    public static final double DIE_SPIN = 0.22;

    // ---------- 管道 ----------
    /** 管道宽度。 */
    public static final int PIPE_W = 62;
    /** 上下管道之间的空隙高度。 */
    public static final int PIPE_GAP = 150;
    /** 管道水平移动速度（像素/帧）。 */
    public static final double PIPE_SPEED = 2.4;
    /** 相邻两组管道的间隔（像素）。 */
    public static final int PIPE_SPACING = 208;
    /** 开口中心离画布上下边缘的最小距离，避免管道太贴边。 */
    public static final int PIPE_MARGIN_TOP = 70;
    public static final int PIPE_MARGIN_BOTTOM = 90;
    /** 第 1 组管道出现前，先给玩家留出的空跑距离。 */
    public static final int FIRST_PIPE_OFFSET = 260;

    // ---------- 节奏 ----------
    /** 固定逻辑步长：1/60 秒。 */
    public static final double STEP_SEC = 1.0 / 60.0;
    /** 单帧最多补算的逻辑步数，防止窗口卡顿后瞬间穿模。 */
    public static final int MAX_STEPS_PER_FRAME = 5;

    // ---------- 表现 ----------
    /** 是否让果叽按飞行姿态倾斜。 */
    public static final boolean TILT = true;
    /** 最高分文件。 */
    public static final String BEST_SCORE_FILE = ".flappy-guoji-best";

    // ---------- 素材 ----------
    /**
     * GIF 顶部有一行「我也要！我也要！」文字。缩小到 62 像素宽后这行字会糊成一团，
     * 所以默认按空行把它裁掉，只保留果叽本体。
     * 想连文字一起显示（原汁原味的表情包）就改成 true。
     */
    public static final boolean KEEP_CAPTION = false;
    /** 找不到素材时的相对路径候选。 */
    public static final String[] GIF_CANDIDATES = {
            "assets/我也要.gif",
            "我也要.gif",
            "../我也要.gif",
            "../assets/我也要.gif",
    };
}
