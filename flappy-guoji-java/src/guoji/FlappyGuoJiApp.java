package guoji;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 程序入口：窗口、输入、主循环。
 *
 * <p>主循环用「累加器 + 固定步长」：
 * 每帧算出真实经过了多少秒，攒够 1/60 秒就推进一步逻辑。
 * 这样 60Hz 和 144Hz 屏幕上小鸟的飞行轨迹完全一致，
 * 而渲染帧率仍然可以自由浮动。
 */
public final class FlappyGuoJiApp extends JPanel {

    private static final long NANOS_PER_SEC = 1_000_000_000L;

    private final FlappyGame game;
    private final GameRenderer renderer;

    /** 逻辑分辨率的离屏画布，保证任何窗口尺寸下画面比例都不变形。 */
    private final BufferedImage buffer;

    private double accumulator;
    private long lastNanos;

    // 由外层窗口设置，用于处理 ESC
    private JFrame frame;

    public FlappyGuoJiApp(GifSprite bird) {
        this.game = new FlappyGame();
        this.renderer = new GameRenderer(bird);
        this.buffer = new BufferedImage(Config.WIDTH, Config.HEIGHT, BufferedImage.TYPE_INT_RGB);

        setPreferredSize(new Dimension(Config.WIDTH, Config.HEIGHT));
        setBackground(new Color(0x22, 0x28, 0x2E));
        setFocusable(true);

        installInput();

        Timer timer = new Timer(8, this::tick);
        timer.setCoalesce(true);
        lastNanos = System.nanoTime();
        timer.start();
    }

    // ==================================================================
    // 输入
    // ==================================================================

    private void installInput() {
        // 鼠标：点哪儿都算拍翅膀
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                game.flap();
            }
        });

        // 键盘：用 InputMap 而不是 KeyListener —— 后者要求控件必须先拿到焦点，
        // 而 Swing 里焦点很容易被别的东西抢走。
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getActionMap();

        bind(im, am, "flap-space", KeyStroke.getKeyStroke("SPACE"));
        bind(im, am, "flap-up", KeyStroke.getKeyStroke("UP"));
        bind(im, am, "flap-w", KeyStroke.getKeyStroke("W"));
        bind(im, am, "flap-enter", KeyStroke.getKeyStroke("ENTER"));

        // R 重开，随时可用
        im.put(KeyStroke.getKeyStroke("R"), "restart");
        am.put("restart", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                game.reset();
                repaint();
            }
        });

        // ESC 退出
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "quit");
        am.put("quit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (frame != null) {
                    frame.dispose();
                }
                System.exit(0);
            }
        });
    }

    private void bind(InputMap im, ActionMap am, String name, KeyStroke ks) {
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                game.flap();
            }
        });
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    private void tick(ActionEvent e) {
        long now = System.nanoTime();
        double elapsed = (now - lastNanos) / (double) NANOS_PER_SEC;
        lastNanos = now;

        // 后台切回来时 elapsed 可能是几十秒，这里封顶，
        // 否则一回到窗口小鸟就瞬移穿墙了。
        if (elapsed > 0.25) {
            elapsed = 0.25;
        }

        accumulator += elapsed;
        int steps = 0;
        while (accumulator >= Config.STEP_SEC && steps < Config.MAX_STEPS_PER_FRAME) {
            game.step();
            accumulator -= Config.STEP_SEC;
            steps++;
        }
        if (steps == Config.MAX_STEPS_PER_FRAME) {
            // 补不完就丢掉，宁可掉帧也不要越算越慢
            accumulator = 0;
        }

        repaint();
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();

        int pw = getWidth();
        int ph = getHeight();

        // 先按逻辑分辨率画到离屏图上
        Graphics2D bg = buffer.createGraphics();
        try {
            renderer.render(bg, game);
        } finally {
            bg.dispose();
        }

        // 再等比放大到窗口，两侧留黑边（letterbox）
        double scale = Math.min(pw / (double) Config.WIDTH, ph / (double) Config.HEIGHT);
        int dw = (int) Math.round(Config.WIDTH * scale);
        int dh = (int) Math.round(Config.HEIGHT * scale);
        int dx = (pw - dw) / 2;
        int dy = (ph - dh) / 2;

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(buffer, dx, dy, dw, dh, null);

        if (dx > 0 || dy > 0) {
            g.setColor(new Color(0x14, 0x18, 0x1C));
            g.setStroke(new java.awt.BasicStroke(2f));
            g.drawRect(dx, dy, dw, dh);
        }
        g.dispose();
    }

    // ==================================================================
    // 启动
    // ==================================================================

    public static void main(String[] args) {
        // 素材路径：优先命令行参数，其次 -Dguoji.gif=，最后按候选相对路径找。
        File gif = resolveGif(args);

        GifSprite bird;
        try {
            bird = GifSprite.load(gif, Config.KEEP_CAPTION);
        } catch (IOException ex) {
            String msg = "无法加载果叽素材：\n" + gif.getAbsolutePath() + "\n\n" + ex.getMessage();
            System.err.println(msg);
            try {
                JOptionPane.showMessageDialog(null, msg, "果叽飞飞", JOptionPane.ERROR_MESSAGE);
            } catch (HeadlessException ignored) {
                // 无界面环境就不弹窗了
            }
            return;
        }

        System.out.println("素材加载成功：" + gif.getPath());
        System.out.println("  " + bird.frameCount() + " 帧 / " + bird.loopDurationMs()
                + "ms 一轮 / 裁剪后 " + bird.sourceWidth() + "x" + bird.sourceHeight());
        System.out.println("  中文字体：" + new GameRenderer(bird).fontFamily());

        try {
            javax.swing.SwingUtilities.invokeLater(() -> {
                FlappyGuoJiApp panel = new FlappyGuoJiApp(bird);
                JFrame f = new JFrame("果叽飞飞 —— Flappy Bird");
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                f.setContentPane(panel);
                panel.frame = f;
                f.pack();
                f.setLocationRelativeTo(null);
                f.setMinimumSize(new Dimension(240, 400));
                // 窗口拉伸时重新布局，保持等比
                f.addComponentListener(new ComponentAdapter() {
                    @Override
                    public void componentResized(ComponentEvent e) {
                        panel.repaint();
                    }
                });
                f.setVisible(true);
                panel.requestFocusInWindow();
            });
        } catch (HeadlessException ex) {
            System.err.println("当前环境没有图形界面，无法打开窗口。");
        }
    }

    /** 依次尝试命令行参数、系统属性、若干候选相对路径。 */
    private static File resolveGif(String[] args) {
        if (args.length > 0) {
            File f = new File(args[0]);
            if (f.isFile()) {
                return f;
            }
        }
        String prop = System.getProperty("guoji.gif");
        if (prop != null && !prop.isBlank()) {
            File f = new File(prop);
            if (f.isFile()) {
                return f;
            }
        }
        for (String c : Config.GIF_CANDIDATES) {
            File f = new File(c);
            if (f.isFile()) {
                return f;
            }
        }
        // 都没找到也返回第一个候选，让报错信息里带上一个明确的路径
        return new File(Config.GIF_CANDIDATES[0]);
    }
}
