/*
 * 카 이스케이프 web UI: ui/Screens.kt + Visuals.kt + Kit.kt + DemoPanel.kt and the app/GameViewModel.kt wiring
 * (200 ms tick, sim charger with x30 time-lapse, records, badges, TTS, tones, ambient light).
 */
(function () {
  'use strict';
  const CE = window.CarEscape;
  const K = { bg: '#0A111E', deep: '#05080F', surface: '#131C2C', surface2: '#1C2739', line: 'rgba(233,220,196,0.25)', text: '#F4F1EA', sub: '#B7C0CE', faint: '#8590A3', gold: '#E8B962', amber: '#F2994A', onGold: '#1A1305' };

  // ---------------------------------------------------------------- engine wiring (GameViewModel)
  const params = new URLSearchParams(location.search);
  const pinnedSeed = /^\d+$/.test(params.get('seed') || '') ? Number(params.get('seed')) : null; // like `--el seed N`
  const game = new CE.EscapeGame();
  // Demo car: charging at 42 % toward 80 %, charge port closed (opening it shows "N화가 준비됐어요"), range known, drive mode unknown (like the Android simulator).
  let vs = CE.vehicleState({ chargePortOpen: false, rangeMeters: 42 * CE.SIM_METERS_PER_PERCENT });
  let timelapse = false;
  let sound = true;
  const TICK_MS = 200;

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode: progress lives for this tab only */ } },
    del(k) { try { localStorage.removeItem(k); } catch (e) { /* same */ } },
  };
  function loadProgress() {
    const best = {};
    for (const e of CE.Episodes.all) {
      const v = (store.get('carescape.best.' + e.id) || '').split(',').map(Number);
      if (v.length === 2 && v.every(Number.isInteger)) best[e.id] = { stars: v[0], sec: v[1] };
    }
    let badges = [];
    try { badges = JSON.parse(store.get('carescape.badges') || '[]').filter((k) => CE.KINDS.includes(k)); } catch (e) { badges = []; }
    let log = [];
    try { log = JSON.parse(store.get('carescape.log') || '[]').filter((r) => r && Number.isInteger(r.episode) && Number.isInteger(r.stars)); } catch (e) { log = []; }
    return { best, badges, newBadges: [], newBest: false, log, newOpened: [] };
  }
  let progress = loadProgress();
  /** Earned for the garage: best stars summed, badges, episodes finished (GameViewModel.earnedOf). */
  const earnedOf = (p) => ({ stars: Object.values(p.best).reduce((a, r) => a + r.stars, 0), badges: p.badges, clears: Object.keys(p.best).length });
  // 마이 차고: the saved car and skin
  let look = CE.decodeLook(store.get('carescape.look'));
  function setLook(l) { look = l; store.set('carescape.look', CE.encodeLook(l)); curLayout = ''; render(); }
  let recorded = false;
  let started = null; // [SoC, range] when this episode began, for the 일지 row
  { const n = Number(store.get('carescape.next_episode')); if (Number.isInteger(n)) game.nextEpisodeIndex = n; }
  /** 가족 충전 일지 삭제 (the title's button). Records and badges stay. */
  function clearLog() { store.set('carescape.log', '[]'); progress = { ...progress, log: [] }; }
  /** "우리 가족의 N번째 충전 모험" (1 reads 첫 번째). */
  const adventure = (n) => `우리 가족의 ${n === 1 ? '첫 번째' : `${n}번째`} 충전 모험`;
  const logLine = (r) => `${r.date} · ${r.episode}화 · ${'★'.repeat(r.stars)} · ${r.socFrom}→${r.socTo}%` + (r.km != null ? ` · +${r.km}km` : '');
  /** Final ending (not the waiting room): save once, mark what is new for the ending screen. */
  function record(ui) {
    if (ui.phase === 'INTRO' || ui.phase === 'TITLE') recorded = false;
    if (ui.phase === 'TITLE') started = null;
    if (ui.phase === 'INTRO' && started == null) started = [Math.trunc(ui.socPercent), vs.rangeMeters];
    if (ui.phase !== 'ENDING' || ui.waitingForCharge) return;
    if (recorded || !ui.episodeId || ui.stars === 0) return;
    recorded = true;
    const old = progress.best[ui.episodeId];
    const mine = { stars: ui.stars, sec: ui.elapsedSec };
    const newBest = !old || mine.stars > old.stars || (mine.stars === old.stars && mine.sec < old.sec);
    const best = { ...progress.best };
    if (newBest) { best[ui.episodeId] = mine; store.set('carescape.best.' + ui.episodeId, `${mine.stars},${mine.sec}`); }
    const newBadges = ui.solvedKinds.filter((k) => !progress.badges.includes(k));
    const badges = CE.KINDS.filter((k) => progress.badges.includes(k) || ui.solvedKinds.includes(k));
    store.set('carescape.badges', JSON.stringify(badges));
    // One 일지 row per ending: date, N화, stars, SoC start -> end, range gained (only when both ends were read).
    const [soc0, range0] = started ?? [Math.trunc(ui.socPercent), null];
    const range1 = vs.rangeMeters;
    const km = range0 != null && range1 != null ? Math.max(0, Math.round((range1 - range0) / 1000)) : null; // round: float steps make 190 read 189.99
    const d = new Date();
    const log = progress.log.concat({ date: `${d.getMonth() + 1}/${d.getDate()}`, episode: ui.episodeNumber, stars: ui.stars, socFrom: soc0, socTo: Math.trunc(ui.socPercent), km });
    store.set('carescape.log', JSON.stringify(log));
    store.set('carescape.next_episode', String(game.nextEpisodeIndex));
    progress = { best, badges, newBadges, newBest, log, newOpened: CE.newlyOpened(earnedOf(progress), earnedOf({ best, badges })) };
  }

  // sound: Web Speech for the game master, short tones for Sfx (ToneGenerator stand-ins)
  let actx = null;
  let quiet = false; // present.js: jumping to a scene replays the game fast, without sound
  function tone(seq) {
    if (!sound || quiet) return;
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      let t = actx.currentTime;
      for (const [f, ms] of seq) {
        const o = actx.createOscillator(), g = actx.createGain();
        o.frequency.value = f; o.type = 'sine';
        g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.18, t + 0.01); g.gain.exponentialRampToValueAtTime(0.0001, t + ms / 1000);
        o.connect(g).connect(actx.destination); o.start(t); o.stop(t + ms / 1000 + 0.02);
        t += ms / 1000;
      }
    } catch (e) { /* no audio device */ }
  }
  const TONES = { TICK: [[1200, 60]], CORRECT: [[880, 110], [1320, 110]], WRONG: [[300, 130], [220, 130]], UNLOCK: [[990, 100], [740, 100]], ENDING: [[660, 150], [880, 150], [1100, 150], [1320, 150]] };
  // Narrator: pre-recorded clips, one per sentence/comma piece (voice/enumerate.mjs splits the same way).
  // A piece with no clip is skipped; the text is on screen anyway.
  let voiceMap = {}, voiceRun = 0, voiceEl = null;
  fetch('voice/manifest.json').then((r) => r.json()).then((m) => { voiceMap = m; }).catch(() => {});
  const pieces = (t) => (t.match(/[^.!?,]+[.!?,]+|[^.!?,]+$/g) || []).map((s) => s.trim()).filter(Boolean);
  async function speak(text) {
    const run = ++voiceRun;
    if (voiceEl) voiceEl.pause();
    if (!sound || quiet) return;
    for (const p of pieces(text)) {
      const id = voiceMap[p];
      if (!id) continue;
      await new Promise((done) => {
        voiceEl = new Audio(`voice/clips/${id}.mp3`);
        voiceEl.onended = voiceEl.onerror = done;
        voiceEl.play().catch(done);
      });
      if (run !== voiceRun) return;
      await new Promise((r) => setTimeout(r, p.endsWith(',') ? 90 : 220));
      if (run !== voiceRun) return;
    }
  }
  const stopSpeech = () => { voiceRun++; if (voiceEl) voiceEl.pause(); };
  const ambientEl = document.getElementById('ambient');
  function ambient(color, brightness) {
    if (color == null) { ambientEl.style.setProperty('--amb-o', '0'); return; }
    ambientEl.style.setProperty('--amb', '#' + color.toString(16).padStart(6, '0'));
    ambientEl.style.setProperty('--amb-o', String(0.25 + 0.75 * brightness / 100));
  }

  // 무드등 미리 켜 보기: parked only, same ambient path as the puzzles; leaving the garage or driving off restores it.
  let moodPreview = false;
  function previewMood(rgb) { if (driving()) return false; moodPreview = true; ambient(rgb, 70); return true; }
  function endMoodPreview() { if (!moodPreview) return; moodPreview = false; ambient(null); }

  let dirty = false;
  game.on((e) => {
    switch (e.type) {
      case 'Speak': if (!driving()) speak(e.text); break;
      case 'Sfx': tone(TONES[e.kind]); break;
      case 'Ambient': ambient(e.color, e.brightness); break;
      case 'AmbientRestore': ambient(null); break;
      case 'Silence': stopSpeech(); break; // door opened: stop mid sentence
      case 'Ui': record(e.ui); if (!dirty) { dirty = true; requestAnimationFrame(() => { dirty = false; render(); }); } break;
    }
  });
  const driving = () => vs.gear !== 'PARK' || vs.speedKmh > 0.5;
  let skew = 0;
  const step = () => game.onVehicle(vs, performance.now() + skew);
  function update(f) {
    const wasDriving = driving();
    vs = f(vs);
    if (!wasDriving && driving()) { stopSpeech(); endMoodPreview(); } // driving: silence the game master right away
    syncPanel();
    step();
  }
  let lastCharge = 0;
  setInterval(() => {
    const now = performance.now();
    if (lastCharge > 0) {
      const before = vs.socPercent;
      vs = CE.simCharge(vs, Math.min(now - lastCharge, 1000), timelapse ? CE.TIMELAPSE : 1);
      if (Math.trunc(vs.socPercent) !== Math.trunc(before)) syncPanel();
    }
    lastCharge = now;
    step();
  }, TICK_MS);
  document.addEventListener('visibilitychange', () => { if (document.hidden) { stopSpeech(); const was = moodPreview; moodPreview = false; ambient(null); if (was) render(); } });

  // ---------------------------------------------------------------- virtual car panel (DemoPanel)
  const panel = document.getElementById('panel');
  const $$ = (sel) => Array.from(panel.querySelectorAll(sel));
  function syncPanel() {
    const segVal = { mode: vs.driveMode ?? '', gear: vs.gear === 'PARK' ? 'PARK' : 'DRIVE', turn: vs.turnSignal === 'LEFT' || vs.turnSignal === 'RIGHT' ? vs.turnSignal : 'OFF', steer: String(vs.steeringHeatLevel), seat: String(vs.seatHeatDriverLevel) };
    for (const g of $$('[data-seg]')) for (const b of g.querySelectorAll('button')) b.setAttribute('aria-pressed', String(b.dataset.v === segVal[g.dataset.seg]));
    const rv = { speed: Math.round(vs.speedKmh), soc: Math.trunc(vs.socPercent), target: vs.chargeTargetPercent, 'win:driver': vs.windowDriverPercent };
    for (const w of ['ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_RIGHT']) rv['win:' + w] = CE.windowPercent(vs, w);
    for (const r of $$('[data-range]')) { if (document.activeElement !== r || r.dataset.range === 'soc') r.value = rv[r.dataset.range]; }
    rv.range = vs.rangeMeters != null ? `${Math.round(vs.rangeMeters / 1000)}km` : '모름';
    for (const o of $$('[data-out]')) o.textContent = rv[o.dataset.out];
    const tg = { charging: vs.charging, timelapse, sound, port: vs.chargePortOpen === true, door: vs.doorOpen, rearlock: vs.rearWindowLocked, range: vs.rangeMeters != null };
    for (const t of $$('[data-toggle]')) t.checked = tg[t.dataset.toggle];
    for (const b of $$('[data-belt]')) b.setAttribute('aria-pressed', String(vs.belts.includes(b.dataset.belt)));
    for (const h of $$('[data-hold]')) {
      const on = h.dataset.hold === 'light' ? vs.readingLightOn : vs.wiperOn;
      h.setAttribute('aria-pressed', String(on));
      h.textContent = h.dataset.hold === 'light' ? (on ? '켜짐 · 손을 떼면 꺼짐' : '누르고 있는 동안 켜짐') : (on ? '닦는 중 · 손을 떼면 멈춤' : '톡 누르면 한 번 닦기');
    }
  }
  panel.addEventListener('click', (ev) => {
    const b = ev.target.closest('button');
    if (!b) return;
    const seg = b.parentElement.dataset.seg;
    const v = b.dataset.v;
    if (seg === 'mode') update((s) => ({ ...s, driveMode: v || null }));
    else if (seg === 'gear') update((s) => ({ ...s, gear: v, speedKmh: v === 'PARK' ? 0 : s.speedKmh }));
    else if (seg === 'turn') update((s) => ({ ...s, turnSignal: v }));
    else if (seg === 'steer') update((s) => ({ ...s, steeringHeatLevel: Number(v) }));
    else if (seg === 'seat') update((s) => ({ ...s, seatHeatDriverLevel: Number(v) }));
    else if (b.dataset.belt) { const seat = b.dataset.belt; update((s) => ({ ...s, belts: s.belts.includes(seat) ? s.belts.filter((x) => x !== seat) : [...s.belts, seat] })); }
  });
  panel.addEventListener('input', (ev) => {
    const r = ev.target;
    const x = Number(r.value);
    if (r.dataset.range) {
      const k = r.dataset.range;
      if (k === 'speed') update((s) => ({ ...s, speedKmh: x }));
      // a known range follows the SoC (same 500 km at 100 % as Sim.kt)
      else if (k === 'soc') update((s) => ({ ...s, socPercent: x, rangeMeters: s.rangeMeters == null ? null : Math.max(0, s.rangeMeters + (x - s.socPercent) * CE.SIM_METERS_PER_PERCENT) }));
      else if (k === 'target') update((s) => ({ ...s, chargeTargetPercent: x }));
      else if (k === 'win:driver') update((s) => ({ ...s, windowDriverPercent: x }));
      else { const w = k.slice(4); update((s) => ({ ...s, windows: { ...s.windows, [w]: x } })); }
    } else if (r.dataset.toggle === 'charging') update((s) => ({ ...s, charging: r.checked }));
    else if (r.dataset.toggle === 'port') update((s) => ({ ...s, chargePortOpen: r.checked }));
    else if (r.dataset.toggle === 'door') update((s) => ({ ...s, doorOpen: r.checked }));
    else if (r.dataset.toggle === 'rearlock') update((s) => ({ ...s, rearWindowLocked: r.checked }));
    else if (r.dataset.toggle === 'range') update((s) => ({ ...s, rangeMeters: r.checked ? s.socPercent * CE.SIM_METERS_PER_PERCENT : null }));
    else if (r.dataset.toggle === 'timelapse') { timelapse = r.checked; syncPanel(); }
    else if (r.dataset.toggle === 'sound') { sound = r.checked; if (!sound) stopSpeech(); syncPanel(); }
  });
  // Press-and-hold: on while the pointer (or Space/Enter) is down, so hold time reaches the game as-is (Morse).
  for (const h of $$('[data-hold]')) {
    const key = h.dataset.hold === 'light' ? 'readingLightOn' : 'wiperOn';
    const set = (on) => { if (vs[key] !== on) update((s) => ({ ...s, [key]: on })); };
    h.addEventListener('pointerdown', (e) => { e.preventDefault(); try { h.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ } set(true); });
    for (const t of ['pointerup', 'pointercancel', 'lostpointercapture']) h.addEventListener(t, () => set(false));
    h.addEventListener('keydown', (e) => { if ((e.key === ' ' || e.key === 'Enter') && !e.repeat) { e.preventDefault(); set(true); } });
    h.addEventListener('keyup', (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); set(false); } });
    h.addEventListener('blur', () => set(false));
    h.addEventListener('contextmenu', (e) => e.preventDefault());
  }

  // ---------------------------------------------------------------- Kit.kt helpers
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const dp = (v) => `calc(${v} * var(--u))`;
  const W = { n: 400, s: 600, b: 700 };
  /** Txt(text, size, color, weight, align, maxLines) */
  function T(text, size, o = {}) {
    const st = [`font-size:max(16px, calc(${size} * var(--u)))`, `color:var(--${o.c || 'text'})`, `font-weight:${W[o.w || 'n']}`];
    if (o.align) st.push(`text-align:${o.align}`);
    let cls = 'txt';
    if (o.lines === 1) cls += ' one';
    else if (o.lines) { cls += ' clamp'; st.push(`-webkit-line-clamp:${o.lines}`); }
    if (o.style) st.push(o.style);
    return `<div class="${cls}" style="${st.join(';')}">${esc(text)}</div>`;
  }
  const Btn = (label, act, primary = true, style = '') =>
    `<button type="button" class="btn ${primary ? 'primary' : 'secondary'}" data-act="${act}" style="${style}">${T(label, 32, { c: primary ? 'on-gold' : 'text', w: 's', lines: 1 })}</button>`;
  const Pill = (text, color = 'gold') => `<span class="pill" style="--pc:var(--${color})">${T(text, 22, { c: color, w: 's', lines: 1 })}</span>`;
  const sp = (h, w) => `<div style="flex:none;${h != null ? `height:${dp(h)};` : ''}${w != null ? `width:${dp(w)};` : ''}"></div>`;
  const clock = (sec) => `${Math.floor(sec / 60)}분 ${sec % 60}초`;
  const etaText = (m) => m != null ? `약 ${m}분` : '계산 중';
  const LABEL = { WINDOW: '운전석 창문', READING_LIGHT: '독서등', TURN_SIGNAL: '깜빡이', STEERING_HEAT: '핸들 열선', SEAT_HEAT: '시트 열선', WIPER: '와이퍼', SEATBELT: '안전벨트', PASSENGER_WINDOW: '조수석 창문', REAR_WINDOW: '2열 창문' };
  const isWindow = (i) => i === 'WINDOW' || i === 'PASSENGER_WINDOW' || i === 'REAR_WINDOW';
  const short = (i) => isWindow(i) ? '창문' : LABEL[i];

  // ---------------------------------------------------------------- drawings (Visuals.kt)
  const rad = (d) => d * Math.PI / 180;
  const pol = (cx, cy, r, deg) => [cx + r * Math.cos(rad(deg)), cy + r * Math.sin(rad(deg))];
  const f2 = (n) => Math.round(n * 100) / 100;
  function arcD(cx, cy, r, start, sweep) {
    if (Math.abs(sweep) >= 360) sweep = 359.99 * Math.sign(sweep);
    const [x0, y0] = pol(cx, cy, r, start), [x1, y1] = pol(cx, cy, r, start + sweep);
    return `M${f2(x0)} ${f2(y0)}A${r} ${r} 0 ${Math.abs(sweep) > 180 ? 1 : 0} ${sweep >= 0 ? 1 : 0} ${f2(x1)} ${f2(y1)}`;
  }
  const svgText = (t, x, y, size, color, bold) =>
    `<text x="${f2(x)}" y="${f2(y)}" font-size="${f2(size)}" fill="${color}" font-weight="${bold ? 700 : 600}" text-anchor="middle" dominant-baseline="central">${esc(t)}</text>`;

  function LogoMark(size) {
    const g = 'g-lg';
    return `<svg viewBox="0 0 100 100" style="width:${size};height:${size};flex:none" aria-hidden="true"><defs><linearGradient id="${g}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${K.gold}"/><stop offset=".5" stop-color="${K.amber}"/><stop offset="1" stop-color="${K.gold}"/></linearGradient></defs>
      <circle cx="50" cy="50" r="46" fill="none" stroke="url(#${g})" stroke-width="5"/><circle cx="50" cy="44" r="10" fill="${K.gold}"/><path d="M45 47.5h10l3.5 23.5h-17z" fill="${K.gold}"/></svg>`;
  }
  function StarsSvg(n, size) {
    const pts = []; for (let k = 0; k < 10; k++) { const rr = k % 2 === 0 ? 46 : 46 * 0.45; pts.push(pol(50, 52, rr, -90 + k * 36).map(f2).join(',')); }
    const g = 'g-st';
    let out = `<span style="display:inline-flex;gap:calc(${size} * 0.2);flex:none" role="img" aria-label="별 ${n}개">`;
    for (let i = 0; i < 3; i++) {
      out += `<svg viewBox="0 0 100 100" style="width:${size};height:${size}"><defs><linearGradient id="${g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${K.gold}"/><stop offset="1" stop-color="${K.amber}"/></linearGradient></defs>
        <polygon points="${pts.join(' ')}" ${i < n ? `fill="url(#${g})"` : `fill="none" stroke="${K.faint}" stroke-width="4" stroke-linejoin="round"`}/></svg>`;
    }
    return out + '</span>';
  }
  const RecordChip = (label, r) => `<span class="chip">${T(label, 22, { c: 'gold', w: 's', lines: 1 })}${StarsSvg(r.stars, dp(20))}${T(clock(r.sec), 22, { c: 'sub', lines: 1 })}</span>`;
  function SocRing(soc, target, size) {
    const g = 'g-sr';
    const [ax, ay] = pol(50, 50, 34, -90 + 360 * target / 100), [bx, by] = pol(50, 50, 50, -90 + 360 * target / 100);
    return `<div style="position:relative;width:${size};height:${size};flex:none;display:flex;align-items:center;justify-content:center">
      <svg viewBox="0 0 100 100" class="fill" style="position:absolute;inset:0" aria-hidden="true"><defs><linearGradient id="${g}"><stop offset="0" stop-color="${K.gold}"/><stop offset="1" stop-color="${K.amber}"/></linearGradient></defs>
      <circle cx="50" cy="50" r="42" fill="none" stroke="${K.surface2}" stroke-width="8"/>
      ${soc > 0 ? `<path d="${arcD(50, 50, 42, -90, 360 * soc / 100)}" fill="none" stroke="url(#${g})" stroke-width="8" stroke-linecap="round"/>` : ''}
      <line x1="${f2(ax)}" y1="${f2(ay)}" x2="${f2(bx)}" y2="${f2(by)}" stroke="${K.gold}" stroke-width="2.4"/></svg>
      ${T(`${Math.trunc(soc)}%`, 40, { w: 'b', lines: 1, style: 'position:relative' })}</div>`;
  }
  const SocBar = (soc, target) => `<div style="position:relative;height:${dp(14)};min-height:6px;border-radius:99px;background:${K.surface2}">
    <div style="position:absolute;left:0;top:0;bottom:0;width:${Math.min(100, Math.max(0, soc))}%;border-radius:99px;background:linear-gradient(90deg,${K.gold},${K.amber})"></div>
    <div style="position:absolute;left:${target}%;top:-40%;bottom:-40%;width:max(2px, ${dp(3.5)});background:${K.text};transform:translateX(-50%)"></div></div>`;
  function BonusRing(left, total, size) {
    const out = left <= 0;
    return `<div style="position:relative;width:${size};height:${size};flex:none;display:flex;align-items:center;justify-content:center">
      <svg viewBox="0 0 100 100" class="fill" style="position:absolute;inset:0" aria-hidden="true"><circle cx="50" cy="50" r="40" fill="none" stroke="${K.surface2}" stroke-width="10"/>
      ${left > 0 ? `<path d="${arcD(50, 50, 40, -90, 360 * left / Math.max(1, total))}" fill="none" stroke="${K.amber}" stroke-width="10" stroke-linecap="round"/>` : ''}</svg>
      ${T(out ? '끝' : String(left), 26, { c: out ? 'faint' : 'amber', w: 'b', lines: 1, style: 'position:relative' })}</div>`;
  }
  function InputIcon(input, size, color = K.gold) {
    return `<svg viewBox="0 0 100 100" style="width:${size};height:${size};flex:none;overflow:visible" aria-hidden="true">${iconBody(input, color)}</svg>`;
  }
  function iconBody(input, color) {
    const st = `fill="none" stroke="${color}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"`;
    const heat = (x0, y0, h) => [0, 1, 2].map((k) => { const x = x0 + k * 16; return `<path d="M${x} ${y0}C${x - 6} ${f2(y0 - h * 0.33)} ${x + 6} ${f2(y0 - h * 0.66)} ${x} ${y0 - h}" ${st}/>`; }).join('');
    let b = '';
    switch (input) {
      case 'WINDOW': case 'PASSENGER_WINDOW': case 'REAR_WINDOW':
        b = `<path d="M15 62L32 20H85V62Z" ${st}/><path d="M55 68V90M45 80L55 90L65 80" ${st}/>`; break;
      case 'READING_LIGHT':
        b = `<path d="M25 37A25 25 0 0 1 75 37Z" fill="${color}"/><path d="M35 50L20 88M50 50V88M65 50L80 88" ${st}/>`; break;
      case 'TURN_SIGNAL':
        b = `<path d="M8 50L40 24V76Z" fill="${color}"/><path d="M92 50L60 24V76Z" fill="${color}"/>`; break;
      case 'STEERING_HEAT':
        b = `<circle cx="50" cy="60" r="30" ${st}/><circle cx="50" cy="60" r="7" fill="${color}"/><path d="M20 60H80M50 60V90" ${st}/>${heat(34, 24, 18)}`; break;
      case 'SEAT_HEAT':
        b = `<path d="M22 12L30 62H72V78H28" ${st}/>${heat(48, 48, 30)}`; break;
      case 'WIPER':
        b = `<path d="${arcD(50, 62, 42, 200, 140)}" ${st}/><path d="M50 82L28 30" ${st}/><circle cx="50" cy="82" r="6" fill="${color}"/>`; break;
      case 'SEATBELT':
        b = `<path d="M20 12L56 56" ${st}/><rect x="46" y="52" width="36" height="28" rx="6" ${st}/><path d="M64 80V94" ${st}/>`; break;
      // sticker-only glyphs (MissionKind with no car part of its own)
      case 'MEMORY': b = `<path d="M8 50Q50 12 92 50Q50 88 8 50Z" ${st}/><circle cx="50" cy="50" r="13" fill="${color}"/>`; break;
      case 'RIDDLE': b = `<path d="M34 34Q34 14 50 14Q68 14 68 32Q68 46 50 52V64" ${st}/><circle cx="50" cy="84" r="7" fill="${color}"/>`; break;
      case 'CHAIN': b = `<rect x="8" y="36" width="50" height="28" rx="14" ${st}/><rect x="42" y="36" width="50" height="28" rx="14" ${st}/>`; break;
      case 'LIGHTNING': b = `<path d="M60 6L20 56H48L38 94L80 40H52Z" fill="${color}"/>`; break;
    }
    return b;
  }

  /** Top-down car, nose right, driver on top. The part [input] uses glows gold. */
  function CarMap(input, width, flash) {
    const w = 200, h = 100;
    const faint = 'rgba(133,144,163,0.55)';
    const g = 'g-cm';
    const glow = (x, y, r) => `<circle cx="${f2(x)}" cy="${f2(y)}" r="${f2(r)}" fill="url(#${g})"/>`;
    const gl = (x1, y1, x2, y2, wd) => `<line x1="${f2(x1)}" y1="${f2(y1)}" x2="${f2(x2)}" y2="${f2(y2)}" stroke="${K.gold}" stroke-width="${wd}" stroke-linecap="round"/>`;
    let s = `<defs><radialGradient id="${g}"><stop offset="0" stop-color="${K.gold}" stop-opacity=".45"/><stop offset="1" stop-color="${K.gold}" stop-opacity="0"/></radialGradient></defs>`;
    for (const x of [0.16, 0.7]) for (const y of [0, 0.82]) s += `<rect x="${w * x}" y="${h * y}" width="${w * 0.14}" height="${h * 0.18}" rx="5" fill="${K.faint}"/>`;
    s += `<rect x="4" y="8" width="192" height="84" rx="30" fill="${K.surface2}" stroke="${K.sub}" stroke-width="2.5"/>`;
    s += `<path d="M132 20L152 26V74L132 80Z" fill="${K.surface}"/><path d="M32 24L20 30V70L32 76Z" fill="${K.surface}"/>`;
    for (const y of [0.2, 0.56]) for (const x of [0.44, 0.24]) s += `<rect x="${w * x}" y="${h * y}" width="${w * 0.12}" height="${h * 0.24}" rx="5" fill="none" stroke="${faint}" stroke-width="2"/>`;
    s += `<circle cx="120" cy="32" r="10" fill="none" stroke="${faint}" stroke-width="2"/>`;
    switch (input) {
      case 'WINDOW': s += glow(106, 8, 40) + gl(84, 8, 128, 8, 7); break;
      case 'READING_LIGHT': s += glow(104, 50, 42) + `<circle cx="104" cy="50" r="8" fill="${K.gold}"/>`; break;
      case 'TURN_SIGNAL': for (const [x, y] of [[190, 16], [190, 84], [10, 16], [10, 84]]) s += glow(x, y, 24) + `<circle cx="${x}" cy="${y}" r="6" fill="${K.gold}"/>`; break;
      case 'STEERING_HEAT': s += glow(120, 32, 36) + `<circle cx="120" cy="32" r="10" fill="none" stroke="${K.gold}" stroke-width="3.5"/>`; break;
      case 'SEAT_HEAT': s += glow(100, 32, 40) + `<rect x="88" y="20" width="24" height="24" rx="5" fill="${K.gold}"/>`; break;
      case 'WIPER': s += glow(144, 50, 45) + gl(144, 74, 138, 36, 4) + gl(144, 50, 138, 18, 4); break;
      case 'PASSENGER_WINDOW': s += glow(106, 92, 40) + gl(84, 92, 128, 92, 7); break;
      case 'REAR_WINDOW': for (const y of [8, 92]) s += glow(60, y, 30) + gl(44, y, 76, y, 7); break;
      case 'SEATBELT': for (const y of [0.2, 0.56]) for (const x of [0.44, 0.24]) s += glow(w * (x + 0.06), h * (y + 0.12), 20) + gl(w * x, h * y, w * (x + 0.12), h * (y + 0.24), 4); break;
    }
    return `<svg class="carmap${flash ? ' flash' : ''}" viewBox="0 0 200 100" style="width:${width};height:calc(${width} * 0.5);flex:none;overflow:visible" aria-hidden="true">${s}</svg>`;
  }

  // ---------------------------------------------------------------- 마이 차고 drawings: IONIQ 9 side view, nose right
  const hex = (rgb) => '#' + (rgb & 0xFFFFFF).toString(16).padStart(6, '0');
  const STICKER_INPUT = { WINDOW: 'WINDOW', MORSE: 'READING_LIGHT', SIGNALS: 'TURN_SIGNAL', STEERING_HEAT: 'STEERING_HEAT', SEAT_HEAT: 'SEAT_HEAT',
    WIPER: 'WIPER', SEATBELT: 'SEATBELT', TWIN_WINDOW: 'PASSENGER_WINDOW', MEMORY: 'MEMORY', RIDDLE: 'RIDDLE', CHAIN: 'CHAIN', LIGHTNING: 'LIGHTNING' };
  const STICKER_COLOR = { WINDOW: '#7FC8F8', MORSE: '#FFD166', SIGNALS: '#FF9F43', STEERING_HEAT: '#FF6B6B', SEAT_HEAT: '#F78FB3', WIPER: '#4ECDC4',
    MEMORY: '#B39DFF', RIDDLE: '#9BE15D', CHAIN: '#5EC2B7', SEATBELT: '#F4A261', TWIN_WINDOW: '#74B9FF', LIGHTNING: '#FFE066' };
  const SLOT_NAME = { FRONT_DOOR: '앞문', REAR_DOOR: '뒷문', TRUNK: '트렁크' };
  /** Round sticker with its mission glyph, centered at (x, y) in the caller's units. */
  const stickerSvg = (kind, x, y, r) => `<circle cx="${x}" cy="${y}" r="${r}" fill="${STICKER_COLOR[kind]}" stroke="#FFFFFF" stroke-width="${f2(r * 0.16)}"/>
    <g transform="translate(${f2(x - r * 0.6)} ${f2(y - r * 0.6)}) scale(${f2(r * 1.2 / 100)})">${iconBody(STICKER_INPUT[kind], '#1A1305')}</g>`;
  function wheelSvg(id, cx, cy) {
    let s = `<circle cx="${cx}" cy="${cy}" r="28" fill="#14171C"/><circle cx="${cx}" cy="${cy}" r="20" fill="#2A2F37"/>`;
    const spokes = (n, len, color, w) => Array.from({ length: n }, (_, i) => { const [x, y] = pol(cx, cy, len, -90 + i * 360 / n); return `<line x1="${cx}" y1="${cy}" x2="${f2(x)}" y2="${f2(y)}" stroke="${color}" stroke-width="${w}" stroke-linecap="round"/>`; }).join('');
    switch (id) {
      case 'BASIC': s += `<circle cx="${cx}" cy="${cy}" r="18" fill="none" stroke="#C9CED6" stroke-width="3"/>` + spokes(5, 17, '#C9CED6', 4.5); break;
      case 'STAR': { const pts = []; for (let k = 0; k < 10; k++) pts.push(pol(cx, cy, k % 2 ? 7.5 : 18, -90 + k * 36).map(f2).join(',')); s += `<polygon points="${pts.join(' ')}" fill="#E7EAEE"/>`; break; }
      case 'PINWHEEL': ['#FF9F43', '#4ECDC4', '#F78FB3', '#B39DFF'].forEach((c, i) => {
        const a = i * 90, [x1, y1] = pol(cx, cy, 18, a), [x2, y2] = pol(cx, cy, 18, a + 70);
        s += `<path d="M${cx} ${cy}L${f2(x1)} ${f2(y1)}Q${f2(x2)} ${f2(y2)} ${cx} ${cy}Z" fill="${c}"/>`;
      }); break;
      case 'GOLD': s += `<circle cx="${cx}" cy="${cy}" r="18.5" fill="none" stroke="#E2BE63" stroke-width="3.5"/>` + spokes(10, 17, '#E2BE63', 2.4); break;
    }
    return s + `<circle cx="${cx}" cy="${cy}" r="4.5" fill="#5B616B"/>`;
  }
  // The family's car: our own Blender model of the IONIQ 9 (tools/ioniq9), rendered white per wheel + a paint mask.
  // Paint = mask brightness x paint RGB / 0.70 over the mask (tools/ioniq9/sheets.py), composited once per look and cached.
  const CAR_VIEW = { side: [1200, 480], three_quarter: [1200, 800], rear_quarter: [1200, 800] };
  const CAR_SLOT = { side: { FRONT_DOOR: [0.6127, 0.5818], REAR_DOOR: [0.3873, 0.5818], TRUNK: [0.1145, 0.5] },
    three_quarter: { FRONT_DOOR: [0.4553, 0.5498], REAR_DOOR: [0.3215, 0.5177], TRUNK: [0.188, 0.4496] },
    rear_quarter: { FRONT_DOOR: [0.6786, 0.5178], REAR_DOOR: [0.5446, 0.5497], TRUNK: [0.3434, 0.549] } }; // assets/ioniq9_sticker_slots.json
  const carImgs = new Map();
  const loadImg = (src) => new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = src; });
  async function paintCar(base, mask, rgb) {
    const [bi, mi] = await Promise.all([loadImg(base), loadImg(mask)]);
    const c = document.createElement('canvas'); c.width = bi.naturalWidth; c.height = bi.naturalHeight;
    const x = c.getContext('2d', { willReadFrequently: true });
    x.drawImage(mi, 0, 0); const m = x.getImageData(0, 0, c.width, c.height).data;
    x.clearRect(0, 0, c.width, c.height); x.drawImage(bi, 0, 0);
    const img = x.getImageData(0, 0, c.width, c.height), d = img.data, pc = [(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255];
    for (let i = 0; i < d.length; i += 4) {
      const a = m[i + 3] / 255; if (!a) continue;
      const g = m[i] / 255 / 0.70;
      for (let k = 0; k < 3; k++) d[i + k] = Math.round(d[i + k] * (1 - a) + Math.min(255, g * pc[k]) * a);
    }
    x.putImageData(img, 0, 0);
    return c.toDataURL('image/png');
  }
  function carSrc(lk, view) {
    const base = `assets/ioniq9_${view}_base_${lk.wheel.toLowerCase()}.png`, key = `${view}|${lk.wheel}|${lk.paint}`;
    const hit = carImgs.get(key);
    if (typeof hit === 'string') return [hit, key];
    // the 3/4 PNGs (front for the garage, rear for the driving panel) only back the 3D views; once one draws (WebGL works) it stays hidden, so skip the per-pixel paint
    // ponytail: if WebGL is lost later, the fallback shows white until the next paint or wheel change
    if (!hit && !(view !== 'side' && document.querySelector('.pl-car.live, .car3d.live'))) {
      carImgs.set(key, true);
      paintCar(base, `assets/ioniq9_${view}_paint_mask.png`, CE.byId(CE.Paint, lk.paint).rgb).then((url) => {
        carImgs.set(key, url);
        for (const im of document.querySelectorAll('img[data-car]')) if (im.dataset.car === key) im.src = url;
      }, () => carImgs.set(key, base)); // file:// or a missing file: the white car stays
    }
    return [base, key];
  }
  /** The family's car: paint, wheels and up to three stickers. width = CSS width; height follows the render. */
  function CarSide(lk, width, label = '우리 차', view = 'side') {
    const [w, h] = CAR_VIEW[view], [src, key] = carSrc(lk, view);
    let st = '';
    for (const slot of CE.StickerSlot) { const k = lk.stickers[slot]; if (k) st += stickerSvg(k, f2(CAR_SLOT[view][slot][0] * w), f2(CAR_SLOT[view][slot][1] * h), view === 'side' ? 34 : 30); }
    return `<span class="carside" style="width:${width};max-width:100%;aspect-ratio:${w} / ${h};flex:none" role="img" aria-label="${esc(label)}">
      <img src="${src}" data-car="${key}" alt="" draggable="false">${st ? `<svg viewBox="0 0 ${w} ${h}" aria-hidden="true">${st}</svg>` : ''}</span>`;
  }
  /** One sticker as a standalone SVG (the 3D view turns it into a decal texture). */
  window.CarEscapeSticker = (kind) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">${stickerSvg(kind, 50, 50, 46)}</svg>`;

  const angleOf = (v) => 135 + 270 * Math.min(100, Math.max(0, v)) / 100;
  function DialView(v, unit) {
    const ok = Math.abs(v.current - v.target) <= v.tolerance;
    const c = 100, r = 96, tr = r * 0.8, tw = r * 0.08;
    const g = 'g-dl';
    let s = `<defs><radialGradient id="${g}"><stop offset="0" stop-color="${K.surface2}"/><stop offset=".5" stop-color="${K.surface}"/><stop offset="1" stop-color="${K.deep}"/></radialGradient></defs>`;
    s += `<circle cx="${c}" cy="${c}" r="${r}" fill="url(#${g})" stroke="${K.line}" stroke-width="${f2(r * 0.012)}"/>`;
    s += `<path d="${arcD(c, c, tr, 135, 270)}" fill="none" stroke="${K.deep}" stroke-width="${f2(tw)}" stroke-linecap="round"/>`;
    const lo = Math.max(0, v.target - v.tolerance), hi = Math.min(100, v.target + v.tolerance);
    s += `<path d="${arcD(c, c, tr, angleOf(lo), Math.max(270 * (hi - lo) / 100, 2))}" fill="none" stroke="${K.gold}" stroke-width="${f2(tw)}"/>`;
    for (let i = 0; i <= 100; i += 5) {
      const a = angleOf(i), major = i % 10 === 0;
      const [x0, y0] = pol(c, c, r * 0.9, a), [x1, y1] = pol(c, c, r * (major ? 0.98 : 0.95), a);
      s += `<line x1="${f2(x0)}" y1="${f2(y0)}" x2="${f2(x1)}" y2="${f2(y1)}" stroke="${major ? K.sub : K.faint}" stroke-width="${f2(r * (major ? 0.014 : 0.008))}"/>`;
    }
    for (const n of [0, 50, 100]) { const [x, y] = pol(c, c, r * 0.66, angleOf(n)); s += svgText(String(n), x, y, r * 0.1, K.sub); }
    if (!ok) {
      const from = angleOf(v.current), to = angleOf(v.target);
      const d = to - from, sweep = Math.abs(d) > 40 ? 40 * Math.sign(d) : d * 0.8;
      const ar = r * 0.5;
      if (Math.abs(sweep) > 0.5) {
        s += `<path d="${arcD(c, c, ar, from, sweep)}" fill="none" stroke="${K.amber}" stroke-width="${f2(r * 0.03)}" stroke-linecap="round"/>`;
        const end = from + sweep;
        const tip = pol(c, c, ar, end + Math.sign(sweep) * 6), p1 = pol(c, c, ar - r * 0.06, end), p2 = pol(c, c, ar + r * 0.06, end);
        s += `<path d="M${tip.map(f2).join(' ')}L${p1.map(f2).join(' ')}L${p2.map(f2).join(' ')}Z" fill="${K.amber}"/>`;
      }
    }
    const a = angleOf(v.current), needle = ok ? K.gold : K.amber;
    const [nx, ny] = pol(c, c, r * 0.52, a), [kx, ky] = pol(c, c, tr, a);
    s += `<line x1="${c}" y1="${c}" x2="${f2(nx)}" y2="${f2(ny)}" stroke="${needle}" stroke-width="${f2(r * 0.035)}" stroke-linecap="round"/>`;
    s += `<circle cx="${f2(kx)}" cy="${f2(ky)}" r="${f2(tw * 0.75)}" fill="${needle}"/><circle cx="${f2(kx)}" cy="${f2(ky)}" r="${f2(tw * 0.3)}" fill="${K.deep}"/>`;
    s += `<circle cx="${c}" cy="${c}" r="${f2(r * 0.07)}" fill="${needle}"/><circle cx="${c}" cy="${c}" r="${f2(r * 0.03)}" fill="${K.deep}"/>`;
    s += svgText(`${unit} ${v.current}%`.trim(), 100, 167, 15, ok ? K.gold : K.text, true) + svgText(`목표 ${v.target}%`, 100, 184, 8.4, K.gold);
    return `<div class="vis grow" style="width:100%"><svg class="sq" viewBox="0 0 200 200" role="img" aria-label="${esc(`${unit} ${v.current}%, 목표 ${v.target}%`)}">${s}</svg></div>`;
  }

  function MorseView(v, land) {
    const lit = v.entered.length > 0;
    const g = 'g-mg', g2 = 'g-mh';
    const lamp = `<div class="vis grow" style="${land ? 'width:100%' : 'height:100%'}"><svg class="sq" viewBox="0 0 100 100" aria-hidden="true">
      <defs><radialGradient id="${g}"><stop offset="0" stop-color="${K.amber}" stop-opacity="${lit ? 0.45 : 0.18}"/><stop offset="1" stop-color="${K.amber}" stop-opacity="0"/></radialGradient>
      <radialGradient id="${g2}"><stop offset="0" stop-color="#FFE2A8"/><stop offset=".55" stop-color="${K.amber}"/><stop offset="1" stop-color="#8A4B14"/></radialGradient></defs>
      <circle cx="50" cy="50" r="45" fill="url(#${g})"/><circle cx="50" cy="50" r="18.9" fill="url(#${g2})"/><circle cx="50" cy="50" r="22.5" fill="none" stroke="${K.line}" stroke-width="1.35"/></svg></div>`;
    const n = Math.max(1, v.pattern.length), slot = 198, hgt = 45;
    let marks = '';
    [...v.pattern].forEach((ch, i) => {
      const cx = slot * (i + 0.5), cy = 45, done = i < v.entered.length;
      const col = done && v.entered[i] === ch ? K.gold : done ? K.amber : i === v.entered.length ? K.text : K.faint;
      const paint = done ? `fill="${col}"` : `fill="none" stroke="${col}" stroke-width="${f2(hgt * 0.16)}"`;
      if (ch === '-') { const w = slot * 0.66; marks += `<rect x="${f2(cx - w / 2)}" y="${cy - hgt / 2}" width="${f2(w)}" height="${hgt}" rx="${hgt / 2}" ${paint}/>`; }
      else marks += `<circle cx="${f2(cx)}" cy="${cy}" r="${f2(hgt * 0.6)}" ${paint}/>`;
    });
    const signals = `<div class="col" style="align-items:center;justify-content:center;${land ? 'width:100%' : 'flex:1 1 0;min-width:0'}">
      <svg viewBox="-12 0 ${n * slot + 24} 90" style="height:${dp(90)};min-height:40px;max-width:100%" role="img" aria-label="${esc(`신호 ${v.entered.length} / ${v.pattern.length}`)}">${marks}</svg>
      ${sp(12)}${T(`점은 짧게, 선은 길게 · ${v.entered.length}\u00a0/\u00a0${v.pattern.length}`, 26, { c: 'sub', align: 'center' })}</div>`;
    return land ? `<div class="col grow" style="width:100%;align-items:center">${lamp}${sp(24)}${signals}</div>`
      : `<div class="row grow" style="width:100%">${lamp}${signals}</div>`;
  }

  function chevron(left, color) {
    const s = left ? -1 : 1;
    return `<path d="M${50 - s * 12} 26L${50 + s * 14} 50L${50 - s * 12} 74" fill="none" stroke="${color}" stroke-width="9" stroke-linecap="round" stroke-linejoin="round"/>`;
  }
  function SequenceView(v, memory = false) {
    const next = v.target[v.entered.length];
    const n = Math.max(1, v.target.length);
    let chips = '';
    v.target.forEach((sy, i) => {
      const done = i < v.entered.length, right = v.entered[i] === sy, cur = i === v.entered.length;
      const [fill, edge, mark] = done && right ? [K.gold, K.gold, K.onGold] : done ? [K.amber, K.amber, K.onGold] : cur ? [K.surface2, K.gold, K.text] : [K.surface, K.line, 'rgba(133,144,163,0.6)'];
      const body = sy === '?' ? svgText('?', 50, 52, 50, mark, true) : chevron(sy.toUpperCase().startsWith('L'), mark);
      chips += `<svg viewBox="0 0 100 100" class="${cur ? 'cur' : ''}" aria-hidden="true"><rect x="${cur ? 3 : 1}" y="${cur ? 3 : 1}" width="${cur ? 94 : 98}" height="${cur ? 94 : 98}" rx="20" fill="${fill}"/>
        <rect x="${cur ? 3 : 1}" y="${cur ? 3 : 1}" width="${cur ? 94 : 98}" height="${cur ? 94 : 98}" rx="20" fill="none" stroke="${edge}" stroke-width="${cur ? 6 : 2}" ${cur ? 'class="pulse"' : ''}/>${body}</svg>`;
    });
    const label = v.target.map((x) => x === '?' ? '?' : x === 'L' ? '왼쪽' : '오른쪽').join(', ');
    return `<div class="vis grow" style="width:100%;--chip:min(${dp(200)}, calc(100cqw / ${n * 1.18 + 0.15}), 42cqh)">
      <div class="seq-row" role="img" aria-label="${esc(label)}">${chips}</div>${sp(28)}
      ${next != null && !memory ? T(`다음: ${next.toUpperCase().startsWith('L') ? '왼쪽' : '오른쪽'}`, 36, { c: 'gold', w: 'b', lines: 1 }) : ''}
      ${T(`${v.entered.length} / ${v.target.length}`, 28, { c: 'sub' })}</div>`;
  }

  function LevelView(v, input) {
    const max = Math.max(1, v.max);
    const unit = input === 'WIPER' ? '번' : '단';
    let cells = '';
    for (let i = 0; i < max; i++) {
      const on = i < v.current, target = i === v.target - 1;
      const t = (i + 1) / max;
      const bg = on ? `background:color-mix(in srgb, ${K.amber} ${Math.round(t * 100)}%, ${K.gold})` : '';
      cells += `<div class="cell ${on ? 'on' : target ? 'tgt' : 'off'}" style="${bg}">${T(target ? `${i + 1}${unit} 목표` : String(i + 1), 34, { c: on ? 'on-gold' : target ? 'gold' : 'faint', w: on || target ? 'b' : 's', lines: 1 })}</div>`;
    }
    return `<div class="col grow" style="width:100%;align-items:center">
      <div class="vis grow" style="width:100%;flex-direction:row;padding:${dp(8)} 0">
        ${input ? InputIcon(input, dp(120), v.current > 0 ? K.amber : K.sub) + sp(null, 40) : ''}
        <div class="gauge" role="img" aria-label="${esc(`현재 ${v.current}${unit}, 목표 ${v.target}${unit}`)}">${cells}</div></div>
      ${sp(16)}${T(`현재 ${v.current}${unit} · 목표 ${v.target}${unit}`, 30, { c: v.current === v.target ? 'gold' : 'sub', w: 's', align: 'center' })}</div>`;
  }

  function MemoryView(v) {
    const showing = v.revealLeftMs > 0;
    const shown = showing ? v.target : v.entered.concat(v.target.slice(v.entered.length));
    return `<div class="col grow" style="width:100%;align-items:center">
      ${Pill(showing ? `잘 보고 외워요 · ${Math.floor((v.revealLeftMs + 999) / 1000)}초` : '이제 똑같이 켜 보세요', showing ? 'amber' : 'gold')}${sp(12)}
      ${SequenceView({ target: shown, entered: showing ? [] : v.entered }, true)}</div>`;
  }

  function RiddleView(v, input) {
    const wiper = input === 'WIPER';
    const unit = wiper ? '번' : isWindow(input) ? '칸' : '단';
    const n = Math.max(1, v.max);
    let cells = '';
    for (let i = 0; i < n; i++) cells += `<i class="${i < v.current ? 'on' : ''}"></i>`;
    return `<div class="col grow" style="width:100%;align-items:center;justify-content:center">
      <div class="card hl" style="width:100%;padding:${dp(32)};display:flex;flex-direction:column;align-items:center">${Pill('수수께끼')}${sp(16)}${T(v.question, 38, { w: 's', align: 'center', lines: 4 })}</div>
      ${sp(32)}${wiper ? '' : `<div style="width:100%;container-type:inline-size"><div class="cells" style="--cell:min(80px, calc(100cqw / ${n * 1.25}))">${cells}</div></div>`}
      ${sp(20)}${T(isWindow(input) ? `10칸 중 지금 ${v.current}칸` : `지금 ${v.current}${unit}`, 34, { c: 'gold', w: 'b', lines: 1 })}</div>`;
  }

  function ChainView(v, input, land) {
    const chips = v.chips.map((c, i) => {
      const done = i < v.done, cur = i === v.done;
      return `<div class="${done ? 'done' : cur ? 'cur' : ''}">${T(`${i + 1}. ${c}`, 24, { c: done ? 'on-gold' : cur ? 'text' : 'faint', w: 's', align: 'center', lines: 2 })}</div>`;
    }).join('');
    return `<div class="col grow" style="width:100%;align-items:center"><div class="chain">${chips}</div>${sp(20)}
      <div class="col grow" style="width:100%">${visualView(v.current, input, land)}</div></div>`;
  }

  const SEAT_AT = { ROW_1_LEFT: [0.3, 0.3], ROW_1_RIGHT: [0.7, 0.3], ROW_2_LEFT: [0.26, 0.53], ROW_2_CENTER: [0.5, 0.53], ROW_2_RIGHT: [0.74, 0.53], ROW_3_LEFT: [0.32, 0.76], ROW_3_RIGHT: [0.68, 0.76] };
  function SeatsView(v) {
    const bw = 620, bh = 1000;
    const next = v.ordered ? v.target[v.done.length] : null;
    const cx = bw / 2, cy = bh * 0.17, rx = bw * 0.38, ry = bh * 0.11;
    const e0 = [cx + rx * Math.cos(rad(200)), cy + ry * Math.sin(rad(200))], e1 = [cx + rx * Math.cos(rad(340)), cy + ry * Math.sin(rad(340))];
    let s = `<rect x="3" y="3" width="${bw - 6}" height="${bh - 6}" rx="${bw * 0.28}" fill="${K.surface2}" stroke="${K.sub}" stroke-width="${bw * 0.012}"/>`;
    s += `<path d="M${f2(e0[0])} ${f2(e0[1])}A${rx} ${ry} 0 0 1 ${f2(e1[0])} ${f2(e1[1])}" fill="none" stroke="${K.surface}" stroke-width="${bw * 0.04}"/>`;
    s += svgText('앞', bw / 2, bh * 0.06, bw * 0.09, K.sub);
    for (const seat of CE.CarSeat) {
      const [fx, fy] = SEAT_AT[seat];
      const sw = bw * (seat === 'ROW_2_CENTER' ? 0.16 : 0.2), sh = bh * 0.15;
      const x = bw * fx - sw / 2, y = bh * fy - sh / 2, rr = sw * 0.22;
      const done = v.done.includes(seat), idx = v.target.indexOf(seat);
      if (done) s += `<rect x="${f2(x)}" y="${f2(y)}" width="${f2(sw)}" height="${sh}" rx="${f2(rr)}" fill="${K.gold}"/>`;
      else if (idx >= 0) s += `<rect x="${f2(x)}" y="${f2(y)}" width="${f2(sw)}" height="${sh}" rx="${f2(rr)}" fill="rgba(232,185,98,0.14)"/>
        <rect x="${f2(x)}" y="${f2(y)}" width="${f2(sw)}" height="${sh}" rx="${f2(rr)}" fill="none" stroke="${K.gold}" stroke-width="${seat === next ? bw * 0.018 : bw * 0.01}" stroke-dasharray="${bw * 0.025} ${bw * 0.018}" ${seat === next ? 'class="pulse"' : ''}/>`;
      else s += `<rect x="${f2(x)}" y="${f2(y)}" width="${f2(sw)}" height="${sh}" rx="${f2(rr)}" fill="none" stroke="rgba(133,144,163,0.6)" stroke-width="${bw * 0.008}"/>`;
      if (v.buckled.includes(seat) && !done) s += `<line x1="${f2(x + sw * 0.2)}" y1="${f2(y + sh * 0.15)}" x2="${f2(x + sw * 0.8)}" y2="${f2(y + sh * 0.85)}" stroke="${K.sub}" stroke-width="${bw * 0.014}" stroke-linecap="round"/>`;
      const mark = done ? '찰칵' : v.ordered && idx >= 0 ? String(idx + 1) : null;
      if (mark) s += svgText(mark, x + sw / 2, y + sh / 2, done ? Math.min(sh * 0.34, sw * 0.27) : Math.min(sh * 0.5, sw * 0.55), done ? K.onGold : K.gold, true);
    }
    return `<div class="col grow" style="width:100%;align-items:center"><div class="vis grow" style="width:100%">
      <svg class="fill" viewBox="0 0 ${bw} ${bh}" role="img" aria-label="${esc(`좌석 지도, 찰칵 ${v.done.length} / ${v.need}`)}">${s}</svg></div>${sp(16)}
      ${next ? T(`다음: ${CE.seatName(next)}`, 34, { c: 'gold', w: 'b', lines: 1 }) : ''}${T(`찰칵 ${v.done.length} / ${v.need}`, 28, { c: 'sub', lines: 1 })}</div>`;
  }

  function TwinView(v, input) {
    const inRange = v.left >= v.min && v.left <= v.max && v.right >= v.min && v.right <= v.max;
    const ok = inRange && Math.abs(v.left - v.right) <= v.tolerance;
    const other = input === 'REAR_WINDOW' ? '2열' : '조수석';
    const Wd = 400, H = 300, pw = Math.min(Wd * 0.34, H * 0.5), top = H * 0.1, ph = H * 0.78, gap = pw * 0.5, x0 = (Wd - pw * 2 - gap) / 2;
    const g = 'g-tw';
    const ys = [v.left, v.right].map((p) => top + ph * Math.min(100, Math.max(0, p)) / 100);
    let s = `<defs><linearGradient id="${g}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3A5A86"/><stop offset="1" stop-color="#1C2E4A"/></linearGradient></defs>`;
    [v.left, v.right].forEach((pct, i) => {
      const x = x0 + i * (pw + gap), cr = pw * 0.1;
      s += `<rect x="${f2(x)}" y="${top}" width="${f2(pw)}" height="${ph}" rx="${f2(cr)}" fill="${K.deep}"/>`;
      s += `<rect x="${f2(x)}" y="${f2(top + ph * v.min / 100)}" width="${f2(pw)}" height="${f2(ph * (v.max - v.min) / 100)}" fill="rgba(232,185,98,0.10)"/>`;
      if (top + ph - ys[i] > 0.5) s += `<rect x="${f2(x)}" y="${f2(ys[i])}" width="${f2(pw)}" height="${f2(top + ph - ys[i])}" rx="${f2(Math.min(cr, (top + ph - ys[i]) / 2))}" fill="url(#${g})"/>`;
      s += `<rect x="${f2(x)}" y="${top}" width="${f2(pw)}" height="${ph}" rx="${f2(cr)}" fill="none" stroke="${K.sub}" stroke-width="${f2(pw * 0.03)}"/>`;
      s += `<line x1="${f2(x)}" y1="${f2(ys[i])}" x2="${f2(x + pw)}" y2="${f2(ys[i])}" stroke="${ok ? K.gold : K.amber}" stroke-width="${f2(pw * 0.05)}" stroke-linecap="round"/>`;
      s += svgText(i === 0 ? '운전석' : other, x + pw / 2, top / 2, Math.min(top * 0.6, pw * 0.16), K.sub, true);
      s += svgText(`${pct}%`, x + pw / 2, top + ph + top * 0.6, Math.min(top * 0.7, pw * 0.18), ok ? K.gold : K.text, true);
    });
    s += `<line x1="${f2(x0 + pw)}" y1="${f2(ys[0])}" x2="${f2(x0 + pw + gap)}" y2="${f2(ys[1])}" stroke="${ok ? K.gold : K.amber}" stroke-width="${f2(pw * 0.025)}" stroke-linecap="round" ${ok ? '' : `stroke-dasharray="${f2(pw * 0.06)} ${f2(pw * 0.04)}"`}/>`;
    const msg = ok ? '똑같아요! 그대로 잠깐 기다려요' : !inRange ? `두 창문 모두 ${v.min}%에서 ${v.max}% 사이로` : `높이 차이 ${Math.abs(v.left - v.right)}%`;
    return `<div class="col grow" style="width:100%;align-items:center"><div class="vis grow" style="width:100%">
      <svg class="fill" viewBox="0 0 ${Wd} ${H + 10}" role="img" aria-label="${esc(`운전석 ${v.left}%, ${other} ${v.right}%`)}">${s}</svg></div>${sp(16)}
      ${T(msg, 30, { c: ok ? 'gold' : 'sub', w: 's', lines: 1 })}</div>`;
  }

  function visualView(v, input, land) {
    if (!v) return '<div class="grow"></div>';
    switch (v.type) {
      case 'Dial': return DialView(v, input ? short(input) : '');
      case 'Morse': return MorseView(v, land);
      case 'Sequence': return SequenceView(v);
      case 'Level': return LevelView(v, input);
      case 'Memory': return MemoryView(v);
      case 'Riddle': return RiddleView(v, input);
      case 'Chain': return ChainView(v, input, land);
      case 'Seats': return SeatsView(v);
      case 'Twin': return TwinView(v, input);
    }
  }

  // ---------------------------------------------------------------- screens (Screens.kt)
  const gameEl = document.getElementById('game');
  const dims = { s: 0.6, land: true, compact: false };
  let dailyMode = false;
  let logConfirm = false;
  const episodes = CE.Episodes.all.map((e, i) => ({ n: i + 1, id: e.id, title: e.title, minutes: e.minutes, blurb: e.intro, puzzles: e.puzzles.length,
    inputs: [...new Set(e.puzzles.flatMap((p) => CE.inputs(p.spec)))] }));
  function recommended(eta) {
    const byLen = episodes.slice().sort((a, b) => a.minutes - b.minutes);
    if (eta != null) { const f = byLen.filter((e) => e.minutes <= eta); if (f.length) return f[f.length - 1]; }
    return byLen[0];
  }

  function TitleScreen(ui) {
    const d = dims;
    // Brand on top, then the family's car as the hero: the garage's 3D view (drag to turn), its render PNG underneath.
    const brand = `<div class="col" style="align-items:${d.land ? 'flex-start' : 'center'};flex:none">
      <div class="row" style="gap:${dp(20)};max-width:100%">${d.compact ? '' : LogoMark(dp(d.land ? 88 : 72))}<div class="col" style="min-width:0;align-items:${d.land ? 'flex-start' : 'center'}">
        <h1 style="margin:0;font:inherit;max-width:100%">${T('카 이스케이프', d.compact ? 72 : d.land ? 88 : 80, { w: 'b', lines: 1 })}</h1>
        ${T('아이오닉 9 가족 방탈출', 30, { c: 'gold', w: 's', lines: 1 })}</div></div>${sp(d.compact ? 8 : 16)}
      ${T('충전하는 동안, 우리 차가 방탈출 게임이 됩니다', d.compact ? 28 : 32, { c: 'sub', align: d.land ? null : 'center' })}</div>`;
    const hero = `<div class="vis gstage deco hero${d.land ? ' grow' : ''}" style="width:100%;${d.land ? '' : `height:${dp(228)};min-height:140px;flex:none`}">
      <div class="car3d" data-car3d="garage" aria-label="우리 차 · 끌어서 돌려 보기">${CarSide(look, 'min(100cqw, 150cqh)', '우리 차', 'three_quarter')}</div></div>
      ${sp(8)}${Btn('우리 차 꾸미기 ›', 'garage', false, 'align-self:center;flex:none')}`;
    const recs = episodes.filter((e) => progress.best[e.id]).map((e) => RecordChip(`${e.minutes}분`, progress.best[e.id])).join('');
    const charge = `<div class="row">${SocRing(ui.socPercent, ui.targetPercent, dp(170))}${sp(null, 36)}<div class="col" style="min-width:0">
      ${T(`현재 ${Math.trunc(ui.socPercent)}% → 목표 ${ui.targetPercent}%`, 30, { w: 's' })}${sp(8)}${T(`예상 남은 시간 ${etaText(ui.chargeEtaMinutes)}`, 28, { c: 'sub' })}</div></div>`;
    const records = recs || progress.badges.length ? `${sp(d.compact ? 16 : 28)}${T('최고 기록', 24, { c: 'sub', w: 's' })}${sp(8)}
      <div class="flow">${recs}${progress.badges.length ? Pill(`배지 ${progress.badges.length} / ${CE.KINDS.length}`, 'amber') : ''}</div>` : '';
    const box = 'width:100%';
    // 가족 충전 일지: count, the latest rows, and a delete button that asks for a second tap.
    const log = progress.log;
    const logView = `${sp(d.compact ? 16 : 28)}<div class="row" style="gap:${dp(16)}">${T(adventure(log.length + 1), 26, { c: 'gold', w: 's', lines: 1, style: 'flex:1 1 0' })}
      ${log.length ? Btn(logConfirm ? '한 번 더 누르면 지워요' : '일지 지우기', 'clearlog', false, 'flex:none') : ''}</div>
      ${d.compact ? '' : log.slice(-3).reverse().map((r) => sp(6) + T(logLine(r), 22, { c: 'sub', lines: 1 })).join('')}`;
    const panel = `<div class="card" style="padding:${dp(44)};${box}">
      <div data-r="charge">${charge}</div>${sp(d.compact ? 24 : 40)}
      <div class="row" style="gap:${dp(20)}">${Btn('시작', 'start', true, 'flex:1 1 0')}${Btn('오늘의 금고', 'today', false, 'flex:1 1 0')}</div>${records}${logView}</div>`;
    // Charge port just opened: one card for the next 화. One tap starts it or closes it; other episodes stay behind 시작.
    const n = ui.readyEpisode, ep = episodes[n - 1];
    const readyEta = ui.chargeEtaMinutes != null ? sp(8) + T(`남은 충전 약 ${ui.chargeEtaMinutes}분`, 30, { c: 'gold', w: 's', lines: 1 }) : '';
    const card = `<div class="card hl" style="padding:${dp(44)};${box}">${Pill('충전구 열림')}${sp(20)}${T(`${n}화가 준비됐어요`, 60, { w: 'b', lines: 1 })}
      <div data-r="ready">${readyEta}</div>${ep ? sp(16) + T(ep.title, 34, { c: 'sub', lines: 2 }) : ''}${sp(d.compact ? 24 : 40)}
      <div class="row" style="gap:${dp(20)}">${Btn(`${n}화 시작`, 'ready', true, 'flex:1 1 0')}${Btn('닫기', 'dismiss', false, 'flex:1 1 0')}</div></div>`;
    const right = n > 0 ? card : panel;
    return { key: 'title', html: d.land
      ? `<div class="scr" style="flex-direction:row;align-items:stretch;height:100%;gap:${dp(56)}"><div class="col" style="flex:1.25 1 0;min-width:0">${brand}${sp(8)}${hero}</div>
          <div class="col" style="flex:1 1 0;min-width:0;justify-content:center">${right}</div></div>`
      : `<div class="scr" style="align-items:center">${brand}${sp(8)}${hero}${sp(24)}${right}</div>`,
    parts: n > 0 ? { ready: readyEta } : { charge } };
  }

  function MatchingScreen(ui) {
    const d = dims, eta = ui.chargeEtaMinutes, rec = recommended(eta);
    const head = `${dailyMode ? `<div class="row">${Pill('오늘의 금고', 'amber')}${sp(null, 16)}${T('오늘은 온 가족이 같은 문제를 풀어요', 26, { c: 'sub', lines: 1 })}</div>${sp(16)}` : ''}
      ${T('충전 시간에 맞는 에피소드', 28, { c: 'gold', w: 's' })}${sp(12)}${T(`남은 충전 시간 ${etaText(eta)}`, 52, { w: 'b' })}
      ${rec ? T(`→ ${rec.minutes}분 에피소드를 추천합니다`, 40, { c: 'gold', w: 's' }) : ''}`;
    const card = (e) => {
      const isRec = e === rec, tooLong = eta != null && e.minutes > eta, best = progress.best[e.id];
      const op = tooLong ? 'opacity:.7;' : '';
      if (!d.land) return `<button type="button" class="card${isRec ? ' hl' : ''}" data-act="pick:${e.id}" style="${op}">
        <div class="row" style="padding:${dp(32)}">${T(`${e.minutes}분`, 60, { c: isRec ? 'gold' : 'text', w: 'b', lines: 1, style: 'flex:none' })}${sp(null, 28)}
        <div class="col grow">${T(`${e.n}화 · ${e.title}`, 34, { w: 's', lines: 1 })}${sp(8)}<div class="row" style="gap:${dp(10)}">${isRec ? Pill('추천') : ''}
        ${tooLong ? Pill('충전 시간보다 길어요', 'faint') : best ? RecordChip('최고', best) : T(`퍼즐 ${e.puzzles}개`, 26, { c: 'faint', lines: 1 })}</div></div>
        ${T('›', 48, { c: isRec ? 'gold' : 'text', w: 'b', style: 'flex:none' })}</div></button>`;
      return `<button type="button" class="card${isRec ? ' hl' : ''}" data-act="pick:${e.id}" style="${op}flex:1 1 0;min-width:0;align-self:flex-start">
        <div class="col" style="padding:${dp(44)}"><div class="row" style="gap:${dp(20)}">${T(`${e.minutes}분`, 72, { c: isRec ? 'gold' : 'text', w: 'b', lines: 1 })}
        ${isRec ? Pill('추천') : ''}${tooLong ? Pill('충전 시간보다 길어요', 'faint') : ''}</div>${sp(12)}
        ${best ? RecordChip('최고', best) + sp(12) : ''}${T(`${e.n}화`, 28, { c: 'gold', w: 's', lines: 1 })}${T(e.title, 40, { w: 's', lines: 2 })}
        ${d.compact ? '' : sp(8) + T(e.blurb, 28, { c: 'sub', lines: 2 })}
        ${e.inputs.length && !d.compact ? `${sp(32)}<div class="flow" style="gap:${dp(14)}">${e.inputs.map((i) => `<span style="display:inline-flex;border-radius:${dp(16)};background:${K.surface2};padding:${dp(12)}" title="${LABEL[i]}">${InputIcon(i, dp(44), K.sub)}</span>`).join('')}</div>` : ''}
        ${sp(!d.compact ? 48 : 24)}<div class="row">${T(`퍼즐 ${e.puzzles}개`, 26, { c: 'faint', style: 'flex:1' })}${T('선택하기 ›', 30, { c: isRec ? 'gold' : 'text', w: 's', lines: 1 })}</div></div></button>`;
    };
    const cards = d.land ? `<div class="row" style="gap:${dp(32)};align-items:flex-start">${episodes.map(card).join('')}</div>`
      : `<div class="col" style="gap:${dp(28)}">${episodes.map(card).join('')}</div>`;
    return { key: 'matching', html: `<div class="scr" style="justify-content:center"><div data-r="head">${head}</div>${sp(d.compact ? 24 : 40)}<div data-r="cards">${cards}</div></div>`, parts: { head, cards } };
  }

  function IntroScreen(ui) {
    return { key: 'intro', html: `<div class="scr center"><div class="col" style="max-width:${dp(1100)};align-items:center">
      ${ui.recap ? T('지난 화', 24, { c: 'faint', w: 's', lines: 1 }) + sp(8) + T(ui.recap, 32, { c: 'sub', align: 'center' }) + sp(28) : ''}
      ${Pill(ui.episodeNumber > 0 ? `${ui.episodeNumber}화 · ${ui.episodeTitle || '에피소드'}` : ui.episodeTitle || '에피소드')}${sp(40)}${T(ui.story, 52, { w: 's', align: 'center' })}${sp(24)}
      ${ui.puzzleCount > 0 ? T(`잠금 ${ui.puzzleCount}개를 모두 풀면 탈출합니다`, 30, { c: 'sub', align: 'center' }) : ''}${sp(64)}
      ${Btn('시작', 'intro', true, `min-width:min(100%, ${dp(420)})`)}</div></div>`, parts: {} };
  }

  let carKey = null, flashUntil = 0;
  function PuzzleScreen(ui) {
    const d = dims;
    const chapter = `<div class="row">${T(ui.chapterLabel || `${ui.puzzleIndex + 1} / ${ui.puzzleCount}`, 28, { c: 'gold', w: 'b', lines: 1, style: 'flex:none' })}${sp(null, 20)}
      ${Array.from({ length: ui.puzzleCount }, (_, i) => `<span style="flex:none;width:${dp(14)};height:${dp(14)};min-width:6px;min-height:6px;margin-right:${dp(10)};border-radius:50%;background:${i <= ui.puzzleIndex ? K.gold : 'rgba(133,144,163,0.5)'}"></span>`).join('')}</div>`;
    const progressBlk = d.compact ? `<div class="row grow">${chapter}${sp(null, 10)}${T(ui.puzzleTitle, 34, { w: 's', lines: 1 })}</div>`
      : `<div class="col grow">${chapter}${T(ui.puzzleTitle, 34, { w: 's', lines: 1 })}</div>`;
    const ring = ui.bonusSecLeft != null ? `<div class="row" style="flex:none">${BonusRing(ui.bonusSecLeft, ui.bonusSecTotal ?? ui.bonusSecLeft, dp(d.compact ? 64 : 84))}${sp(null, 12)}
      ${T(ui.bonusSecLeft > 0 ? '번개 금고' : '번개 시간 끝', 24, { c: ui.bonusSecLeft > 0 ? 'amber' : 'faint', w: 's', lines: 1 })}</div>` : '';
    const charge = `<div class="col" style="${d.land ? `width:${dp(d.compact ? 760 : 560)};flex:none;max-width:45%` : 'width:100%'}">
      <div class="row">${T(`충전 ${Math.trunc(ui.socPercent)}% → ${ui.targetPercent}%`, 28, { c: 'sub', lines: 1, style: 'flex:1' })}
      ${T(ui.chargeEtaMinutes != null ? `약 ${ui.chargeEtaMinutes}분 남음` : '남은 시간 계산 중', 28, { c: 'sub', lines: 1 })}</div>${sp(10)}${SocBar(ui.socPercent, ui.targetPercent)}</div>`;
    const top = d.land ? `<div class="row">${progressBlk}${ring ? sp(null, 24) + ring : ''}${sp(null, 40)}${charge}</div>`
      : `<div class="row">${progressBlk}${ring}</div>${sp(20)}${charge}`;

    const input = ui.requiredInput;
    const key = input ? input + JSON.stringify(carValue(input)) : null;
    if (key !== carKey) { if (carKey != null && key != null && carKey.startsWith(input)) flashUntil = performance.now() + 800; carKey = key; }
    const flash = performance.now() < flashUntil;
    const car = input ? `<div class="row">${CarMap(input, dp(200), flash)}${sp(null, 24)}<div class="col" style="min-width:0">
      ${T(LABEL[input], 32, { c: 'gold', w: 'b', lines: 1 })}${d.compact ? '' : T('우리 차의 이 부분을 움직이세요', 24, { c: 'sub', lines: 1 })}</div></div>${sp(20)}` : '';
    // The app speaks the story (TTS); on the web it is also shown, so a muted browser still gets the wiggle note.
    const text = `${ui.story ? T(ui.story, 26, { c: 'sub', style: `margin-bottom:${dp(10)}` }) : ''}${T(ui.prompt, 48, { w: 'b', lines: 3 })}
      ${ui.hintText ? `${sp(24)}<div class="card" style="padding:${dp(28)}">${T('힌트', 28, { c: 'gold', w: 's' })}${T(ui.hintText, 28, { lines: 3 })}</div>` : ''}`;
    const buttons = `${sp(40)}<div class="row" style="gap:${dp(24)}">${Btn(ui.hintsUsed > 0 ? `힌트 (${ui.hintsUsed})` : '힌트', 'hint', false, 'flex:1 1 0')}${Btn('건너뛰기', 'skip', false, 'flex:1 1 0')}</div>`;
    const vis = visualView(ui.visual, ui.requiredInput, d.land);
    const html = `<div class="scr" style="${d.land ? 'height:100%' : ''}"><div data-r="top">${top}</div>${sp(32)}
      ${d.land ? `<div class="row grow" style="align-items:stretch"><div class="col grow" data-r="vis">${vis}</div>${sp(null, 56)}
        <div class="col grow" style="justify-content:center"><div data-r="car">${car}</div><div data-r="text">${text}</div><div data-r="btns">${buttons}</div></div></div>`
      : `<div class="col pvis" data-r="vis">${vis}</div>${sp(36)}<div data-r="car">${car}</div><div data-r="text">${text}</div><div data-r="btns">${buttons}</div>`}</div>`;
    return { key: 'puzzle', html, parts: { top, vis, car, text, btns: buttons } };
  }
  function carValue(input) {
    switch (input) {
      case 'WINDOW': return vs.windowDriverPercent;
      case 'READING_LIGHT': return vs.readingLightOn;
      case 'TURN_SIGNAL': return vs.turnSignal;
      case 'STEERING_HEAT': return vs.steeringHeatLevel;
      case 'SEAT_HEAT': return vs.seatHeatDriverLevel;
      case 'WIPER': return vs.wiperOn;
      case 'SEATBELT': return vs.belts.slice().sort();
      case 'PASSENGER_WINDOW': return CE.windowPercent(vs, 'ROW_1_RIGHT');
      case 'REAR_WINDOW': return vs.windows;
    }
  }

  function SolvedScreen(ui) {
    const g = 'g-sv';
    return { key: 'solved', html: `<div class="solved"><svg class="ring" viewBox="0 0 100 100" aria-hidden="true"><defs><radialGradient id="${g}"><stop offset="0" stop-color="${K.gold}" stop-opacity=".18"/><stop offset="1" stop-color="${K.gold}" stop-opacity=".04"/></radialGradient></defs>
      <circle cx="50" cy="50" r="34" fill="url(#${g})" stroke="${K.gold}" stroke-width="1.7"/><circle class="wave" cx="50" cy="50" r="34" fill="none" stroke="${K.gold}" stroke-width="1"/></svg>
      <div class="inner"><svg viewBox="0 0 100 100" style="width:${dp(96)};height:${dp(96)};min-width:40px;min-height:40px" aria-hidden="true">
        <path d="${arcD(60, 28, 22, 180, 180)}" fill="none" stroke="${K.gold}" stroke-width="9" stroke-linecap="round"/><path d="M82 28V40" stroke="${K.gold}" stroke-width="9" stroke-linecap="round"/>
        <rect x="14" y="44" width="62" height="50" rx="10" fill="${K.gold}"/><circle cx="45" cy="66" r="6" fill="${K.deep}"/></svg>${sp(16)}
      <h2 style="margin:0;font:inherit">${T('잠금 해제', 72, { c: 'gold', w: 'b', lines: 1 })}</h2>${T(ui.story || '다음 단서로 넘어갑니다', 30, { c: 'sub', align: 'center', lines: 3 })}</div></div>`, parts: {} };
  }

  function EndingScreen(ui) {
    const d = dims;
    const scored = !ui.waitingForCharge && ui.stars > 0;
    const top = scored ? StarsSvg(ui.stars, dp(d.compact ? 56 : 84)) + sp(d.compact ? 12 : 20) : d.compact ? '' : LogoMark(dp(110)) + sp(28);
    const text = d.compact ? ui.endingText : ui.endingText.replace(/([.!?]) /g, '$1\n');
    let body;
    if (ui.waitingForCharge) {
      body = `<div class="card" style="width:min(100%, ${dp(624)});padding:${dp(24)}"><div class="row">${T(`충전 ${Math.trunc(ui.socPercent)}% → ${ui.targetPercent}%`, 28, { c: 'sub', lines: 1, style: 'flex:1' })}
        ${ui.chargeEtaMinutes != null ? T(`약 ${ui.chargeEtaMinutes}분 남음`, 28, { c: 'sub', lines: 1 }) : ''}</div>${sp(12)}${SocBar(ui.socPercent, ui.targetPercent)}</div>`;
    } else {
      const best = progress.best[ui.episodeId];
      const stat = (l, v) => `<div class="card stat">${T(l, 26, { c: 'sub', lines: 1 })}${T(v, 44, { w: 'b', lines: 1 })}</div>`;
      body = `<div class="row" style="gap:${dp(24)};justify-content:center">${stat('걸린 시간', clock(ui.elapsedSec))}${best ? stat(`최고 기록 · 별 ${best.stars}개`, clock(best.sec)) : stat('사용한 힌트', `${ui.hintsUsed}번`)}</div>
        ${ui.summary ? sp(16) + T(ui.summary, 26, { c: 'sub', align: 'center' }) : ''}
        ${progress.newBest || progress.newBadges.length ? `${sp(16)}<div class="flow" style="justify-content:center">${progress.newBest ? Pill('새 기록!', 'amber') : ''}
          ${CE.KINDS.filter((k) => progress.newBadges.includes(k)).map((k) => Pill(`새 배지 · ${CE.MissionKind[k]}`)).join('')}</div>` : ''}
        ${sp(d.compact ? 12 : 24)}<div class="row" style="gap:${dp(28)};justify-content:center">${d.compact ? '' : CarSide(look, dp(320))}
        ${progress.newOpened.length ? `<div class="col" style="min-width:0;align-items:flex-start">${T('우리 차 꾸미기에 새로 열렸어요', 26, { c: 'gold', w: 's' })}${sp(8)}
          <div class="flow">${progress.newOpened.map((it) => Pill(`${GROUP[it.group]} · ${it.label}`, 'amber')).join('')}</div></div>` : ''}</div>`;
    }
    const pills = ui.waitingForCharge ? Pill('퍼즐 완료 · 충전 중', 'amber')
      : `<div class="flow" style="justify-content:center">${Pill('탈출 성공')}${scored && progress.log.length ? Pill(adventure(progress.log.length), 'amber') : ''}</div>`;
    // 원래대로 출발: what still differs from the start, live. The family moves it back; the app never does.
    const left = ui.restoreLeft;
    const restore = !ui.waitingForCharge && left ? `${sp(d.compact ? 16 : 28)}<div class="card${left.length ? '' : ' hl'}" style="max-width:min(100%, ${dp(900)});padding:${dp(20)} ${dp(32)};display:flex;flex-direction:column;align-items:center">
      ${T('마지막 미션 · 원래대로 출발', 26, { c: 'gold', w: 's', lines: 1 })}${sp(10)}
      ${left.length ? T('시작 때와 달라진 곳을 돌려 놓아요', 26, { c: 'sub', align: 'center' }) + sp(12) + `<div class="flow" style="justify-content:center">${left.map((x) => Pill(x, 'amber')).join('')}</div>`
        : T('출발 준비 완료', 40, { w: 'b', lines: 1 })}</div>` : '';
    const story = `${top}${pills}${sp(20)}
      <h2 style="margin:0;font:inherit">${T(ui.episodeTitle, 64, { w: 'b', align: 'center' })}</h2>${sp(20)}
      ${T(text, 32, { c: 'sub', align: 'center', style: `white-space:pre-line;max-width:${dp(d.compact ? 1300 : 900)}` })}${restore}`;
    // Landscape, real ending: the story on the left, the results on the right, so the whole ending and 처음으로 fit without scrolling.
    const content = d.land && !ui.waitingForCharge
      ? `<div class="row" style="gap:${dp(56)}"><div class="col" style="flex:1 1 0;min-width:0;align-items:center">${story}</div>
          <div class="col" style="flex:1 1 0;min-width:0;align-items:center">${body}</div></div>`
      : `<div class="col" style="align-items:center">${story}${sp(d.compact ? 24 : 48)}${body}</div>`;
    return { key: 'ending', html: `<div class="scr center"><div data-r="content" style="width:100%">${content}</div>${sp(d.compact ? 24 : 56)}${Btn('처음으로', 'back', true, `min-width:min(100%, ${dp(420)})`)}</div>`, parts: { content } };
  }

  // ---------------------------------------------------------------- 마이 차고 (GarageScreen)
  const GROUP = { paint: '페인트', wheel: '휠', sticker: '스티커', theme: '테마' };
  const TABS = [['paint', '페인트'], ['wheel', '휠'], ['sticker', '스티커'], ['theme', '테마'], ['mode', '주행 모드']];
  const MOODS = CE.Theme.map((t) => t.mood);
  const garage = { open: false, tab: 'paint', slot: 'FRONT_DOOR', mode: 'NORMAL' };
  let shownMode = vs.driveMode; // last mode read while parked: the skin never changes under the drive cover
  const lockSvg = `<svg viewBox="0 0 100 100" style="width:${dp(30)};height:${dp(30)};min-width:16px;min-height:16px;flex:none" aria-hidden="true">
    <path d="M32 46V32Q32 14 50 14Q68 14 68 32V46" fill="none" stroke="currentColor" stroke-width="9"/><rect x="20" y="44" width="60" height="44" rx="10" fill="currentColor"/></svg>`;
  /** One pickable item: picture, name, and when locked the line that says how to open it. */
  function Opt(act, on, it, pic, earned) {
    const locked = it && !CE.isOpen(it, earned);
    return `<button type="button" class="opt${on ? ' on' : ''}${locked ? ' locked' : ''}" data-act="${locked ? 'locked' : act}" aria-pressed="${on}"${locked ? ' aria-disabled="true"' : ''}>
      <span class="opic">${pic}</span>${T(it ? it.label : '떼기', 24, { w: 's', align: 'center', lines: 1 })}
      ${locked ? `<span class="row" style="gap:${dp(8)};color:var(--faint)">${lockSvg}${T(CE.needHint(it.need), 20, { c: 'faint', align: 'center', lines: 2 })}</span>` : ''}</button>`;
  }
  const swatch = (bg, inner = '') => `<span class="sw" style="background:${bg}">${inner}</span>`;
  const skinChip = (id) => { const k = CE.SKINS[id]; return swatch(`linear-gradient(135deg, ${k.top}, ${k.deep})`, `<i style="background:${k.gold}"></i><i style="background:${k.surface2}"></i>`); };
  const themeOpts = (act, sel, earned) => CE.Theme.map((t) => Opt(`${act}:${t.id}`, t.id === sel, t, skinChip(t.id), earned)).join('');
  function modeLine(mode) {
    if (mode == null) return '주행 모드 정보 없음 · 테마를 직접 골라요';
    return `지금 주행 모드 ${CE.DRIVE_MODE_NAME[mode]}${look.followMode ? ' · 테마가 모드를 따라가요' : ''}`;
  }
  function GarageScreen() {
    const d = dims, e = earnedOf(progress), g = garage;
    const tabs = `<div class="gtabs" role="tablist">${TABS.map(([id, name]) => `<button type="button" role="tab" class="gtab" data-act="tab:${id}" aria-selected="${g.tab === id}">${T(name, 24, { c: g.tab === id ? 'on-gold' : 'text', w: 's', lines: 1 })}</button>`).join('')}</div>`;
    let body = '';
    if (g.tab === 'paint') body = `<div class="ggrid">${CE.Paint.map((p) => Opt(`paint:${p.id}`, look.paint === p.id, p, swatch(hex(p.rgb)), e)).join('')}</div>`;
    else if (g.tab === 'wheel') body = `<div class="ggrid">${CE.Wheel.map((w) => Opt(`wheel:${w.id}`, look.wheel === w.id, w, `<svg viewBox="68 96 64 64" class="wpic" aria-hidden="true">${wheelSvg(w.id, 100, 128)}</svg>`, e)).join('')}</div>`;
    else if (g.tab === 'sticker') {
      const cur = look.stickers[g.slot];
      body = `${T('어디에 붙일까요?', 24, { c: 'sub', w: 's' })}${sp(10)}<div class="gtabs">${CE.StickerSlot.map((sl) => `<button type="button" class="gtab" data-act="slot:${sl}" aria-selected="${g.slot === sl}">
        ${T(SLOT_NAME[sl] + (look.stickers[sl] ? ` · ${CE.MissionKind[look.stickers[sl]]}` : ''), 22, { c: g.slot === sl ? 'on-gold' : 'text', w: 's', lines: 1 })}</button>`).join('')}</div>${sp(16)}
        ${T('미션 배지를 받을 때마다 스티커가 하나씩 열려요', 22, { c: 'sub' })}${sp(10)}
        <div class="ggrid">${CE.STICKERS.map((st) => Opt(`sticker:${st.id}`, cur === st.id, st, `<svg viewBox="0 0 40 40" class="spic" aria-hidden="true">${stickerSvg(st.id, 20, 20, 17)}</svg>`, e)).join('')}
        ${cur ? Opt('unstick', false, null, `<svg viewBox="0 0 40 40" class="spic" aria-hidden="true"><circle cx="20" cy="20" r="16" fill="none" stroke="currentColor" stroke-width="3" stroke-dasharray="5 4"/></svg>`, e) : ''}</div>`;
    } else if (g.tab === 'theme') {
      const follows = look.followMode && shownMode != null;
      body = `${T('카카오톡 테마처럼 앱 전체 색과 배경이 바뀌어요', 22, { c: 'sub' })}${sp(10)}
        ${follows ? `<div class="card" style="padding:${dp(16)} ${dp(20)}">${T(`지금은 ${CE.DRIVE_MODE_NAME[shownMode]} 모드 테마가 보여요. 여기서 고른 테마는 '모드 따라가기'를 끄면 보여요`, 22, { c: 'gold' })}</div>${sp(12)}` : ''}
        <div class="ggrid">${themeOpts('theme', look.theme, e)}</div>`;
    } else {
      const m = g.mode;
      body = `${T(modeLine(shownMode), 24, { c: shownMode == null ? 'faint' : 'gold', w: 's' })}${sp(6)}
        ${T('주행 모드는 차에서만 바꿀 수 있어요. 앱은 읽기만 해요', 22, { c: 'sub' })}${sp(14)}
        ${Btn(`모드 따라가기 · ${look.followMode ? '켜짐' : '꺼짐'}`, 'follow', look.followMode)}${sp(18)}
        <div class="gtabs">${CE.DriveMode.map((x) => `<button type="button" class="gtab" data-act="gmode:${x}" aria-selected="${m === x}">
          ${T(CE.DRIVE_MODE_NAME[x] + (x === shownMode ? ' · 지금' : ''), 24, { c: m === x ? 'on-gold' : 'text', w: 's', lines: 1 })}</button>`).join('')}</div>${sp(16)}
        ${T(`${CE.DRIVE_MODE_NAME[m]} 모드 테마`, 24, { c: 'sub', w: 's' })}${sp(10)}<div class="ggrid">${themeOpts('mtheme', CE.themeOf(look, m), e)}</div>${sp(18)}
        ${T(`${CE.DRIVE_MODE_NAME[m]} 모드 무드등 색`, 24, { c: 'sub', w: 's' })}${sp(10)}
        <div class="flow" style="gap:${dp(14)}">${MOODS.map((c) => `<button type="button" class="mood" data-act="mood:${c}" aria-pressed="${CE.moodOf(look, m) === c}" aria-label="무드등 색 ${hex(c)}" style="--mc:${hex(c)}"></button>`).join('')}</div>${sp(18)}
        ${Btn(moodPreview ? '무드등 끄기' : '무드등 미리 켜 보기', 'preview', false)}${sp(8)}
        ${T('차가 P에 있을 때만 켜져요. 이 화면을 나가면 원래대로 돌아가요', 20, { c: 'faint' })}`;
    }
    const skin = CE.byId(CE.Theme, CE.shownTheme(look, shownMode));
    const head = `<div class="row" style="gap:${dp(20)}">${T('우리 차 꾸미기', d.compact ? 40 : 56, { w: 'b', lines: 1, style: 'flex:1 1 0' })}${Btn('다 했어요', 'garageback', true, 'flex:none')}</div>`;
    const preview = `<div class="vis grow gstage${d.compact ? '' : ' deco'}" style="width:100%"><div class="car3d" data-car3d="garage" aria-label="꾸민 우리 차 · 끌어서 돌려 보기">${CarSide(look, 'min(100cqw, 150cqh)', '꾸민 우리 차', 'three_quarter')}</div></div>
      ${sp(12)}<div class="flow" style="justify-content:center">${Pill(`테마 · ${skin.label}`)}${d.compact ? '' : Pill(shownMode == null ? '모드 정보 없음' : `${CE.DRIVE_MODE_NAME[shownMode]} 모드`, shownMode == null ? 'faint' : 'amber')}</div>`;
    const picks = `${tabs}${sp(20)}${body}`;
    return { key: 'garage', html: d.land
      ? `<div class="scr" style="height:100%">${head}${sp(d.compact ? 16 : 28)}<div class="row grow" style="align-items:stretch;gap:${dp(48)}">
          <div class="col" style="flex:1.15 1 0;min-width:0">${preview}</div><div class="col gpicks" style="flex:1 1 0;min-width:0">${picks}</div></div></div>`
      : `<div class="scr">${head}${sp(24)}<div class="col" style="height:${dp(420)};min-height:200px;flex:none">${preview}</div>${sp(28)}${picks}</div>`,
    parts: {} };
  }

  function PausedDrivingOverlay() {
    const size = dp(dims.compact ? 120 : 168);
    return { key: 'paused', html: `<div class="paused"><div class="col" style="align-items:center">
      <div class="pbadge" style="width:${size};height:${size};min-width:64px;min-height:64px">${T('P', 96, { c: 'on-gold', w: 'b', lines: 1 })}</div>${sp(36)}
      ${T('주행 중 일시정지', 56, { w: 'b', align: 'center', lines: 1 })}${sp(16)}${T('기어를 P로 두면 이어집니다', 36, { c: 'sub', align: 'center', lines: 1 })}</div></div>`, parts: {} };
  }

  /** Door open: same opaque pause as driving, its own words. Doors are a pause signal only. */
  function DoorPauseScreen(ui) {
    const size = dp(dims.compact ? 120 : 168), ask = ui.doorAsk;
    const bar = `<span style="width:${dp(18)};height:${dp(64)};min-width:7px;min-height:26px;border-radius:99px;background:${ask ? K.onGold : K.gold}"></span>`;
    return { key: 'door', html: `<div class="paused"><div class="col" style="align-items:center">
      <div class="pbadge" style="width:${size};height:${size};min-width:64px;min-height:64px;gap:${dp(16)};background:${ask ? K.gold : K.surface2}">${bar}${bar}</div>${sp(36)}
      ${T(ask ? '이어서 할까요?' : '문이 열려 잠시 멈췄어요', 56, { w: 'b', align: 'center' })}${sp(16)}${T(ask ? '문이 모두 닫혔어요' : '모두 닫히면 이어서 할까요?', 36, { c: 'sub', align: 'center' })}
      ${ask ? sp(48) + Btn('이어서 하기', 'resume', true, `min-width:min(100%, ${dp(420)})`) : ''}</div></div>`, parts: {} };
  }

  // ---------------------------------------------------------------- render with region patching (buttons survive the 200 ms ticks)
  let curLayout = '';
  const partCache = new Map();
  const frameEl = document.getElementById('frame');
  const VARS = { bg: '--bg', deep: '--bg-deep', surface: '--surface', surface2: '--surface2', line: '--line', text: '--text', sub: '--sub', faint: '--faint', gold: '--gold', amber: '--amber', onGold: '--on-gold', top: '--top' };
  let curSkin = 'NIGHT_SKY';
  /** Theme pack: K for the drawings, CSS variables for the rest. 밤하늘 = style.css as is (nothing set). */
  function applySkin(id) {
    curSkin = id; curLayout = '';
    const sk = CE.SKINS[id];
    for (const k of Object.keys(K)) K[k] = sk[k];
    for (const [k, v] of Object.entries(VARS)) { if (id === 'NIGHT_SKY') frameEl.style.removeProperty(v); else frameEl.style.setProperty(v, sk[k]); }
    if (id === 'NIGHT_SKY') delete frameEl.dataset.skin; else frameEl.dataset.skin = id;
    frameEl.style.colorScheme = sk.light ? 'light' : '';
  }
  const NEED_AT = { WINDOW: ['[data-range="win:driver"]'], PASSENGER_WINDOW: ['[data-range="win:ROW_1_RIGHT"]'], REAR_WINDOW: ['[data-range="win:ROW_2_LEFT"]', '[data-range="win:ROW_2_RIGHT"]'],
    READING_LIGHT: ['[data-hold=light]'], WIPER: ['[data-hold=wiper]'], TURN_SIGNAL: ['[data-seg=turn]'], STEERING_HEAT: ['[data-seg=steer]'], SEAT_HEAT: ['[data-seg=seat]'], SEATBELT: ['.belts'] };
  let needKey = null;
  function markNeed(ui) {
    const key = ui.phase === 'PAUSED_DRIVING' ? 'P' : ui.phase === 'PUZZLE' ? ui.requiredInput : null;
    if (key === needKey) return;
    needKey = key;
    for (const el of $$('.p-item.need')) el.classList.remove('need');
    const els = key === 'P' ? $$('[data-seg=gear]') : (NEED_AT[key] || []).flatMap((q) => $$(q));
    const items = els.map((el) => el.closest('.p-item'));
    for (const it of items) it.classList.add('need');
    const note = document.getElementById('p-need');
    note.hidden = !key;
    note.textContent = key === 'P' ? '기어를 P로 두면 게임이 이어집니다' : key ? `지금 움직일 곳: ${LABEL[key]}` : '';
    const body = document.getElementById('p-body');
    if (items[0] && body.clientHeight) body.scrollTo({ top: Math.max(0, items[0].offsetTop - body.offsetTop - 64), behavior: 'smooth' });
  }
  function render() {
    const ui = game.ui;
    markNeed(ui);
    if (!driving()) shownMode = vs.driveMode ?? null;
    const skin = CE.shownTheme(look, shownMode);
    if (skin !== curSkin) applySkin(skin);
    if (ui.phase !== 'TITLE' && ui.phase !== 'PAUSED_DRIVING') garage.open = false;
    if (!garage.open) endMoodPreview();
    const scr = ui.phase === 'PAUSED_DRIVING' ? PausedDrivingOverlay() : garage.open ? GarageScreen() : {
      TITLE: TitleScreen, MATCHING: MatchingScreen, INTRO: IntroScreen, PUZZLE: PuzzleScreen, SOLVED: SolvedScreen, ENDING: EndingScreen, PAUSED_DOOR: DoorPauseScreen,
    }[ui.phase](ui);
    if (ui.phase !== 'TITLE') logConfirm = false;
    const layout = `${scr.key}|${dims.land}|${dims.compact}|${ui.phase === 'PUZZLE' ? ui.puzzleIndex : ''}|${ui.phase === 'ENDING' ? ui.waitingForCharge : ''}` +
      `|${ui.phase === 'TITLE' ? `${ui.readyEpisode}|${logConfirm}|${progress.log.length}` : ''}|${ui.doorAsk}` +
      (scr.key === 'garage' ? `|${JSON.stringify(garage)}|${CE.encodeLook(look)}|${shownMode}|${moodPreview}` : '');
    if (layout !== curLayout) {
      // a pick in the garage keeps both scroll spots
      const keep = scr.key === 'garage' && curLayout.startsWith('garage') ? [gameEl.scrollTop, gameEl.querySelector('.gpicks')?.scrollTop ?? 0] : null;
      curLayout = layout;
      partCache.clear();
      gameEl.innerHTML = scr.html;
      gameEl.scrollTop = keep ? keep[0] : 0;
      if (keep && gameEl.querySelector('.gpicks')) gameEl.querySelector('.gpicks').scrollTop = keep[1];
      for (const [k, h] of Object.entries(scr.parts)) partCache.set(k, h);
      sync3d();
      return;
    }
    for (const [k, h] of Object.entries(scr.parts)) {
      if (partCache.get(k) === h) continue;
      partCache.set(k, h);
      const el = gameEl.querySelector(`[data-r="${k}"]`);
      if (el) el.innerHTML = h;
    }
  }

  gameEl.addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-act]');
    if (!b) return;
    const a = b.dataset.act;
    if (a === 'start') { dailyMode = false; game.startMatching(); }
    else if (a === 'today') { dailyMode = true; game.startMatching(); }
    else if (a.startsWith('pick:')) { const id = a.slice(5); if (dailyMode) game.startDaily(id, pinnedSeed ?? CE.epochDay()); else game.startEpisode(id); }
    else if (a === 'intro') game.skipIntro();
    else if (a === 'hint') game.requestHint();
    else if (a === 'skip') game.skipPuzzle();
    else if (a === 'back') game.backToTitle();
    else if (a === 'resume') game.resumeAfterDoor();
    else if (a === 'ready') { dailyMode = false; game.startNext(); }
    else if (a === 'dismiss') game.dismissReady();
    else if (a === 'garage') { garage.open = true; garage.mode = vs.driveMode ?? 'NORMAL'; render(); }
    else if (a === 'garageback') { garage.open = false; render(); }
    else if (a.startsWith('tab:')) { garage.tab = a.slice(4); if (garage.tab === 'mode' && shownMode) garage.mode = shownMode; render(); }
    else if (a.startsWith('slot:')) { garage.slot = a.slice(5); render(); }
    else if (a.startsWith('gmode:')) { garage.mode = a.slice(6); render(); }
    else if (a.startsWith('paint:')) setLook({ ...look, paint: a.slice(6) });
    else if (a.startsWith('wheel:')) setLook({ ...look, wheel: a.slice(6) });
    else if (a.startsWith('sticker:')) setLook({ ...look, stickers: { ...look.stickers, [garage.slot]: a.slice(8) } });
    else if (a === 'unstick') { const st = { ...look.stickers }; delete st[garage.slot]; setLook({ ...look, stickers: st }); }
    else if (a.startsWith('theme:')) setLook({ ...look, theme: a.slice(6), followMode: false }); // a picked theme stays (Android: same)
    else if (a === 'follow') setLook({ ...look, followMode: !look.followMode });
    else if (a.startsWith('mtheme:')) {
      // a new theme for the mode brings its own mood color
      const mt = { ...look.modeThemes, [garage.mode]: a.slice(7) }, mm = { ...look.modeMoods }; delete mm[garage.mode];
      setLook({ ...look, modeThemes: mt, modeMoods: mm });
      if (moodPreview) previewMood(CE.moodOf(look, garage.mode));
    } else if (a.startsWith('mood:')) {
      setLook({ ...look, modeMoods: { ...look.modeMoods, [garage.mode]: Number(a.slice(5)) } });
      if (moodPreview) previewMood(CE.moodOf(look, garage.mode));
    } else if (a === 'preview') { if (moodPreview) endMoodPreview(); else previewMood(CE.moodOf(look, garage.mode)); render(); }
    else if (a === 'clearlog') { if (logConfirm) { logConfirm = false; clearLog(); } else logConfirm = true; render(); }
  });

  // Scaled (Kit.kt): scale against the 17" window, 1680x1184 land / 840x1184 port; compact = very wide.
  function measure() {
    const w = gameEl.clientWidth, h = gameEl.clientHeight;
    if (!w || !h) return;
    const land = w > h, compact = land && w > h * 1.8;
    const s = Math.min(1.2, Math.max(0.34, Math.min(w / (land ? 1680 : 840), h / (compact ? 760 : 1184))));
    gameEl.style.setProperty('--s', s.toFixed(4));
    gameEl.classList.toggle('land', land);
    gameEl.classList.toggle('port', !land);
    gameEl.classList.toggle('compact', compact);
    if (land !== dims.land || compact !== dims.compact) { Object.assign(dims, { s, land, compact }); curLayout = ''; }
    dims.s = s;
    render();
  }
  new ResizeObserver(measure).observe(gameEl);

  // ---------------------------------------------------------------- Pleos Connect display mock (web only)
  // App window = PleosScreens.kt official dp (1 CSS px = 1 dp), the whole display scaled to fit. PORT = 1:1 split with a second app.
  const SCREENS = {
    S17_LAND: [1700, 1184], S17_PORT: [840, 1184], S146_LAND: [1360, 948], S146_PORT: [672, 948], S129_LAND: [1274, 855], S129_PORT: [628, 855],
    WS_LAND: [1640, 810], WS_PORT: [800, 810], WE_LAND: [1640, 1400], WE_PORT: [800, 1400], WF_LAND: [1256, 800], WF_PORT: [648, 800],
  };
  // Physical panel per preset (docs/pleos/form-factor-official-dp.md), for the caption only.
  const PANEL = { S17: '17" 2560×1440', S146: '14.6" 2048×1152', S129: '12.9" 1920×1080', WS: '24.6" 3840×850', WE: '24.6" 3840×1440', WF: '18.7" 3072×816' };
  const devEl = document.getElementById('pl-dev'), fitEl = document.getElementById('pl-fit'), shellEl = document.getElementById('pl-shell'), capEl = document.getElementById('pl-cap');
  const pageEl = document.getElementById('page');
  const clean = params.get('clean') === '1'; // the display alone, edge to edge (screen recording)
  pageEl.classList.toggle('clean', clean);
  const winEls = Array.from(devEl.querySelectorAll('.pl-win'));
  const screenSel = document.getElementById('pl-screen');
  let screen = [params.get('screen'), store.get('carescape.screen')].find((k) => k in SCREENS) || 'S17_LAND';
  let plMode = (params.get('mode') || store.get('carescape.plmode')) === 'light' ? 'light' : 'dark';
  function layoutDevice() {
    // Graphite layout (spec.md 1): driving view 853 wide beside status bar 90 + app window, GNB 140 under all, window bar 25.
    // Measured on the 17" (window 1210 high); other displays scale that chrome by k so the window itself stays official dp.
    const [w, h] = SCREENS[screen], split = w < h, k = h / 1210, hw = Math.round(25 * k);
    for (const el of winEls) { el.style.width = w + 'px'; el.style.height = h + 'px'; }
    devEl.classList.toggle('split', split);
    devEl.classList.toggle('light', plMode === 'light');
    const cols = [Math.round(853 * k), split ? w * 2 + hw : w, hw], rows = [Math.round(90 * k), h, Math.round(140 * k)];
    devEl.style.setProperty('--k', k.toFixed(4));
    devEl.style.gridTemplateColumns = cols.map((x) => x + 'px').join(' ');
    devEl.style.gridTemplateRows = rows.map((x) => x + 'px').join(' ');
    devEl.querySelector('.pl-apps').style.gap = hw + 'px';
    const dw = cols[0] + cols[1] + cols[2], dh = rows[0] + rows[1] + rows[2];
    devEl.style.width = dw + 'px'; devEl.style.height = dh + 'px';
    // Fit both ways and centre: bezel bz around the display, the caption (cap) under it, a small margin (pad) around both.
    const fw = fitEl.clientWidth, fh = fitEl.clientHeight;
    const narrow = fw < 620, bz = clean ? 0 : narrow ? 5 : 9, pad = clean ? 0 : narrow ? 8 : 16, cap = clean ? 0 : 28;
    pageEl.style.setProperty('--dev-ar', (dw / dh).toFixed(4)); // the under-900px layout sizes the stage from it (style.css)
    const f = Math.max(0.05, Math.min((fw - 2 * (pad + bz)) / dw, (fh - 2 * (pad + bz) - cap) / dh));
    const sw = dw * f + 2 * bz, sh = dh * f + 2 * bz;
    Object.assign(shellEl.style, { width: sw + 'px', height: sh + 'px', left: (fw - sw) / 2 + 'px', top: (fh - cap - sh) / 2 + 'px' });
    shellEl.style.setProperty('--bz', bz + 'px');
    devEl.style.transform = `scale(${f})`;
    capEl.textContent = narrow ? `차량 화면 미리보기 · ${w}×${h} dp · ${Math.round(f * 100)}%`
      : `차량 화면 미리보기 · Connect-${screen.split('_')[0].replace(/\d+$/, '')} ${PANEL[screen.split('_')[0]]} · 앱 창 ${w}×${h} dp (${split ? '분할' : '단독'}) · ${Math.round(f * 100)}%`;
    screenSel.value = screen;
    document.querySelectorAll('[data-pl-mode]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.plMode === plMode)));
  }
  screenSel.addEventListener('change', () => { screen = screenSel.value; store.set('carescape.screen', screen); layoutDevice(); });
  document.querySelectorAll('[data-pl-mode]').forEach((b) => b.addEventListener('click', () => { plMode = b.dataset.plMode; store.set('carescape.plmode', plMode); layoutDevice(); }));
  new ResizeObserver(layoutDevice).observe(fitEl);
  // Driving view (left of the app area): mirrors the virtual car like Pleos' own (spec.md 4). Parked: our IONIQ 9 in 3D,
  // a fixed rear 3/4 with door controls; driving: big speed, the car still and small (no motion while driving).
  const $id = (id) => document.getElementById(id);
  let driveCarKey = '', driveKey = '';
  function sync3d() {
    const C = window.CarEscape3D;
    if (!C) return;
    C.view('drive', $id('pl-car'), look, driving());
    C.view('garage', gameEl.querySelector('[data-car3d=garage]'), look, false);
  }
  window.addEventListener('car3d-ready', () => { driveCarKey = ''; sync3d(); });
  function syncDrive() {
    const drv = driving(), g = vs.gear === 'PARK' ? 'P' : 'D', speed = String(Math.round(vs.speedKmh));
    const soc = Math.round(vs.socPercent), range = vs.rangeMeters != null ? `${Math.round(vs.rangeMeters / 1000)}km` : `${soc}%`;
    const d = new Date(), hh = d.getHours(), clockText = `${String(hh % 12 || 12).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} ${hh < 12 ? '오전' : '오후'}`;
    const key = [g, speed, soc, range, vs.driveMode, vs.charging, vs.belts.includes('ROW_1_LEFT'), clockText].join('|');
    if (key !== driveKey) {
      driveKey = key;
      devEl.classList.toggle('drv', drv);
      devEl.classList.toggle('charging', !!vs.charging);
      $id('pl-gear').innerHTML = ['P', 'R', 'N', 'D'].map((x) => x === g ? `<b>${x}</b>` : `<span>${x}</span>`).join('');
      $id('pl-speed').textContent = speed;
      $id('pl-kmh').firstChild.textContent = speed;
      const m = $id('pl-mode'); m.dataset.m = vs.driveMode || ''; m.textContent = vs.driveMode ? `${CE.DRIVE_MODE_NAME[vs.driveMode]} 모드` : '주행 모드 정보 없음';
      $id('pl-soc').textContent = range; $id('pl-socbar').style.width = soc + '%';
      $id('pl-belt').classList.toggle('ok', vs.belts.includes('ROW_1_LEFT'));
      $id('pl-clock').textContent = clockText;
    }
    const ck = CE.encodeLook(look) + drv;
    if (ck !== driveCarKey) {
      driveCarKey = ck;
      const host = $id('pl-car');
      host.querySelector('.carside')?.remove();
      host.insertAdjacentHTML('afterbegin', CarSide(look, 'min(100%, 780px)', '우리 차', 'rear_quarter')); // same angle as the 3D parked view: rear 3/4
      sync3d();
    }
  }
  setInterval(syncDrive, 250);
  layoutDevice();
  syncDrive();

  syncPanel();
  measure();
  step();
  // QA hook (headless checks read the engine; nothing in the page depends on it)
  // present.js (시연 진행 도구) drives the same engine: skip() time-lapses the engine clock (holds, intro and unlock waits) in steps the
  // engine accepts, reset() is a clean first run (records, car look and virtual car back to the start; screen and theme prefs stay).
  function skip(ms) { for (let left = ms; left > 0; left -= 500) { skew += Math.min(left, 500); step(); } }
  function reset(over = {}) {
    stopSpeech();
    for (const e of CE.Episodes.all) store.del('carescape.best.' + e.id);
    for (const k of ['badges', 'log', 'next_episode', 'look']) store.del('carescape.' + k);
    progress = loadProgress(); look = CE.decodeLook(null); recorded = false; started = null;
    Object.assign(garage, { open: false, tab: 'paint', slot: 'FRONT_DOOR', mode: 'NORMAL' });
    dailyMode = false; logConfirm = false; timelapse = false;
    vs = CE.vehicleState({ chargePortOpen: false, rangeMeters: 42 * CE.SIM_METERS_PER_PERCENT, ...over });
    game.backToTitle(); game.nextEpisodeIndex = 0;
    curLayout = ''; syncPanel(); step(); render();
  }
  /** 시연: the records of a family that finished season 1, so every garage item is open (own records stay). */
  function grant() {
    for (const e of CE.Episodes.all) if (!progress.best[e.id]) store.set('carescape.best.' + e.id, `3,${e.minutes * 45}`);
    store.set('carescape.badges', JSON.stringify(CE.KINDS));
    progress = { ...loadProgress(), newOpened: [] }; curLayout = ''; render();
  }
  window.__carEscape = { game, get vehicle() { return vs; }, update, skip, reset, grant, set quiet(v) { quiet = !!v; if (quiet) stopSpeech(); },
    get look() { return look; }, get earned() { return earnedOf(progress); }, clean, auto: params.get('auto') === '1' };
})();
