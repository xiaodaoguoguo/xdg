> **本目录是 `xdg` 仓库里「果叽飞飞」的 Java 桌面版存档。**
> 想在浏览器里直接玩，用仓库根目录的 [`guoji-feifei.html`](../guoji-feifei.html) ——
> 那是同一套玩法与数值的网页复刻，打开即玩、不用装 JDK。
> 下面这份是 Java/Swing 版，保留完整源码、可调参数和离屏验收工具。

# 果叽飞飞（Flappy Guoji）

用 Java 写的 Flappy Bird 复刻版，小鸟换成了 `我也要.gif` 里的果叽表情动画。

## 跑起来

三种方式，任选一种：

**1. 双击 `run.bat`**（推荐）
会自动找 JDK、必要时先编译，然后打开游戏窗口。

**2. IntelliJ IDEA**
`File → Open` 打开本目录，把 `src` 标成 Sources Root，运行 `guoji.FlappyGuoJiApp`。
编译时注意加 `-encoding UTF-8`（源码里有中文）。

**3. 命令行**

```bash
javac -encoding UTF-8 -d out src/guoji/*.java tools/*.java
java -Dfile.encoding=UTF-8 -cp out guoji.FlappyGuoJiApp
```

需要 JDK 17 以上（本机是 `D:\kaifa\jdk21`，JDK 21）。

## 操作

| 操作 | 按键 |
|---|---|
| 上升 | **空格** / **↑** / **W** / **Enter** / 鼠标点击 |
| 重开 | **R** |
| 退出 | **Esc** |

撞管道或落地就结束；撞到画布顶部只是被挡住，不会死（原版就是这个手感）。
窗口可以随意拉伸，画面保持比例、两侧留黑边，不会变形。

## 目录结构

```
FlappyGuoJi/
├─ assets/我也要.gif      素材（原文件的副本）
├─ src/guoji/
│   ├─ Config.java        所有可调参数都在这
│   ├─ GifSprite.java     GIF 解码 + 逐帧循环播放 + 缩放
│   ├─ FlappyGame.java    纯游戏逻辑（物理/管道/碰撞/计分），不碰绘制
│   ├─ GameRenderer.java  绘制（天空/云/远山/管道/地面/HUD）
│   └─ FlappyGuoJiApp.java 窗口、输入、主循环
├─ tools/
│   ├─ DumpFrames.java    导出 GIF 各帧，检查素材和边缘质量
│   └─ HeadlessRender.java 离线跑验证 + 导出游戏截图与预览 GIF
├─ build.bat / run.bat
└─ out/                   编译产物；out/verify 下是验证截图
```

## 想改手感就改 `Config.java`

几个最常动的：

| 参数 | 作用 |
|---|---|
| `GRAVITY` / `JUMP_V` | 重力和起跳力度。调大 `JUMP_V` 的绝对值 = 飞得更高 |
| `PIPE_GAP` | 管道开口高度，**调大就是变简单** |
| `PIPE_SPACING` | 管道间距，调大 = 节奏更松 |
| `PIPE_SPEED` | 游戏速度 |
| `HIT_RX` / `HIT_RY` | 碰撞椭圆半径。调小 = 判定更宽容 |
| `BIRD_W` / `BIRD_H` | 果叽显示尺寸 |
| `TILT` | 关掉的话果叽就永远水平，不会跟着速度倾斜 |
| `KEEP_CAPTION` | 见下 |

## 关于 GIF 素材，有两件事得说明

**一、顶部的「我也要！我也要！」文字被裁掉了。**

原 GIF 是 240×240，文字占了上面 53 个像素。游戏里果叽只有 74 像素宽，
那行字缩下去会糊成一坨，所以 `GifSprite` 会自动找到文字和角色之间的空行、
只留本体，再按四帧并集裁掉透明留白 —— 最终得到 230×180 的紧凑素材。

裁剪位置不是写死的常数，而是扫描「上半部分第一段长度 ≥3 的空行带」，
换成别的表情包 GIF 也不会误裁。

想要原汁原味（连文字一起显示）就把 `Config.KEEP_CAPTION` 改成 `true`，
或者直接传参：

```bash
java -cp out guoji.FlappyGuoJiApp "路径/别的.gif"
```

**二、透明边缘做了处理，否则会有一圈白毛刺。**

GIF 只有「透明 / 不透明」两种状态，被透明像素占位的 RGB 其实是白色的。
直接缩放做插值时，这些白色会被平均到边缘上，在深色背景里就是一圈白边。
`GifSprite.bleedEdges()` 先把不透明像素的颜色向外扩散 6 层填满透明区
（alpha 保持 0 不变），再去缩放，边缘就干净了。

用 `tools.DumpFrames` 可以把各帧导出成图肉眼核对：

```bash
java -Djava.awt.headless=true -cp out tools.DumpFrames
# 产物在 out/dump/，其中 ingame_scale_x4.png 是放大 4 倍的边缘检查图
```

## 动画是怎么循环的

没用 `ImageIcon`（它虽然能自动播动画，但没法控制进度：游戏结束想定格就做不到）。
`GifSprite` 自己解码所有帧，用

```java
frameIndex = (elapsedMs % loopMs) → 按帧延时定位
```

做「时间 → 帧号」的直接映射。好处是掉帧不会让动画变慢，
`% loopMs` 天然就是无限循环，不会因为边界判断写错而停住。

本 GIF 是 4 帧 × 60ms = 240ms 一轮，即每秒约 4.2 轮，
和微信里看到的速度一致。游戏里有两个时钟：`clockMs` 驱动果叽动画
（撞上后冻结，看着像「僵住」），`worldMs` 驱动地面和云
（多走坠落那一段，画面才不会像卡死）。

## 离线验证

`tools.HeadlessRender` 不打开窗口，跑一遍逻辑断言并导出截图：

```bash
java -Dfile.encoding=UTF-8 -Djava.awt.headless=true -cp out tools.HeadlessRender
```

它会检查 GIF 是否严格按 240ms 无限循环（含 10 分钟长时间轴回绕）、
物理与碰撞、撞顶不死、死亡后动画冻结等共 17 项，并把
`screen_ready.png` / `screen_gameover.png` / `playthrough.png` / `gameplay.gif`
导出到 `out/verify/`。

里面还带一个自动驾驶 —— 它能源源不断拿分，就说明管道间距、
开口尺寸和碰撞判定都还在人类可玩的范围内。

> 注意：自动驾驶的瞄准点是「开口中心 + 32」而不是开口中心本身。
> 因为每次拍翅膀会上升 `JUMP_V² / (2·GRAVITY) ≈ 68` 像素，
> 瞄着中心拍会让顶点冲到开口上沿之上直接撞管。这是个挺容易踩的坑。

## 已知限制

- 没有音效（不想塞来源不明的音频素材）。
- 最高分存在工作目录下的 `.flappy-guoji-best`。
- 窗口最小 240×400，再小管道会挤在一起。
