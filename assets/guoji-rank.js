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
 *   GQ.rank.board(元素)                   把排行榜渲染到指定容器
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

  var K_NAME = 'guoji_player_name';
  var K_UID  = 'guoji_player_uid';
  var K_SKIP = 'guoji_gate_skip';

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

  function send(payload) {
    var url = API + '/api/report';
    var body = JSON.stringify(payload);
    try {
      if (navigator.sendBeacon) {
        /* 用 text/plain 而不是 application/json：前者属于「简单请求」不触发 CORS 预检，
           而 sendBeacon 处理不了预检。服务端那边也是按文本读再 JSON.parse。 */
        var blob = new Blob([body], { type: 'text/plain;charset=UTF-8' });
        if (navigator.sendBeacon(url, blob)) return;
      }
    } catch (e) { /* 落到下面的 fetch */ }
    try {
      fetch(url, { method: 'POST', body: body, keepalive: true, mode: 'cors' });
    } catch (e) { /* 完全失败就算了，不能影响游戏本身 */ }
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
    send(payload);
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
    injectCSS();

    host.className = 'gq-board';
    host.innerHTML =
      '<div class="gq-bh">' +
        '<span class="gq-bt">果叽排行榜</span>' +
        '<span class="gq-bs">按最高分排</span>' +
      '</div>' +
      '<div class="gq-tabs" id="gqTabs"></div>' +
      '<div class="gq-bd" id="gqBody"></div>';

    var tabsEl = host.querySelector('#gqTabs');
    var bodyEl = host.querySelector('#gqBody');

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

    function paint() {
      if (!data) return;
      if (cur === 'all') {
        renderSummary(bodyEl, data);
      } else {
        var g = data[cur];
        renderRows(bodyEl, g ? g.top : null, playerName());
      }
    }

    function pick(k) {
      cur = k;
      Object.keys(tabMap).forEach(function (x) {
        tabMap[x].className = 'gq-tab' + (x === k ? ' on' : '');
      });
      paint();
    }

    setBusy('加载中…');

    fetch(API + '/api/board', { mode: 'cors' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        if (!j || !j.ok || !j.games) throw new Error('bad payload');
        data = j.games;
        /* 服务端可能还是旧版（GAMES 里没有某个新游戏）：这种情况把那个标签藏掉，
           否则点进去是一片空白，看着像坏了 */
        GAME_ORDER.forEach(function (k) {
          if (!data[k] && tabMap[k]) tabMap[k].style.display = 'none';
        });
        paint();
      })
      .catch(function () {
        setBusy('排行榜暂时连不上，过会儿再刷新看看');
      });
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
