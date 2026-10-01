/**
 * 果叽小游戏 · 昵称门 + 在线排行榜（前端共享脚本）
 *
 * 8 个页面共用这一份：index.html 和 7 个游戏页。
 * 用法：
 *   <script defer src="assets/guoji-rank.js"></script>
 *
 * 页面里可调用的东西都挂在 window.GQ.rank 上：
 *   GQ.rank.begin()                      开局：计时归零并起跑
 *   GQ.rank.hold() / GQ.rank.free()      暂停 / 继续计时
 *   GQ.rank.finish({game,score,reason})  结算：停表 + 上报（自动防重复）
 *                                        上报成功弹一枚回执，并让榜单立刻重拉
 *   GQ.rank.board(元素)                   把排行榜渲染到指定容器
 *                                        挂上后按 REFRESH_MS 自动刷新，不需要刷页面
 *
 * ★ 安全规则（改这个文件时请守住）：任何来自用户的内容（昵称）
 *   一律用 textContent 写入，绝不用 innerHTML。硬编码的静态骨架才允许用 innerHTML 拼。
 */
(function () {
  'use strict';

  /* ==================== 0. 配置 ==================== */

  /* ★ 后端地址。当前跑在 Cloudflare Pages 上，**刻意不用 workers.dev**：
     `*.workers.dev` 在国内被 GFW 定向封了（TLS 握手时 SNI 里一带这个后缀就被重置），
     而 `*.pages.dev` 实测国内可直连，所以后端整体搬到了 Pages。
     要换地址只改这一行，不要带结尾的斜杠。
     地址不可达时脚本不报错，只是不上报、榜单显示「还没接上」。 */
  var API = 'https://guoji-rank.pages.dev';

  var CONFIGURED = API.indexOf('http') === 0;

  /* ★ 榜单自动刷新间隔（毫秒）。榜单区一旦挂上就按这个节奏重拉，
     页面切到后台自动停，切回来立刻补一次。8 秒是「够实时」和「别打爆后端」的折中。 */
  var REFRESH_MS = 8000;

  /* ★ 请求去缓存。后端给 /api/board 发的是 `Cache-Control: public, max-age=30`，
     浏览器和 CDN 都会喂给你 30 秒内的旧数据 —— 这是「看着不更新」的一大来源。
     用「5 秒一换的时间桶」当查询参数：同一个 5 秒窗口内所有人共用一份缓存，
     既保证拿到新鲜数据，又不会让请求量翻倍。 */
  var TICK_MS = 5000;

  function stamp() {
    return '?t=' + Math.floor(Date.now() / TICK_MS);
  }

  var K_NAME = 'guoji_player_name';
  var K_UID  = 'guoji_player_uid';
  var K_SKIP = 'guoji_gate_skip';
  var K_BEST = 'guoji_best_';   /* + 游戏 id，记本机玩出来的最好成绩 */

  var GAME_LABEL = {
    dadishu:    '打地鼠',
    ganfan:     '干饭',
    tiaoyitiao: '跳一跳',
    feifei:     '飞飞',
    xiaoxiaole: '消消乐',
    jiawawaji:  '夹娃娃机',
    kuanggong:  '矿工',
  };
  /* 标签顺序：沿用原有 5 个游戏的顺序，新游戏追加在后面。
     ★ 新增游戏时，这里、GAME_LABEL、以及服务端 Worker 的 GAMES 三处要一起加 */
  var GAME_ORDER = ['dadishu', 'ganfan', 'tiaoyitiao', 'feifei', 'xiaoxiaole', 'jiawawaji', 'kuanggong'];

  /* 与服务端保持一致：允许文字 / 数字 / 表情 / 空格 / 下划线 / 连字符 / 间隔点 / 句点 */
  var NAME_SHAPE = /^[\p{L}\p{N}\p{Extended_Pictographic} _\-·.]+$/u;
  var NAME_MAX = 12;

  var DICE = [
    '果叽', '糖蛋', '小果叽', '果叽大王', '干饭果叽', '爱发呆的果叽',
    '幸运果叽', '果叽探险家', '摸鱼果叽', '果叽护卫', '一只果叽', '果叽小队长',
  ];

  /* ==================== 1. 基础工具 ==================== */

  var mem = {};

  function store(k, v) {
    try {
      if (v === undefined) return window.localStorage.getItem(k);
      window.localStorage.setItem(k, String(v));
      return null;
    } catch (e) {
      if (v === undefined) return (k in mem) ? mem[k] : null;
      mem[k] = String(v);
      return null;
    }
  }

  function sess(k, v) {
    try {
      if (v === undefined) return window.sessionStorage.getItem(k);
      window.sessionStorage.setItem(k, String(v));
    } catch (e) { /* 无痕模式可能禁用，忽略 */ }
    return null;
  }

  function playerName() { return store(K_NAME) || ''; }
  function hasName() { return !!playerName(); }

  function deviceId() {
    var u = store(K_UID);
    if (!u) {
      u = 'u' + Date.now().toString(36) + Math.random().toString(36).slice(2, 9);
      store(K_UID, u);
    }
    return u;
  }

  /** 返回错误文案，空字符串代表通过 */
  function checkName(s) {
    var v = String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
    if (!v) return '名字不能为空';
    if ([...v].length > NAME_MAX) return '最多 ' + NAME_MAX + ' 个字';
    if (!NAME_SHAPE.test(v)) return '只能用文字、数字或表情，不能有符号';
    return '';
  }

  /* ==================== 2. 统一计时器 ==================== */

  /* 7 个游戏各写各的计时会失真（比如干饭的 G.t 在暂停时仍然在涨），
     所以统一在这里计：谁开局调 begin，谁暂停调 hold，谁恢复调 free。
     hold/free 用计数配对，游戏自己的暂停和「切走标签页」可以叠加，互不干扰。 */
  var T = { acc: 0, mark: 0, on: false, hold: 0 };

  function tick() {
    return (window.performance && window.performance.now) ? window.performance.now() : Date.now();
  }

  function tBegin() {
    T.acc = 0; T.mark = tick(); T.on = true; T.hold = 0;
    if (document.hidden) tHold();
  }

  function tHold() {
    if (T.hold++ === 0 && T.on) { T.acc += tick() - T.mark; T.on = false; }
  }

  function tFree() {
    if (T.hold > 0) {
      T.hold--;
      if (T.hold === 0) { T.mark = tick(); T.on = true; }
    }
  }

  function tSec() {
    var ms = T.acc + (T.on ? tick() - T.mark : 0);
    return Math.round(ms / 1000);
  }

  /* 切走标签页自动停表 —— 不然挂着页面去吃午饭，这一局就是几千秒 */
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) tHold(); else tFree();
  });

  /* ==================== 3. 上报 ==================== */

  var sent = false;

  /** 返回是否「已经发出去了」。发不出去时不再假装成功，交给 finish 提示。 */
  function send(payload) {
    var url = API + '/api/report';
    var body = JSON.stringify(payload);
    try {
      if (navigator.sendBeacon) {
        /* 用 text/plain 而不是 application/json：前者属于「简单请求」不触发 CORS 预检，
           而 sendBeacon 处理不了预检。服务端那边也是按文本读再 JSON.parse。 */
        var blob = new Blob([body], { type: 'text/plain;charset=UTF-8' });
        if (navigator.sendBeacon(url, blob)) return true;
      }
    } catch (e) { /* 落到下面的 fetch */ }
    try {
      fetch(url, { method: 'POST', body: body, keepalive: true, mode: 'cors' });
      return true;
    } catch (e) {
      /* 完全失败就算了，不能影响游戏本身 */
      return false;
    }
  }

  /** 开局。同时把「这一局是否已上报」的标志清掉 */
  function begin() {
    sent = false;
    tBegin();
  }

  /**
   * 结算并上报。自动防重复：同一个 begin~finish 周期内只会送出一次。
   * o = { game, score, reason, lv }
   */
  function finish(o) {
    if (sent) return;
    sent = true;
    tHold();

    o = o || {};
    var name = playerName();
    var payload = {
      game: o.game,
      name: name,
      score: Math.max(0, Math.round(Number(o.score) || 0)),
      seconds: tSec(),
      uid: deviceId(),
      reason: o.reason || '',
    };
    if (o.lv) payload.lv = Math.round(Number(o.lv));

    if (!CONFIGURED || !name || !GAME_LABEL[o.game]) return;

    if (!send(payload)) {
      /* 一个字节都没送出去（离线 / 被拦）。不假装成功，但也不能干扰结算画面。 */
      toast('这局没能上榜：网络好像断了');
      return;
    }

    /* 送出去了：给自己一枚回执，并让已经渲染出来的榜单立刻重拉一次。
       ★ 这一步是「实时感」的关键 —— 玩家不用回首页、不用刷页面，当场就知道自己上榜了。 */
    var isNew = payload.score > bestOf(o.game);
    if (isNew) store(K_BEST + o.game, payload.score);
    lastRun = { game: o.game, score: payload.score, at: Date.now() };
    toast('已上榜 · 本次 ' + payload.score + ' 分' + (isNew ? '（新纪录）' : ''));
    notifyBoards();
  }

  /* ==================== 3.5 回执 · 榜单订阅 ==================== */

  /* 已经挂载出来的榜单，每个元素是一个「立刻重拉」函数。
     目前一个页面只挂一块，写成列表是为了以后首页之外也能挂。 */
  var liveBoards = [];
  var lastRun = null;   /* 本页最近一次上报：{game, score, at} */

  /* 传 true = 强制重绘：自己刚上报完，哪怕服务端数据还没变，
     「你的最好成绩」那一行也得立刻跟上，否则看着像没写进去。 */
  function notifyBoards() {
    for (var i = 0; i < liveBoards.length; i++) {
      try { liveBoards[i](true); } catch (e) { /* 单个榜单出错不影响其他 */ }
    }
  }

  function bestOf(g) { return Number(store(K_BEST + g)) || 0; }

  /** 轻提示：玩完立刻知道「上榜了 / 多少分 / 是不是新纪录」。动态内容走 textContent。 */
  function toast(msg) {
    injectCSS();
    var old = document.getElementById('gqToast');
    if (old && old.parentNode) old.parentNode.removeChild(old);

    var t = document.createElement('div');
    t.id = 'gqToast';
    t.className = 'gq-toast';
    t.textContent = msg;
    (document.body || document.documentElement).appendChild(t);
    setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 4200);
  }

  /* ==================== 4. 样式 ==================== */

  var CSS = [
    '.gq-gate{position:fixed;inset:0;z-index:2147483000;overflow:auto;padding:20px;',
    'display:flex;align-items:center;justify-content:center;',
    'background:linear-gradient(170deg,rgba(233,243,255,.97),rgba(246,240,255,.97) 46%,rgba(253,241,245,.98));',
    'font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei","Segoe UI",Roboto,sans-serif}',
    '.gq-card{width:100%;max-width:330px;background:#fff;border:1px solid #f3e3ea;border-radius:20px;',
    'padding:22px 20px 16px;text-align:center;box-sizing:border-box;box-shadow:0 8px 22px rgba(51,51,51,.09)}',
    '.gq-face{width:74px;height:74px;object-fit:contain;display:block;margin:0 auto 8px}',
    '.gq-title{font-size:18px;font-weight:700;color:#333;margin:0 0 6px}',
    '.gq-desc{font-size:13px;color:#8b909d;margin:0 0 10px;line-height:1.5}',
    '.gq-dice{background:none;border:0;color:#e06a92;font-size:12px;text-decoration:underline;',
    'cursor:pointer;font-family:inherit;padding:0;margin:0 0 12px}',
    '.gq-input{width:100%;box-sizing:border-box;height:46px;border:1.5px solid #f0dfe6;border-radius:13px;',
    'padding:0 13px;font-size:16px;color:#333;background:#fdf7f9;outline:none;font-family:inherit}',
    '.gq-input:focus{border-color:#e06a92;background:#fff}',
    '.gq-err{min-height:17px;font-size:12px;color:#d24b4b;text-align:left;padding:5px 3px 0}',
    '.gq-btn{display:block;width:100%;height:46px;border-radius:14px;font-size:16px;font-weight:700;',
    'font-family:inherit;cursor:pointer;margin-bottom:8px;border:0;box-sizing:border-box}',
    '.gq-btn-p{background:linear-gradient(180deg,#F585AC,#E96692);color:#fff;',
    'box-shadow:0 4px 12px rgba(224,106,146,.32)}',
    '.gq-btn-g{background:#fff;color:#8b909d;border:1.5px solid #f0dfe6}',
    '.gq-tip{font-size:11px;color:#b0a79c;margin:6px 0 0;line-height:1.5}',

    '.gq-board{background:#fff;border:1px solid #f3e3ea;border-radius:20px;padding:16px 14px 8px;',
    'box-sizing:border-box;color:#333;text-align:left;box-shadow:0 6px 18px rgba(51,51,51,.07);',
    'font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei","Segoe UI",Roboto,sans-serif}',
    '.gq-bh{display:flex;align-items:baseline;gap:8px;margin:0 2px 10px}',
    '.gq-bt{font-size:16px;font-weight:700}',
    '.gq-bs{font-size:12px;color:#b0a79c}',
    '.gq-tabs{display:flex;gap:6px;overflow-x:auto;padding:0 2px 8px;margin-bottom:4px;',
    '-webkit-overflow-scrolling:touch;scrollbar-width:none}',
    '.gq-tabs::-webkit-scrollbar{display:none}',
    '.gq-tab{flex:0 0 auto;padding:6px 13px;border-radius:999px;border:1.5px solid #f0dfe6;',
    'background:#fdf7f9;color:#8b909d;font-size:13px;cursor:pointer;font-family:inherit;white-space:nowrap}',
    '.gq-tab.on{background:linear-gradient(180deg,#F585AC,#E96692);border-color:#E96692;color:#fff;font-weight:700}',
    '.gq-row{display:flex;align-items:center;gap:9px;padding:9px 2px;border-bottom:1px solid #f7eef2;font-size:14px}',
    '.gq-row:last-child{border-bottom:0}',
    '.gq-rk{flex:0 0 22px;text-align:center;font-size:13px;font-weight:700;color:#c3b3ba}',
    '.gq-rk1{color:#e8a33d}.gq-rk2{color:#9aa3ad}.gq-rk3{color:#c08a5e}',
    '.gq-nm{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
    '.gq-me{font-size:11px;color:#e06a92;margin-left:5px}',
    '.gq-sc{flex:0 0 auto;font-weight:700;color:#e06a92}',
    '.gq-gm{flex:0 0 auto;font-size:12px;color:#b0a79c;min-width:46px;text-align:right}',
    '.gq-empty{text-align:center;color:#b0a79c;font-size:13px;padding:22px 0;margin:0;line-height:1.7}',

    '.gq-sum{padding:2px 0 6px}',
    '.gq-si{display:flex;align-items:center;gap:8px;padding:9px 2px;border-bottom:1px solid #f7eef2;font-size:14px}',
    '.gq-si:last-child{border-bottom:0}',
    '.gq-sg{flex:0 0 58px;font-size:13px;color:#8b909d}',
    '.gq-sn{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:700}',
    '.gq-ss{flex:0 0 auto;font-weight:700;color:#e06a92}',
    '.gq-sm{flex:0 0 auto;font-size:12px;color:#b0a79c;min-width:62px;text-align:right}',

    /* 玩完后的回执气泡 */
    '.gq-toast{position:fixed;left:0;right:0;top:14px;margin:0 auto;width:-moz-max-content;width:max-content;',
    'max-width:88vw;z-index:2147482000;background:rgba(51,51,51,.9);color:#fff;font-size:13px;',
    'line-height:1.5;padding:9px 16px;border-radius:999px;text-align:center;',
    'font-family:-apple-system,BlinkMacSystemFont,"PingFang SC","Microsoft YaHei","Segoe UI",Roboto,sans-serif;',
    'animation:gqIn .18s ease-out}',
    '@keyframes gqIn{from{opacity:0;transform:translateY(-6px)}to{opacity:1;transform:translateY(0)}}',

    /* 手动刷新 */
    '.gq-rl{margin-left:auto;background:none;border:0;padding:0;color:#e06a92;font-size:12px;',
    'text-decoration:underline;cursor:pointer;font-family:inherit}',

    /* 榜尾「你的最好成绩」：没进前 10 也能看到自己 */
    '.gq-mine{margin:0;padding:10px 2px 2px;border-top:1px dashed #f2e2e9;font-size:13px;color:#8b909d}',
    '.gq-mine b{color:#e06a92;font-size:15px}',
  ].join('');

  function injectCSS() {
    if (document.getElementById('gqStyle')) return;
    var s = document.createElement('style');
    s.id = 'gqStyle';
    s.textContent = CSS;
    (document.head || document.documentElement).appendChild(s);
  }

  /* ==================== 5. 昵称门 ==================== */

  function closeGate() {
    var g = document.getElementById('gqGate');
    if (g && g.parentNode) g.parentNode.removeChild(g);
  }

  /**
   * 弹出昵称门。
   * opts.soft = true 时多给一个「先随便看看」（只在首页用）；
   * 游戏页走 strict（必填），否则榜上没名字。
   */
  function gate(opts) {
    opts = opts || {};
    if (document.getElementById('gqGate')) return;
    injectCSS();

    var wrap = document.createElement('div');
    wrap.id = 'gqGate';
    wrap.className = 'gq-gate';

    /* 静态骨架，无任何变量插值，允许用 innerHTML；昵称等动态内容一律走 textContent */
    wrap.innerHTML =
      '<div class="gq-card">' +
        '<img class="gq-face" src="assets/shy.png" alt="">' +
        '<h3 class="gq-title">先起个名字吧</h3>' +
        '<p class="gq-desc">这个名字会出现在排行榜上，<br>别人也看得到</p>' +
        '<button class="gq-dice" id="gqDice" type="button">帮我随便起一个</button>' +
        '<input class="gq-input" id="gqInput" type="text" maxlength="12" autocomplete="off" ' +
          'placeholder="1-12 个字" enterkeyhint="done">' +
        '<div class="gq-err" id="gqErr"></div>' +
        '<button class="gq-btn gq-btn-p" id="gqOk" type="button">就叫这个，开始玩</button>' +
        (opts.soft ? '<button class="gq-btn gq-btn-g" id="gqSkip" type="button">先随便看看</button>' : '') +
        '<p class="gq-tip">只用于排行榜显示，不收集个人信息</p>' +
      '</div>';

    (document.body || document.documentElement).appendChild(wrap);

    var input = wrap.querySelector('#gqInput');
    var errEl = wrap.querySelector('#gqErr');
    var okBtn = wrap.querySelector('#gqOk');
    var diceBtn = wrap.querySelector('#gqDice');
    var skipBtn = wrap.querySelector('#gqSkip');

    var remembered = playerName();
    if (remembered) input.value = remembered;

    function submit() {
      var v = input.value.replace(/\s+/g, ' ').trim();
      var err = checkName(v);
      if (err) {
        errEl.textContent = err;              /* ← 动态内容，textContent */
        input.focus();
        return;
      }
      store(K_NAME, v);
      closeGate();
    }

    okBtn.addEventListener('click', submit);

    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') { e.preventDefault(); submit(); }
    });
    input.addEventListener('input', function () { errEl.textContent = ''; });

    diceBtn.addEventListener('click', function () {
      var base = DICE[Math.floor(Math.random() * DICE.length)];
      var n = Math.floor(Math.random() * 900) + 100;
      input.value = base + n;
      errEl.textContent = '';
      input.focus();
    });

    if (skipBtn) {
      skipBtn.addEventListener('click', function () {
        sess(K_SKIP, '1');
        closeGate();
      });
    }

    /* 手机上别让键盘一弹就把按钮顶没了 */
    setTimeout(function () { try { input.focus(); } catch (e) {} }, 60);
  }

  /* ==================== 6. 排行榜 ==================== */

  function el(tag, cls, text) {
    var d = document.createElement(tag);
    if (cls) d.className = cls;
    if (text !== undefined && text !== null) d.textContent = String(text);   /* ← XSS 防线 */
    return d;
  }

  function renderRows(host, list, me) {
    host.innerHTML = '';
    if (!list || !list.length) {
      host.appendChild(el('p', 'gq-empty', '还没有人上榜，你可以是第一个'));
      return;
    }
    for (var i = 0; i < list.length; i++) {
      var it = list[i];
      var row = el('div', 'gq-row');
      var rk = el('span', 'gq-rk' + (i < 3 ? ' gq-rk' + (i + 1) : ''), i + 1);
      var nm = el('span', 'gq-nm', it.name);
      if (me && it.name === me) nm.appendChild(el('span', 'gq-me', '（我）'));
      row.appendChild(rk);
      row.appendChild(nm);
      row.appendChild(el('span', 'gq-sc', it.best));
      row.appendChild(el('span', 'gq-gm', it.runs + ' 局'));
      host.appendChild(row);
    }
  }

  function renderSummary(host, games) {
    host.innerHTML = '';
    var box = el('div', 'gq-sum');
    var any = false;

    for (var i = 0; i < GAME_ORDER.length; i++) {
      var id = GAME_ORDER[i];
      var g = games[id];
      if (!g || !g.top || !g.top.length) continue;
      any = true;

      var row = el('div', 'gq-si');
      row.appendChild(el('span', 'gq-sg', GAME_LABEL[id] || id));
      row.appendChild(el('span', 'gq-sn', g.top[0].name));
      row.appendChild(el('span', 'gq-ss', g.top[0].best));
      row.appendChild(el('span', 'gq-sm', (g.players || 0) + ' 人 · ' + (g.runs || 0) + ' 局'));
      box.appendChild(row);
    }

    if (!any) {
      host.appendChild(el('p', 'gq-empty', '还没有人上榜，你可以是第一个'));
      return;
    }
    host.appendChild(box);
  }

  /**
   * 把排行榜渲染到指定容器。
   * 容器里会被整体重建，原来有什么都会被清掉。
   */
  function board(host) {
    if (!host || !CONFIGURED) return;   /* 未接服务端时整块不显示，页面保持原样 */
    if (host.getAttribute('data-gq') === '1') return;   /* 重复挂载保护 */
    host.setAttribute('data-gq', '1');
    injectCSS();

    host.className = 'gq-board';
    host.innerHTML =
      '<div class="gq-bh">' +
        '<span class="gq-bt">果叽排行榜</span>' +
        '<span class="gq-bs">按最高分排</span>' +
        '<button class="gq-rl" id="gqReload" type="button">刷新</button>' +
      '</div>' +
      '<div class="gq-tabs" id="gqTabs"></div>' +
      '<div class="gq-bd" id="gqBody"></div>';

    var tabsEl = host.querySelector('#gqTabs');
    var bodyEl = host.querySelector('#gqBody');
    var reloadBtn = host.querySelector('#gqReload');

    function setBusy(text) {
      bodyEl.innerHTML = '';
      bodyEl.appendChild(el('p', 'gq-empty', text));
    }

    /* 标签：总览 + 每个游戏 */
    var keys = ['all'].concat(GAME_ORDER);
    var tabMap = {};
    keys.forEach(function (k) {
      var t = el('button', 'gq-tab' + (k === 'all' ? ' on' : ''), k === 'all' ? '总览' : (GAME_LABEL[k] || k));
      t.type = 'button';
      t.addEventListener('click', function () { pick(k); });
      tabsEl.appendChild(t);
      tabMap[k] = t;
    });

    var data = null;
    var cur = 'all';
    var loading = false;
    var loadedOnce = false;
    var lastRaw = '';

    function paint() {
      if (!data) return;
      if (cur === 'all') {
        renderSummary(bodyEl, data);
      } else {
        var g = data[cur];
        var top = g ? g.top : null;
        renderRows(bodyEl, top, playerName());
        appendMine(cur, top);
      }
    }

    /** 榜尾补一行「你」。★ 这是「看不到自己 = 分数没更新上去」的解法：
        后端只给前 10，没进榜的人原本在页面上找不到任何自己的痕迹。 */
    function appendMine(game, top) {
      var me = playerName();
      var mine = me ? bestOf(game) : 0;
      if (!mine) return;

      var onBoard = false;
      for (var i = 0; i < (top ? top.length : 0); i++) {
        if (top[i].name === me) { onBoard = true; break; }
      }

      var row = el('p', 'gq-mine');
      row.appendChild(document.createTextNode('你的最好成绩 '));
      row.appendChild(el('b', null, mine));
      row.appendChild(document.createTextNode(onBoard ? ' 分 · 已在榜上' : ' 分 · 还没进前 10'));
      bodyEl.appendChild(row);
    }

    function pick(k) {
      cur = k;
      Object.keys(tabMap).forEach(function (x) {
        tabMap[x].className = 'gq-tab' + (x === k ? ' on' : '');
      });
      paint();
    }

    /**
     * 拉一次榜单。
     * silent = true 是后台自动刷新：不显示「加载中」，失败也不擦掉已经画出来的榜单，
     * 否则网络抖一下，本来能看的榜会突然变成一行报错。
     */
    function load(silent, force) {
      if (loading) return;
      loading = true;
      if (!silent) setBusy('加载中…');

      fetch(API + '/api/board' + stamp(), { mode: 'cors', cache: 'no-store' })
        .then(function (r) { return r.json(); })
        .then(function (j) {
          if (!j || !j.ok || !j.games) throw new Error('bad payload');

          var raw = JSON.stringify(j.games);
          data = j.games;

          /* 服务端可能还是旧版（GAMES 里没有某个新游戏）：这种情况把那个标签藏掉，
             否则点进去是一片空白，看着像坏了 */
          GAME_ORDER.forEach(function (k) {
            if (!data[k] && tabMap[k]) tabMap[k].style.display = 'none';
          });

          loadedOnce = true;
          /* 数据没变就不重绘：10 行以内虽不慢，但每 8 秒把 DOM 重建一次会闪。
             force 用于「刚上报完」这一次，必须重绘才能把本机最好成绩带上去。 */
          if (force || raw !== lastRaw) { lastRaw = raw; paint(); }
        })
        .catch(function () {
          if (!loadedOnce) setBusy('排行榜暂时连不上，过会儿再刷新看看');
        })
        .then(function () {
          loading = false;
          reloadBtn.textContent = '刷新';
        });
    }

    reloadBtn.addEventListener('click', function () {
      reloadBtn.textContent = '刷新中…';
      load(false);
    });

    load(false);

    /* ★ 自动刷新：页面可见时按 REFRESH_MS 轮询，切到后台就停（省电省流量），
       切回来立刻补一次 —— 玩家的动作顺序通常是「去玩一局 → 切回来」，这一下最有用。 */
    setInterval(function () { if (!document.hidden) load(true); }, REFRESH_MS);
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) load(true);
    });

    /* 本页上报成功后立刻重拉（silent + force，不闪「加载中」但要重绘） */
    liveBoards.push(function (force) { load(true, force); });
  }

  /* ==================== 7. 启动 ==================== */

  function isHomePage() {
    var p = location.pathname;
    return /\/$/.test(p) || /\/index\.html?$/i.test(p) || p === '';
  }

  function boot() {
    /* ★ 服务端还没接上时全程静默：不弹昵称门、不显示榜单，
       页面跟接入前完全一样。这样才能安全地先把代码推上线，
       等 Worker 部署好、把上面的 API 占位符换掉，功能自动生效。 */
    if (!CONFIGURED) return;
    if (hasName()) return;
    /* 首页允许「先随便看看」，并且本次会话内不再重复弹；
       游戏页必填 —— 没有名字就没法上榜。 */
    var home = isHomePage();
    if (home && sess(K_SKIP)) return;
    gate({ soft: home });
  }

  window.GQ = window.GQ || {};
  window.GQ.rank = {
    name: playerName,
    hasName: hasName,
    setName: function (v) {
      var e = checkName(v);
      if (!e) store(K_NAME, String(v).replace(/\s+/g, ' ').trim());
      return e;
    },
    gate: gate,
    begin: begin,
    hold: tHold,
    free: tFree,
    seconds: tSec,
    finish: finish,
    board: board,
    configured: function () { return CONFIGURED; },
    api: function () { return API; },
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
