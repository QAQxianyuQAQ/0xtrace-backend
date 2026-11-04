/*! tq-guide.js — 参赛者做题入口引导（由 /entry/ 网关注入到平台前端） */
(function () {
  if (window.__tqGuideMounted) return;
  window.__tqGuideMounted = true;

  var FALLBACK = [
    { code: 'HC-01', title: '人机验证' }, { code: 'SUDOKU', title: '异形数独拼图' },
    { code: 'SNAKE', title: '贪吃蛇（原型链）' }, { code: 'MISSING', title: '消失的方块' },
    { code: 'MORSE', title: '摩斯电码与 MD5' }, { code: 'SRCFLAG', title: '查看源码' }
  ];
  var list = FALLBACK.slice();
  var currentCode = null;

  function detectCode() {
    var h = (location.pathname + location.search + location.hash).toUpperCase();
    var hit = null;
    list.forEach(function (c) {
      if (!c.code) return;
      var code = String(c.code).toUpperCase();
      if (new RegExp('(^|[^A-Z0-9])' + code + '([^A-Z0-9]|$)').test(h)) hit = c.code;
    });
    return hit;
  }

  var CSS = ''
    + '#tqg-btn{position:fixed;right:22px;bottom:22px;z-index:2147483000;display:flex;align-items:center;gap:8px;'
    + 'padding:11px 16px;border-radius:999px;border:1px solid rgba(120,190,255,.45);background:#101820;color:#cfe7ff;'
    + 'font:600 14.5px/1 system-ui,-apple-system,"Microsoft YaHei",sans-serif;cursor:pointer;box-shadow:0 8px 26px rgba(0,0,0,.45);'
    + 'transition:transform .15s ease,background .15s ease}'
    + '#tqg-btn:hover{transform:translateY(-2px);background:#16212c}'
    + '#tqg-btn i{width:8px;height:8px;border-radius:50%;background:#3fb950;box-shadow:0 0 0 0 rgba(63,185,80,.6);animation:tqg-p 2s infinite}'
    + '@keyframes tqg-p{70%{box-shadow:0 0 0 9px rgba(63,185,80,0)}100%{box-shadow:0 0 0 0 rgba(63,185,80,0)}}'
    + '#tqg-mask{position:fixed;inset:0;z-index:2147483001;background:rgba(3,6,10,.62);backdrop-filter:blur(2px);display:none}'
    + '#tqg-mask.on{display:block}'
    + '#tqg-panel{position:fixed;z-index:2147483002;right:22px;bottom:22px;width:min(560px,calc(100vw - 32px));'
    + 'max-height:min(78vh,760px);overflow:auto;background:#0f1319;border:1px solid #263039;border-radius:16px;'
    + 'box-shadow:0 24px 70px rgba(0,0,0,.6);color:#e6edf3;padding:20px 22px 18px;'
    + 'font:14px/1.75 system-ui,-apple-system,"Microsoft YaHei",sans-serif;display:none}'
    + '#tqg-panel.on{display:block}'
    + '#tqg-panel h2{font-size:17px;margin:0 26px 4px 0;font-weight:700}'
    + '#tqg-panel h3{font-size:13px;margin:16px 0 6px;color:#8fa3b8;font-weight:600;letter-spacing:.02em}'
    + '#tqg-panel p{margin:6px 0;color:#b9c6d3}'
    + '#tqg-panel code{background:#1a2129;border:1px solid #263039;border-radius:5px;padding:1px 6px;font-size:12.5px;color:#9fd0ff}'
    + '#tqg-x{position:absolute;right:12px;top:10px;border:0;background:transparent;color:#7d8b99;font-size:20px;cursor:pointer;line-height:1}'
    + '.tqg-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 10px;border:1px solid #1e262e;'
    + 'border-radius:10px;margin-top:7px;background:#11161c}'
    + '.tqg-row b{font-weight:600;font-size:13.5px;color:#e6edf3}'
    + '.tqg-row code{font-size:11.5px}'
    + '.tqg-go{flex:0 0 auto;padding:6px 13px;border-radius:8px;background:#1f6feb;color:#fff;text-decoration:none;'
    + 'font:600 13px/1.5 system-ui,"Microsoft YaHei",sans-serif;white-space:nowrap}'
    + '.tqg-go:hover{background:#2b7ff5}'
    + '.tqg-now{background:linear-gradient(180deg,#12331f,#0e2417);border:1px solid #2ea043;border-radius:12px;padding:12px 14px;margin:12px 0}'
    + '.tqg-now .tqg-go{display:inline-block;margin-top:8px;padding:8px 18px;font-size:14px}'
    + '.tqg-note{border-left:2px solid #2f81f7;padding-left:10px;margin:10px 0;color:#98a8b8;font-size:13px}'
    + '.tqg-bad{color:#f0883e}';

  function mk(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

  function render() {
    var code = detectCode();
    var rows = list.map(function (c) {
      return '<div class="tqg-row"><span><b>' + (c.title || c.code) + '</b> <code>' + c.code + '</code></span>'
        + '<a class="tqg-go" href="/entry/' + encodeURIComponent(c.code) + '/" target="_blank" rel="noopener">进入题目</a></div>';
    }).join('');

    var nowBlock = '';
    if (code) {
      var t = (list.filter(function (c) { return c.code === code; })[0] || {}).title || code;
      nowBlock = '<div class="tqg-now"><div style="font-size:13px;color:#7ee787">你正在看：<b>' + t + '</b> <code>' + code + '</code></div>'
        + '<a class="tqg-go" href="/entry/' + encodeURIComponent(code) + '/" target="_blank" rel="noopener">开始做这道题 →</a></div>';
    }

    return '<button id="tqg-x" title="关闭">×</button>'
      + '<h2>怎么开始做题？</h2>'
      + '<p>平台的「连接容器」是<b>裸 socket 通道</b>，只收发原始字节、不会渲染网页，本题是 Web 题，请用下面的入口打开：</p>'
      + nowBlock
      + '<h3>点「进入题目」即可，不用自己填地址</h3>'
      + rows
      + '<h3>为什么不能用「连接容器」面板？</h3>'
      + '<div class="tqg-note">那个面板是把你的输入原样发进容器、把容器的原始响应原样显示出来，它不会执行题目页面里的 HTML/JS。'
      + 'Web 题需要浏览器真正访问容器，所以走这里的入口；入口会自动为你本队启动容器，每支队伍各有一个独立容器、独立 flag。</div>'
      + '<h3>小提示</h3>'
      + '<p>· 一队同时只能运行一个容器，切换题目时旧容器会自动销毁重开，只影响你自己的队伍。<br>'
      + '· 页面提示「容器启动中」时等 10–40 秒，会自动进入题目。<br>'
      + '· 拿到 <code>SACC{...}</code> 后回平台这道题的提交框提交即可。</p>';
  }

  function mount() {
    if (!document.body) return;
    var st = document.createElement('style'); st.textContent = CSS; document.head.appendChild(st);

    var btn = mk('button', '', '<i></i>怎么做题？点这里');
    btn.id = 'tqg-btn'; btn.type = 'button';
    var mask = mk('div', ''); mask.id = 'tqg-mask';
    var panel = mk('div', ''); panel.id = 'tqg-panel';

    function open() { panel.innerHTML = render(); panel.classList.add('on'); mask.classList.add('on'); }
    function close() { panel.classList.remove('on'); mask.classList.remove('on'); }

    btn.addEventListener('click', function () { panel.classList.contains('on') ? close() : open(); });
    mask.addEventListener('click', close);
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });
    panel.addEventListener('click', function (e) { if (e.target && e.target.id === 'tqg-x') close(); });

    document.body.appendChild(btn); document.body.appendChild(mask); document.body.appendChild(panel);

    // 直达/截图：?tqguide=1 自动展开；否则每个标签页首次进入自动展开一次，保证参赛者能看见
    if (/[?&]tqguide=1/.test(location.search)) open();
    else {
      try {
        if (!sessionStorage.getItem('tqguide_seen')) {
          sessionStorage.setItem('tqguide_seen', '1');
          setTimeout(open, 900);
        }
      } catch (e) {}
    }

    fetch('/api/v1/challenges', { credentials: 'same-origin' }).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        if (!d || !d.items) return;
        var got = d.items.filter(function (x) { return x.containerEnabled; })
          .map(function (x) { return { code: x.code, title: x.title, cat: x.category, pts: x.points }; });
        if (got.length) { got.sort(function (a, b) { return (a.pts || 0) - (b.pts || 0); }); list = got; currentCode = detectCode(); if (panel.classList.contains('on')) open(); }
      }).catch(function () {});
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
})();
