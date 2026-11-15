/*
 * 시연 진행 도구 (web only): the scene bar under the car display, the virtual car panel fold, fullscreen, ?auto=1.
 * Every scene drives the real game through ui.js (__carEscape): virtual car state changes and clicks on the app's own buttons.
 * Jumping to a scene = a clean reset, then the earlier scenes replayed with the engine clock time-lapsed (no screen is faked).
 */
(function () {
  'use strict';
  const X = window.__carEscape, CE = window.CarEscape, g = X.game;
  const $ = (id) => document.getElementById(id);
  const page = $('page'), countEl = $('pv-count'), descEl = $('pv-desc'), stepsEl = $('pv-steps'), autoBtn = $('pv-auto'), seekEl = $('pl-seek');
  const pref = { get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }, set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } } };

  // ---------------------------------------------------------------- panel fold (saved) and fullscreen
  const foldBtn = $('pv-panel-btn');
  function fold(off) {
    page.classList.toggle('panel-off', off);
    foldBtn.setAttribute('aria-expanded', String(!off));
    foldBtn.setAttribute('aria-label', off ? '가상 차량 패널 펼치기' : '가상 차량 패널 접기');
  }
  fold(pref.get('carescape.panel') === '0');
  foldBtn.addEventListener('click', () => { const off = !page.classList.contains('panel-off'); fold(off); pref.set('carescape.panel', off ? '0' : '1'); });
  const fullBtn = $('pv-full'), root = document.documentElement;
  if (!root.requestFullscreen) fullBtn.hidden = true;
  fullBtn.addEventListener('click', () => { (document.fullscreenElement ? document.exitFullscreen() : root.requestFullscreen()).catch(() => {}); });
  document.addEventListener('fullscreenchange', () => { fullBtn.textContent = document.fullscreenElement ? '전체화면 끝' : '전체화면'; });

  // ---------------------------------------------------------------- runner
  class Stop extends Error {}
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let run = 0; // bumping it cancels whatever is playing
  let fast = false; // seeking: waits become engine clock skips
  let cur = -1; // scene on the bar
  let at = null; // the game stands exactly at the start of scene `at`; null = somewhere else (mid scene, or the visitor touched it)
  let auto = false, busy = false;
  let noGesture = false; // ?auto=1 starts without a tap: browsers refuse audio until one
  const quiet = () => { X.quiet = fast || noGesture; };
  const btn = (act) => document.querySelector(`#game [data-act="${act}"]`);

  /** What a scene may do. Every await checks that this run is still the current one. */
  function ctx(id) {
    const alive = () => { if (id !== run) throw new Stop(); };
    const c = {
      say(t) { if (!fast) descEl.textContent = t; },
      car(f) { X.update(f); },
      async wait(ms) { if (fast) { X.skip(ms); await sleep(0); } else await sleep(ms); alive(); },
      async until(pred, max = 30000) {
        for (let t = 0; !pred(); t += 50) {
          if (t > max) throw new Error('scene timed out');
          if (fast) X.skip(250);
          await sleep(fast ? 16 : 50); alive();
        }
      },
      /** Tap one of the app's own buttons once it is on screen (or move on when `or` already holds). */
      async click(act, or = () => false) { await c.until(() => btn(act) || or()); const b = btn(act); if (b) b.click(); await c.wait(60); },
    };
    return c;
  }

  /** Solve puzzle i with the engine's own demo steps (the same moves a family makes in the car). */
  async function solve(c, i) {
    await c.until(() => g.ui.phase === 'PUZZLE' && g.ui.puzzleIndex === i);
    c.say(`잠금 ${i + 1} · ${g.ui.puzzleTitle}: "${g.ui.prompt}" 가상 차량 패널에서 금색으로 표시된 부품을 움직이면 게임이 그 값을 읽습니다.`);
    await c.wait(2200);
    for (const st of g.demoSolution(i)) { c.car(st.change); await c.wait(st.waitMs); }
    await c.until(() => g.ui.phase !== 'PUZZLE');
    await c.wait(1200);
    await c.until(() => g.ui.phase !== 'SOLVED');
  }
  const openPick = (list, not) => list.slice().reverse().find((it) => CE.isOpen(it, X.earned) && it.id !== not);

  const SCENES = [
    { name: '충전 시작', async play(c) {
      c.say('충전 전의 타이틀 화면입니다. 왼쪽은 차량 주행 정보 화면, 오른쪽 큰 창이 카 이스케이프 앱이에요.');
      await c.wait(2400);
      c.say('충전구를 열고 충전을 시작하면 "1화가 준비됐어요" 카드가 뜹니다. 충전 한 번이 이야기 한 화예요.');
      c.car((s) => ({ ...s, chargePortOpen: true }));
      await c.wait(1000);
      c.car((s) => ({ ...s, charging: true }));
      await c.wait(3000);
    } },
    { name: '미션 시작', async play(c) {
      c.say('"1화 시작"을 누르면 오늘의 이야기가 음성과 함께 시작됩니다.');
      await c.wait(1400);
      await c.click('ready', () => g.ui.phase !== 'TITLE');
      await c.until(() => g.ui.phase === 'INTRO');
      await c.wait(3400);
      await c.click('intro', () => g.ui.phase === 'PUZZLE');
      await c.until(() => g.ui.phase === 'PUZZLE');
      c.say(`잠금 ${g.ui.puzzleCount}개를 화면 터치가 아니라 차의 실제 부품(창문, 열선, 독서등)으로 풉니다.`);
      await c.wait(2600);
    } },
    { name: '퍼즐 1', play: (c) => solve(c, 0) },
    { name: '퍼즐 2', play: (c) => solve(c, 1) },
    { name: '주행 중 일시정지', async play(c) {
      await c.until(() => g.ui.phase === 'PUZZLE');
      c.say('퍼즐 도중 기어를 D로 바꾸면 화면이 바로 가려지고 음성도 멈춥니다. 주행 중에는 누를 것이 없어요.');
      await c.wait(2000);
      c.car((s) => ({ ...s, gear: 'DRIVE', speedKmh: 30 }));
      await c.wait(3600);
      c.say('기어를 P로 되돌리면 멈췄던 잠금에서 그대로 이어집니다.');
      c.car((s) => ({ ...s, gear: 'PARK', speedKmh: 0 }));
      await c.until(() => g.ui.phase === 'PUZZLE');
      await c.wait(2200);
    } },
    { name: '남은 퍼즐', async play(c) {
      await c.until(() => g.ui.phase === 'PUZZLE');
      for (let i = g.ui.puzzleIndex; i < g.ui.puzzleCount; i++) await solve(c, i);
    } },
    { name: '충전 완료 엔딩', async play(c) {
      await c.until(() => g.ui.phase === 'ENDING');
      if (g.ui.waitingForCharge) {
        c.say('퍼즐을 다 풀어도 엔딩은 충전이 목표에 닿아야 열립니다. 시연에서는 배터리를 빠르게 올려요.');
        await c.wait(2600);
        const target = X.vehicle.chargeTargetPercent;
        for (let soc = Math.trunc(X.vehicle.socPercent); soc < target;) {
          soc = Math.min(target, soc + 2);
          const v = soc;
          c.car((s) => ({ ...s, socPercent: v, rangeMeters: s.rangeMeters == null ? null : v * CE.SIM_METERS_PER_PERCENT }));
          await c.wait(110);
        }
        await c.until(() => !g.ui.waitingForCharge);
      }
      c.say('목표 충전량 도달: 별점과 걸린 시간, 이제 갈 수 있는 거리와 함께 엔딩이 열립니다. 마지막 미션은 "원래대로 출발"이에요.');
      await c.wait(5200);
      const s0 = g.startVs;
      if (s0 && (g.ui.restoreLeft || []).length) {
        c.say('퍼즐 때문에 달라진 창문과 열선을 가족이 직접 처음 상태로 돌려 놓으면 "출발 준비 완료"가 됩니다.');
        await c.wait(1400);
        c.car((s) => ({ ...s, windowDriverPercent: s0.windowDriverPercent, windows: s0.windows, seatHeatDriverLevel: s0.seatHeatDriverLevel,
          steeringHeatLevel: s0.steeringHeatLevel, readingLightOn: s0.readingLightOn, wiperOn: s0.wiperOn }));
        await c.wait(3600);
      }
    } },
    { name: '마이 차고', async play(c) {
      await c.click('back', () => g.ui.phase === 'TITLE');
      await c.until(() => g.ui.phase === 'TITLE');
      c.say('타이틀에 가족 충전 일지가 쌓였습니다. 모은 별과 배지로 "우리 차 꾸미기"의 아이템이 열려요.');
      await c.wait(2800);
      await c.click('garage');
      await c.wait(1600);
      const paint = openPick(CE.Paint, X.look.paint);
      if (paint) { c.say(`페인트: 방금 받은 별과 배지로 열린 "${paint.label}"을 칠합니다. 3D 차는 끌어서 돌려 볼 수 있어요.`); await c.click('paint:' + paint.id); await c.wait(2600); }
      await c.click('tab:wheel');
      c.say('휠은 아직 잠겨 있습니다. 여는 조건이 아이템마다 적혀 있어, 다음 충전 때 다시 올 이유가 돼요.');
      await c.wait(3000);
      c.say('시연용으로 시즌 1을 모두 마친 가족의 기록을 불러와 나머지 아이템을 엽니다.');
      X.grant();
      await c.wait(1800);
      const wheel = openPick(CE.Wheel, X.look.wheel);
      if (wheel) { c.say(`휠: "${wheel.label}"로 바꿉니다.`); await c.click('wheel:' + wheel.id); await c.wait(2200); }
      await c.click('tab:sticker');
      const st = openPick(CE.STICKERS, null);
      if (st) { c.say(`스티커: 미션 배지마다 하나씩 열립니다. "${st.label}" 스티커를 앞문에 붙여요.`); await c.wait(900); await c.click('sticker:' + st.id); await c.wait(2200); }
      await c.click('tab:theme');
      const theme = openPick(CE.Theme, X.look.theme);
      if (theme) { c.say(`테마: "${theme.label}"를 고르면 앱 전체 색과 배경이 바뀝니다.`); await c.wait(900); await c.click('theme:' + theme.id); await c.wait(3000); }
    } },
    { name: '드라이브 모드 테마', async play(c) {
      await c.click('garage', () => !!btn('tab:mode'));
      await c.click('tab:mode');
      c.say('주행 모드는 차가 알려 주고 앱은 읽기만 합니다. "모드 따라가기"를 켜면 모드에 맞춰 테마와 무드등 색이 바뀌어요.');
      await c.wait(2400);
      if (!X.look.followMode) await c.click('follow');
      await c.wait(1400);
      for (const [mode, line] of [['ECO', '에코 모드로 바꾸면 숲속 캠핑 테마'], ['SPORT', '스포츠 모드로 바꾸면 불꽃 서킷 테마'], ['NORMAL', '노멀 모드로 돌아오면 밤하늘 테마']]) {
        c.say(`${line}: 가상 차량의 주행 모드만 바꿨고, 앱은 그 값을 읽어 스스로 갈아입습니다.`);
        c.car((s) => ({ ...s, driveMode: mode }));
        await c.wait(2800);
      }
      await c.click('garageback');
      await c.wait(1200);
      c.say('시연 끝. 화면의 "시작"을 눌러 직접 풀어 보거나, 가상 차량을 움직여 보세요. "처음부터"는 기록을 지우고 첫 장면으로 돌아갑니다.');
    } },
  ];

  const HELP = '"자동 시연"을 누르면 충전 시작부터 퍼즐, 엔딩, 차 꾸미기까지 순서대로 보여 줍니다. 직접 해 보려면 화면의 "시작"을 누르고 가상 차량을 조작하세요.';
  function paint() {
    countEl.textContent = cur < 0 ? '안내' : `${cur + 1} / ${SCENES.length} ${SCENES[cur].name}`;
    autoBtn.textContent = auto ? '멈춤' : '자동 시연';
    autoBtn.setAttribute('aria-pressed', String(auto));
    [...stepsEl.children].forEach((li, i) => { li.className = i === cur ? 'cur' : i < cur ? 'done' : ''; li.firstChild.setAttribute('aria-current', i === cur ? 'step' : 'false'); });
    $('pv-prev').disabled = cur <= 0;
    $('pv-next').disabled = cur >= SCENES.length - 1 && at !== 0;
  }
  /** Clean first run, then scenes 0..k-1 replayed fast: the game ends up exactly where scene k begins. */
  async function seek(k, id) {
    fast = true; quiet(); seekEl.hidden = k === 0;
    try {
      X.reset({ charging: false });
      for (let i = 0; i < k; i++) await SCENES[i].play(ctx(id));
      at = k;
    } finally { if (id === run) { fast = false; quiet(); seekEl.hidden = true; } }
  }
  /** Play scene k (and on, when auto). */
  async function go(k, keepGoing) {
    const id = ++run;
    auto = keepGoing; busy = true; cur = k; paint();
    try {
      if (at !== k) await seek(k, id);
      for (;; k++) {
        cur = k; at = null; paint();
        await SCENES[k].play(ctx(id));
        at = k + 1;
        if (!auto || k + 1 >= SCENES.length) break;
        await ctx(id).wait(700);
      }
    } catch (e) {
      if (!(e instanceof Stop)) { descEl.textContent = '시연이 멈췄어요. "처음부터"를 눌러 다시 시작해 주세요.'; at = null; }
    } finally { if (id === run) { auto = false; busy = false; paint(); } }
  }
  function halt() { run++; if (busy) at = null; auto = false; busy = false; if (fast) { fast = false; seekEl.hidden = true; } quiet(); paint(); }
  const nextIndex = () => at != null ? at % SCENES.length : Math.max(0, cur);

  SCENES.forEach((s, i) => {
    const li = document.createElement('li'), b = document.createElement('button');
    b.type = 'button'; b.textContent = `${i + 1} ${s.name}`;
    b.addEventListener('click', () => go(i, false));
    li.appendChild(b); stepsEl.appendChild(li);
  });
  autoBtn.addEventListener('click', () => { if (auto) { halt(); descEl.textContent = '멈췄습니다. "자동 시연"을 누르면 이 장면부터 다시 보여 줍니다.'; } else go(nextIndex(), true); });
  $('pv-next').addEventListener('click', () => go(at != null ? at % SCENES.length : Math.min(SCENES.length - 1, cur + 1), false));
  $('pv-prev').addEventListener('click', () => go(Math.max(0, cur - 1), false));
  $('pv-restart').addEventListener('click', async () => { halt(); const id = ++run; cur = -1; await seek(0, id).catch(() => {}); descEl.textContent = HELP; paint(); });
  // The visitor takes over: touching the car display or the virtual car stops the show.
  for (const el of [$('frame'), $('panel')]) for (const t of ['pointerdown', 'keydown']) {
    el.addEventListener(t, (e) => {
      if (!e.isTrusted || e.target.closest('#pv-panel-btn')) return;
      const was = busy;
      at = null;
      if (was) { halt(); descEl.textContent = '직접 조작 중이라 자동 시연을 멈췄습니다. "자동 시연"을 누르면 이 장면부터 다시 보여 줍니다.'; }
    }, true);
  }
  descEl.textContent = HELP;
  paint();
  if (X.auto) {
    noGesture = true; quiet();
    window.addEventListener('pointerdown', () => { noGesture = false; quiet(); }, { once: true, capture: true });
    window.addEventListener('load', () => setTimeout(() => go(0, true), 900));
  }
  // QA hook: headless runs read where the show is
  window.__present = { get scene() { return cur; }, get busy() { return busy; }, get at() { return at; }, names: SCENES.map((s) => s.name) };
})();
