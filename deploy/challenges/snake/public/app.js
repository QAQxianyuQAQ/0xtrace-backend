'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  const canvas = $('game');
  const ctx = canvas.getContext('2d');
  const size = 20;
  const cell = canvas.width / size;
  const colors = { mint: '#62e6ff', violet: '#9b8cff', amber: '#2ea9ff' };
  const vectors = { up: [0, -1], down: [0, 1], left: [-1, 0], right: [1, 0] };
  let state = null;
  let activeLevel = 1;
  let snake, food, direction, nextDirection, running = false, timer, palette = 'mint';
  let toastTimer, pollTimer, syncing = false, pendingScores = 0, scoreQueue = Promise.resolve(), resettingAfterDeath = false, flagAnnounced = false;
  let queuedTurn = false;

  async function api(path, body) {
    const options = { credentials: 'same-origin', cache: 'no-store' };
    if (body !== undefined) Object.assign(options, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const response = await fetch(path, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error ?? `HTTP ${response.status}`);
    return data;
  }

  function toast(message) {
    $('toast').textContent = message;
    $('toast').hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { $('toast').hidden = true; }, 4200);
  }

  function applyPalette(value) {
    palette = Object.hasOwn(colors, value) ? value : 'mint';
    document.documentElement.style.setProperty('--snake', colors[palette]);
    $('palette').value = palette;
    draw();
  }

  function renderState() {
    if (!state) return;
    const current = activeLevel === 1 ? state.stage1 : state.stage2;
    const score = current?.score ?? 0;
    $('score').textContent = String(score).padStart(5, '0');
    const target = state.targets[activeLevel];
    $('target-value').textContent = target.toLocaleString('en-US');
    const percent = Math.min(score / target * 100, 100);
    $('progress-fill').style.width = `${percent}%`;
    $('progress-percent').textContent = `${percent.toFixed(3)}%`;
    $('raw-score-label').textContent = activeLevel === 2 ? `本轮计数 ${current?.rawScore ?? 0} / 10` : '每一口，都算数。';
    $('level-two').disabled = !state.stage1.cleared;
    $('level-one-mark').textContent = state.stage1.cleared ? 'CLEARED' : 'READY';
    $('level-two-mark').textContent = state.stage2?.cleared ? 'CLEARED' : state.stage1.cleared ? 'READY' : 'LOCKED';
    for (const [id, level] of [['level-one', 1], ['level-two', 2]]) {
      $(id).classList.toggle('active', activeLevel === level);
      if (activeLevel === level) $(id).setAttribute('aria-current', 'step'); else $(id).removeAttribute('aria-current');
    }
    $('board-label').textContent = $('round-pill').textContent = `ROUND 0${activeLevel}`;
    $('brief-title').textContent = activeLevel === 1 ? '从第一口开始。' : '十分之后，又是零。';
    $('brief-text').textContent = activeLevel === 1
      ? '每吃到一个光点，积分 +1。达到 999 分即可开启下一关。撞墙或撞到自己会结束这局，本关分数归零。'
      : '目标依旧是 99,999。每次只能 +1，本轮计数到 10 就归零。旧版排行榜结算服务保留了下来，还新增了可同步的游戏偏好。';
    $('brief-note').textContent = activeLevel === 1 ? '耐心是一种解法。好奇心也是。' : '规则看起来滴水不漏。记录从哪里来？';
    $('palette-help').textContent = activeLevel === 2 ? '你的偏好会同步到本关计分服务。' : '选择你的游戏配色。';
    $('source-button').hidden = activeLevel !== 2;
    if (state.flag) {
      $('completion').hidden = false;
      $('flag-value').textContent = state.flag;
      pause();
      overlay('ALL CLEAR', '挑战完成。', '你找到了规则背后的规则。', '查看 Flag ↓');
      if (!flagAnnounced) {
        flagAnnounced = true;
        $('completion').scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
    } else if (activeLevel === 1 && state.stage1.cleared) {
      $('completion').hidden = true;
      flagAnnounced = false;
      pause();
      overlay('ROUND 01 CLEARED', '第一关，拿下。', '第二关已经解锁。', '进入第二关 ↗');
    } else {
      $('completion').hidden = true;
      flagAnnounced = false;
    }
  }

  function overlay(kicker, title, description, button) {
    $('overlay-kicker').textContent = kicker;
    $('overlay-title').textContent = title;
    $('overlay-description').textContent = description;
    $('play-button').textContent = button;
    $('game-overlay').hidden = false;
  }

  function resetBoard() {
    snake = [{ x: 9, y: 10 }, { x: 8, y: 10 }, { x: 7, y: 10 }, { x: 6, y: 10 }];
    food = { x: 13, y: 10 };
    direction = nextDirection = 'right';
    queuedTurn = false;
    draw();
  }

  function draw() {
    if (!snake) return;
    ctx.fillStyle = '#071522';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.strokeStyle = '#12314a';
    ctx.lineWidth = 0.7;
    for (let i = 0; i <= size; i++) {
      ctx.beginPath(); ctx.moveTo(i * cell, 0); ctx.lineTo(i * cell, canvas.height); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(0, i * cell); ctx.lineTo(canvas.width, i * cell); ctx.stroke();
    }
    ctx.fillStyle = '#ff6f91';
    ctx.shadowColor = '#ff6f9177'; ctx.shadowBlur = 15;
    ctx.beginPath(); ctx.roundRect(food.x * cell + 7, food.y * cell + 7, cell - 14, cell - 14, 5); ctx.fill();
    ctx.shadowBlur = 0;
    snake.forEach((part, index) => {
      ctx.fillStyle = index === 0 ? colors[palette] : index % 2 ? colors[palette] + 'cc' : colors[palette] + 'aa';
      ctx.beginPath(); ctx.roundRect(part.x * cell + 2, part.y * cell + 2, cell - 4, cell - 4, index === 0 ? 7 : 4); ctx.fill();
      if (index === 0) {
        const horizontal = direction === 'left' || direction === 'right';
        const sx = direction === 'left' ? 8 : direction === 'right' ? 21 : 10;
        const sy = direction === 'up' ? 8 : direction === 'down' ? 21 : 10;
        ctx.fillStyle = '#06121f';
        ctx.fillRect(part.x * cell + sx, part.y * cell + sy, 3, 3);
        ctx.fillRect(part.x * cell + sx + (horizontal ? 0 : 9), part.y * cell + sy + (horizontal ? 9 : 0), 3, 3);
      }
    });
  }

  function placeFood() {
    const free = [];
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
      if (!snake.some((part) => part.x === x && part.y === y)) free.push({ x, y });
    }
    if (!free.length) { pause(); resetBoard(); toast('棋盘满了！积分保留，再来一局。'); return; }
    food = free[Math.floor(Math.random() * free.length)];
  }

  function scoreFood() {
    const level = activeLevel;
    pendingScores++;
    scoreQueue = scoreQueue.then(async () => {
      const result = await api(`/api/level/${level}/score`, { delta: 1 });
      const prior = state.stage1.cleared;
      if (level === 1) {
        state.stage1 = { score: result.score, rawScore: result.score, cleared: result.cleared };
        if (result.cleared && !prior) state = await api('/api/session');
      } else {
        state.stage2 = result;
        state.flag = result.flag;
      }
      renderState();
    }).catch((error) => { pause(); toast(error.message); }).finally(() => { pendingScores--; });
  }

  function tick() {
    direction = nextDirection;
    queuedTurn = false;
    const [dx, dy] = vectors[direction];
    const head = { x: snake[0].x + dx, y: snake[0].y + dy };
    const eating = head.x === food.x && head.y === food.y;
    const body = eating ? snake : snake.slice(0, -1);
    if (head.x < 0 || head.x >= size || head.y < 0 || head.y >= size || body.some((part) => part.x === head.x && part.y === head.y)) {
      pause();
      const level = activeLevel;
      overlay('TRY AGAIN', '本局结束。', '分数已归零。', '再来一局 ↗');
      $('game-status').textContent = '本局结束';
      void resetRoundAfterDeath(level);
      return;
    }
    snake.unshift(head);
    if (eating) { scoreFood(); placeFood(); } else snake.pop();
    draw();
  }

  function pause() {
    clearInterval(timer);
    running = false;
    $('game-status').textContent = '已暂停';
  }

  async function resetRoundAfterDeath(level) {
    resettingAfterDeath = true;
    $('play-button').disabled = true;
    try {
      await scoreQueue;
      state = await api('/api/round/reset', { level });
      renderState();
    } catch (error) {
      toast(error.message);
    } finally {
      resettingAfterDeath = false;
      $('play-button').disabled = false;
    }
  }

  function start() {
    if (!state || resettingAfterDeath) return;
    if (state.flag) { $('completion').scrollIntoView({ behavior: 'smooth' }); return; }
    if (activeLevel === 1 && state.stage1.cleared) { selectLevel(2); return; }
    if ($('overlay-kicker').textContent !== 'PAUSED') resetBoard();
    $('game-overlay').hidden = true;
    running = true;
    $('game-status').textContent = '游戏中';
    clearInterval(timer);
    timer = setInterval(tick, 150);
    canvas.focus({ preventScroll: true });
  }

  function togglePause() {
    if (running) { pause(); overlay('PAUSED', '歇一口气。', '准备好了就继续。', '继续游戏 ↗'); }
    else start();
  }

  function turn(value) {
    if (!running || queuedTurn) return;
    const [dx, dy] = vectors[direction];
    const [nx, ny] = vectors[value];
    if (dx + nx === 0 && dy + ny === 0) return;
    nextDirection = value;
    queuedTurn = true;
  }

  function selectLevel(level) {
    if (!state || (level === 2 && !state.stage1.cleared)) return;
    pause();
    activeLevel = level;
    $('source-panel').hidden = true;
    resetBoard();
    overlay('YOUR MOVE', level === 1 ? '' : '换个思路，再来。', level === 1 ? '' : '每十分一个轮回。目标还是 99,999。', '开始游戏 ↗');
    renderState();
  }

  async function sync() {
    if (syncing || pendingScores || document.hidden) return;
    syncing = true;
    try {
      const previous = state;
      state = await api('/api/session');
      if (previous?.stage2?.cleared && !state.stage1.cleared) selectLevel(1);
      renderState();
    } catch (error) { toast(error.message); }
    finally { syncing = false; }
  }

  $('play-button').addEventListener('click', start);
  $('level-one').addEventListener('click', () => selectLevel(1));
  $('level-two').addEventListener('click', () => selectLevel(2));
  document.addEventListener('keydown', (event) => {
    if (['SELECT', 'INPUT', 'TEXTAREA', 'BUTTON'].includes(document.activeElement?.tagName)) return;
    const keyMap = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', w: 'up', s: 'down', a: 'left', d: 'right' };
    if (event.code === 'Space') { event.preventDefault(); togglePause(); }
    else if (Object.hasOwn(keyMap, event.key)) { event.preventDefault(); turn(keyMap[event.key]); }
  });
  document.querySelectorAll('[data-direction]').forEach((button) => button.addEventListener('click', () => turn(button.dataset.direction)));
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && running) { pause(); overlay('PAUSED', '歇一口气。', '切回游戏，继续你的挑战。', '继续游戏 ↗'); }
    if (!document.hidden) sync();
  });
  $('palette').addEventListener('change', async (event) => {
    const value = event.target.value;
    applyPalette(value);
    if (activeLevel === 2) {
      try { state = await api('/api/profile', { preferences: { palette: value } }); renderState(); toast('偏好已同步。'); }
      catch (error) { toast(error.message); }
    }
  });
  $('source-button').addEventListener('click', async () => {
    try {
      const response = await fetch('/assets/score-service.js', { cache: 'no-store' });
      if (!response.ok) throw new Error('无法读取计分服务。');
      $('source-code').textContent = await response.text();
      $('source-panel').hidden = false;
      $('source-panel').scrollIntoView({ behavior: 'smooth', block: 'start' });
    } catch (error) { toast(error.message); }
  });
  $('close-source').addEventListener('click', () => { $('source-panel').hidden = true; });
  $('reset-button').addEventListener('click', async () => {
    if (!window.confirm('这会清空两关积分与已获得的 Flag，确认重置？')) return;
    pause();
    await scoreQueue;
    try {
      state = await api('/api/reset', {});
      $('completion').hidden = true;
      selectLevel(1);
      toast('挑战已重置。');
    } catch (error) { toast(error.message); }
  });
  $('copy-flag').addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(state.flag); toast('Flag 已复制。'); }
    catch { toast('请选中 Flag 手动复制。'); }
  });

  resetBoard();
  api('/api/session').then((data) => {
    state = data;
    activeLevel = data.level;
    if (data.stage2) applyPalette(data.stage2.palette);
    selectLevel(activeLevel);
    pollTimer = setInterval(sync, 2000);
  }).catch((error) => {
    $('game-status').textContent = '连接失败';
    $('play-button').disabled = true;
    toast(error.message);
  });
  window.addEventListener('pagehide', () => { clearInterval(timer); clearInterval(pollTimer); });
})();
