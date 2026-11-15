/*
 * 카 이스케이프 web: line-by-line port of :core + :game (Contract.kt, Puzzles.kt, EscapeGame.kt,
 * Episodes.kt, ChargeClock.kt, Sim.kt), v3. Pure logic, no DOM. Plain script: sets globalThis.CarEscape
 * so it loads from file://, a static host, or node (selftest.mjs).
 */
(function (root) {
  'use strict';

  // ---------------------------------------------------------------- kotlin.random.Random (XorWow)
  /** Exact port of kotlin.random.XorWowRandom with 32-bit int math, so seeds give the same puzzles. */
  class KRandom {
    constructor(seed1, seed2) {
      this.x = seed1 | 0; this.y = seed2 | 0; this.z = 0; this.w = 0; this.v = ~seed1;
      this.addend = ((seed1 << 10) ^ (seed2 >>> 4)) | 0;
      for (let i = 0; i < 64; i++) this.nextInt();
    }
    /** Random(seed: Long) */
    static ofLong(seed) {
      const b = BigInt(seed);
      return new KRandom(Number(BigInt.asIntN(32, b)), Number(BigInt.asIntN(32, b >> 32n)));
    }
    /** Random(seed: Int) */
    static ofInt(seed) { return new KRandom(seed | 0, (seed | 0) >> 31); }
    static any() { return new KRandom((Math.random() * 4294967296) | 0, (Math.random() * 4294967296) | 0); }
    nextInt() {
      let t = this.x;
      t ^= t >>> 2;
      this.x = this.y; this.y = this.z; this.z = this.w;
      const v0 = this.v;
      this.w = v0;
      t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4);
      this.v = t | 0;
      this.addend = (this.addend + 362437) | 0;
      return (t + this.addend) | 0;
    }
    nextBits(n) { return (this.nextInt() >>> (32 - n)) & ((-n) >> 31); }
    /** nextInt(until) / nextInt(from, until) */
    nextIntIn(from, until) {
      if (until === undefined) { until = from; from = 0; }
      const n = (until - from) | 0;
      if (!(n > 0)) throw new Error('bad range');
      let r;
      if ((n & -n) === n) r = this.nextBits(31 - Math.clz32(n));
      else {
        let bits, v;
        do { bits = this.nextInt() >>> 1; v = bits % n; } while (((bits - v + (n - 1)) | 0) < 0);
        r = v;
      }
      return from + r;
    }
    nextBoolean() { return this.nextBits(1) !== 0; }
  }
  const pick = (list, rnd) => list[rnd.nextIntIn(list.length)];
  function shuffled(list, rnd) {
    const a = list.slice();
    for (let i = a.length - 1; i >= 1; i--) { const j = rnd.nextIntIn(i + 1); const t = a[j]; a[j] = a[i]; a[i] = t; }
    return a;
  }
  const eqList = (a, b) => a.length === b.length && a.every((x, i) => x === b[i]);

  // ---------------------------------------------------------------- Contract.kt
  const Gear = { PARK: 'PARK', DRIVE: 'DRIVE', UNKNOWN: 'UNKNOWN' };
  const VehicleInput = ['WINDOW', 'READING_LIGHT', 'TURN_SIGNAL', 'STEERING_HEAT', 'SEAT_HEAT', 'WIPER', 'SEATBELT', 'PASSENGER_WINDOW', 'REAR_WINDOW'];
  const CarSeat = ['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_CENTER', 'ROW_2_RIGHT', 'ROW_3_LEFT', 'ROW_3_RIGHT'];
  const MissionKind = {
    WINDOW: '창문', MORSE: '등대', SIGNALS: '깜빡이', STEERING_HEAT: '따뜻한 핸들', SEAT_HEAT: '따뜻한 의자',
    WIPER: '와이퍼', MEMORY: '기억', RIDDLE: '수수께끼', CHAIN: '연쇄', SEATBELT: '찰칵',
    TWIN_WINDOW: '쌍둥이 창문', LIGHTNING: '번개',
  };
  const KINDS = Object.keys(MissionKind);
  const Phase = { TITLE: 'TITLE', MATCHING: 'MATCHING', INTRO: 'INTRO', PUZZLE: 'PUZZLE', SOLVED: 'SOLVED', ENDING: 'ENDING', PAUSED_DRIVING: 'PAUSED_DRIVING', PAUSED_DOOR: 'PAUSED_DOOR' };
  const CarWindow = ['ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_RIGHT'];

  /** VehicleState() defaults. windows: {CarWindow: 0..100}; belts/occupied/beltSeats: arrays of CarSeat. */
  function vehicleState(o) {
    return Object.assign({
      gear: Gear.PARK, speedKmh: 0, socPercent: 42, charging: true, chargeTargetPercent: 80,
      windowDriverPercent: 0, readingLightOn: false, turnSignal: 'OFF', steeringHeatLevel: 0, seatHeatDriverLevel: 0,
      wiperOn: false, occupants: 1, windows: {}, belts: [], occupied: [], beltSeats: [], steeringHeatMax: 2, seatHeatMax: 3,
      // v3: null / false = unknown or not reported
      chargePortOpen: null, rangeMeters: null, doorOpen: false, rearWindowLocked: false,
      driveMode: null, // DriveMode, read only (Pleos DrivingMode); null = no capability / unknown
    }, o || {});
  }
  const windowPercent = (s, area) => area == null ? s.windowDriverPercent : (s.windows[area] ?? 0);

  // ---------------------------------------------------------------- Puzzles.kt: specs
  const Spec = {
    Window: (target, o = {}) => ({ type: 'Window', target, tolerance: o.tolerance ?? 10, holdMs: o.holdMs ?? 2000, area: o.area ?? null }),
    Morse: (pattern, o = {}) => ({ type: 'Morse', pattern, longMs: o.longMs ?? 600, gapResetMs: o.gapResetMs ?? 2500 }),
    Signals: (target) => ({ type: 'Signals', target }),
    SteeringHeat: (level, o = {}) => ({ type: 'SteeringHeat', level, max: o.max ?? 2, holdMs: o.holdMs ?? 1500 }),
    SeatHeat: (level, o = {}) => ({ type: 'SeatHeat', level, max: o.max ?? 3, holdMs: o.holdMs ?? 1500 }),
    Wiper: (times, o = {}) => ({ type: 'Wiper', times, doneMs: o.doneMs ?? 2000 }),
    Memory: (target, o = {}) => ({ type: 'Memory', target, revealMs: o.revealMs ?? 8000 + 700 * target.length }),
    Riddle: (inner, question, math, answer) => ({ type: 'Riddle', inner, question, math, answer }),
    Chain: (steps) => ({ type: 'Chain', steps }),
    Belts: (o = {}) => ({ type: 'Belts', seats: o.seats ?? [], count: o.count ?? 1, ordered: o.ordered ?? false }),
    Twin: (o = {}) => ({ type: 'Twin', area: o.area ?? 'ROW_1_RIGHT', tolerance: o.tolerance ?? 10, min: o.min ?? 20, max: o.max ?? 80, holdMs: o.holdMs ?? 2000 }),
  };
  const need = (b) => b.seats.length === 0 ? b.count : b.seats.length;
  const windowInput = (area) => area == null ? 'WINDOW' : area === 'ROW_1_RIGHT' ? 'PASSENGER_WINDOW' : 'REAR_WINDOW';

  function input(s) {
    switch (s.type) {
      case 'Window': return windowInput(s.area);
      case 'Morse': return 'READING_LIGHT';
      case 'Signals': case 'Memory': return 'TURN_SIGNAL';
      case 'SteeringHeat': return 'STEERING_HEAT';
      case 'SeatHeat': return 'SEAT_HEAT';
      case 'Wiper': return 'WIPER';
      case 'Riddle': return input(s.inner);
      case 'Chain': return input(s.steps[0]);
      case 'Belts': return 'SEATBELT';
      case 'Twin': return windowInput(s.area);
    }
  }
  function inputs(s) {
    if (s.type === 'Riddle') return inputs(s.inner);
    if (s.type === 'Chain') return [...new Set(s.steps.flatMap(inputs))];
    if (s.type === 'Twin') return [...new Set(['WINDOW', input(s)])];
    return [input(s)];
  }

  const puzzle = (story, reward, spec, color, timedSec = null) => ({ story, reward, spec, color, timedSec });

  const sym = (s) => s === 'L' ? '왼쪽' : '오른쪽';
  const syms = (t) => t.map(sym).join(', ');
  const morseWords = (p) => [...p].map((c) => c === '.' ? '짧게' : '길게').join(', ');
  const windowWords = (t) => t <= 35 ? '3분의 1쯤' : t <= 60 ? '절반쯤' : '3분의 2쯤';
  const windowWho = (a) => a == null ? '운전석' : { ROW_1_RIGHT: '조수석', ROW_2_LEFT: '2열 왼쪽', ROW_2_RIGHT: '2열 오른쪽' }[a];
  const SEAT_NAME = { ROW_1_LEFT: '운전석', ROW_1_RIGHT: '조수석', ROW_2_LEFT: '2열 왼쪽', ROW_2_CENTER: '2열 가운데', ROW_2_RIGHT: '2열 오른쪽', ROW_3_LEFT: '3열 왼쪽', ROW_3_RIGHT: '3열 오른쪽' };
  const seatName = (s) => SEAT_NAME[s];
  const seatList = (s) => s.map(seatName).join(', ');

  function title(s) {
    switch (s.type) {
      case 'Window': return s.area == null ? '창문 금고' : `${windowWho(s.area)} 창문`;
      case 'Morse': return '독서등 등대';
      case 'Signals': return '깜빡이 암호';
      case 'SteeringHeat': return '따뜻한 핸들';
      case 'SeatHeat': return '따뜻한 의자';
      case 'Wiper': return '와이퍼 인사';
      case 'Memory': return '기억 금고';
      case 'Riddle': return '수수께끼 금고';
      case 'Chain': return s.steps.every((x) => x.type === 'Window') ? '릴레이 창문' : '연쇄 금고';
      case 'Belts': return s.ordered ? '찰칵 릴레이' : s.seats.length > 1 ? '모두 찰칵' : '찰칵 금고';
      case 'Twin': return '쌍둥이 창문';
    }
  }

  const ORD = ['첫째', '둘째', '셋째', '넷째'];
  function prompt(s) {
    switch (s.type) {
      case 'Window': return `${windowWho(s.area)} 창문을 ${windowWords(s.target)} 내려 보세요`;
      case 'Morse': return `독서등을 ${morseWords(s.pattern)} 순서로 켰다 꺼 보세요`;
      case 'Signals': return `방향지시등을 ${syms(s.target)} 순서로 켜 보세요`;
      case 'SteeringHeat': return `핸들 열선을 ${s.level}단으로 맞춰 보세요`;
      case 'SeatHeat': return `운전석 시트 열선을 ${s.level}단으로 맞춰 보세요`;
      case 'Wiper': return `와이퍼를 한 번만 닦기로 톡, ${s.times}번 움직여 보세요`;
      case 'Memory': return '깜빡이 순서를 잘 보고 외워요. 사라지면 똑같이 켜 보세요';
      case 'Riddle': { const c = clue(s.inner); return `답을 찾으면 ${c[0]}${c[1]}`; }
      case 'Chain': return '차례대로 해 보세요. ' + s.steps.map((st, i) => `${ORD[i] ?? `${i + 1}번`} ${chip(st)}`).join(', ');
      case 'Belts':
        if (s.seats.length === 0) return `앞자리에서 ${s.count}명이 벨트를 풀었다가 다시 찰칵 채워 보세요`;
        if (s.ordered) return `${seatList(s.seats)} 순서로 한 명씩 벨트를 다시 찰칵 채워 보세요`;
        if (s.seats.length === 1) return `${seatName(s.seats[0])} 자리만 벨트를 풀었다가 다시 찰칵 채워 보세요`;
        return `${seatList(s.seats)} 모두 벨트를 풀었다가 다시 찰칵 채워 보세요`;
      case 'Twin': return `운전석과 ${windowWho(s.area)} 창문을 똑같은 높이로 내려 보세요`;
    }
  }

  function chip(s) {
    switch (s.type) {
      case 'Window': return `${windowWho(s.area)} 창문 ${windowWords(s.target)}`;
      case 'Signals': return s.target.length === 1 ? `${sym(s.target[0])} 깜빡이` : `깜빡이 ${s.target.length}번`;
      case 'SteeringHeat': return `핸들 열선 ${s.level}단`;
      case 'SeatHeat': return `시트 열선 ${s.level}단`;
      case 'Wiper': return `와이퍼 ${s.times}번`;
      case 'Morse': return `독서등 신호 ${s.pattern.length}번`;
      default: return title(s);
    }
  }

  function clue(inner) {
    switch (inner.type) {
      case 'Wiper': return ['앞유리를 닦는 친구를 ', '그 수만큼 톡톡 움직여 보세요'];
      case 'SeatHeat': return ['운전석 엉덩이를 데워 주는 친구를 ', '그 단계로 맞춰 보세요'];
      case 'SteeringHeat': return ['손을 데워 주는 동그란 친구를 ', '그 단계로 맞춰 보세요'];
      default: return ['바람이 들어오는 운전석 문을 ', '10칸 중 그 칸만큼 내려 보세요'];
    }
  }
  function nudge(inner) {
    switch (inner.type) {
      case 'Wiper': return '앞유리를 닦는 친구는 바로 와이퍼예요.';
      case 'SeatHeat': return '엉덩이를 데워 주는 친구는 바로 시트 열선이에요.';
      case 'SteeringHeat': return '손을 데워 주는 동그란 친구는 바로 핸들 열선이에요.';
      default: return '바람이 들어오는 문은 바로 운전석 창문이에요.';
    }
  }

  function hints(s) {
    switch (s.type) {
      case 'Window': return [
        s.area == null ? '창문 버튼을 짧게 여러 번 눌러 조금씩 움직여 보세요.' : `${windowWho(s.area)}에 앉은 친구가 창문 버튼을 짧게 여러 번 눌러요.`,
        '바늘이 초록 칸에 들어가면 손을 떼고 기다려요.',
        `정답: ${windowWho(s.area)} 창문을 ${s.target}% 근처에 두고 2초 기다리기.`,
      ];
      case 'Morse': return ['짧게는 톡 켰다 끄기, 길게는 하나 둘 세고 끄기.', '중간에 오래 쉬면 처음부터 다시 시작해요.', `정답: ${morseWords(s.pattern)}.`];
      case 'Signals': return ['방향지시등 레버를 위아래로 움직여요.', '같은 방향을 또 하려면 가운데로 돌렸다가 다시 켜요.', `정답: ${syms(s.target)}.`];
      case 'SteeringHeat': return ['핸들 열선 버튼은 공조 화면이나 핸들 근처에 있어요.', '버튼을 누를 때마다 한 단씩 바뀌어요.', `정답: ${s.level}단에서 잠깐 기다리기.`];
      case 'SeatHeat': return ['시트 열선 버튼은 공조 화면에 있어요.', '버튼을 누를 때마다 한 단씩 바뀌어요.', `정답: 운전석 ${s.level}단에서 잠깐 기다리기.`];
      case 'Wiper': return ['와이퍼 레버를 한 번만 닦기 쪽으로 톡 움직였다가 놓아요.', '다 하고 2초 기다리면 개수를 세요.', `정답: 한 번만 닦기 ${s.times}번.`];
      case 'Memory': return ['왼쪽, 오른쪽을 소리 내어 따라 말하면 잘 외워져요.', `첫 번째는 ${sym(s.target[0])}이에요.`, `정답: ${syms(s.target)}.`];
      case 'Riddle': return [nudge(s.inner), `${s.math}, 답은 ${s.answer}!`,
        s.inner.type === 'Window' ? `정답: 운전석 창문 10칸 중 ${s.answer}칸.` : `정답: ${prompt(s.inner)}.`];
      case 'Chain': return ['칩 하나씩 차례로 해요. 칩에 불이 들어오면 다음 칩으로 가요.', '순서를 건너뛰면 다음 칩으로 넘어가지 않아요.',
        `정답: ${s.steps.map(prompt).join(', 그다음 ')}.`];
      case 'Belts': return ['벨트를 풀었다가 다시 찰칵 끼우면 한 번으로 세요.',
        s.ordered ? '적힌 순서대로 해요. 순서가 틀리면 처음부터 다시 해요.' : '한 사람씩 천천히 해도 괜찮아요.',
        s.seats.length === 0 ? `정답: 앞자리 ${s.count}곳에서 찰칵.` : `정답: ${seatList(s.seats)} 찰칵.`];
      case 'Twin': return [`${windowWho(s.area)} 친구와 운전석이 같이 창문 버튼을 눌러요.`,
        '두 바늘이 가까워지면 손을 떼고 기다려요. 끝까지 열거나 닫으면 안 돼요.', '정답: 두 창문 모두 절반쯤 내리고 2초 기다리기.'];
    }
  }

  const KIND = { Window: 'WINDOW', Morse: 'MORSE', Signals: 'SIGNALS', SteeringHeat: 'STEERING_HEAT', SeatHeat: 'SEAT_HEAT', Wiper: 'WIPER', Memory: 'MEMORY', Riddle: 'RIDDLE', Chain: 'CHAIN', Belts: 'SEATBELT', Twin: 'TWIN_WINDOW' };
  const kind = (s) => KIND[s.type];

  function number(s) {
    switch (s.type) {
      case 'Wiper': return s.times;
      case 'SeatHeat': case 'SteeringHeat': return s.level;
      case 'Window': return s.area == null ? Math.trunc(s.target / 10) : null;
      default: return null;
    }
  }

  function defaultSpec(i) {
    switch (i) {
      case 'WINDOW': return Spec.Window(50);
      case 'READING_LIGHT': return Spec.Morse('.-');
      case 'TURN_SIGNAL': return Spec.Signals(['L', 'R', 'L']);
      case 'STEERING_HEAT': return Spec.SteeringHeat(1);
      case 'SEAT_HEAT': return Spec.SeatHeat(2);
      case 'WIPER': return Spec.Wiper(2);
      case 'SEATBELT': return Spec.Belts({ count: 1 });
      case 'PASSENGER_WINDOW': return Spec.Window(50, { area: 'ROW_1_RIGHT' });
      case 'REAR_WINDOW': return Spec.Window(50, { area: 'ROW_2_RIGHT' });
    }
  }

  /** Swap puzzles whose inputs the car lacks (see Puzzles.kt substitute). supported: array of VehicleInput. */
  function substitute(puzzles, supported) {
    const sup = new Set(supported);
    const picks = VehicleInput.filter((i) => sup.has(i) && i !== 'REAR_WINDOW');
    if (picks.length === 0) return puzzles;
    const ok = (s) => inputs(s).every((i) => sup.has(i));
    const used = {};
    puzzles.map((p) => input(p.spec)).filter((i) => sup.has(i)).forEach((i) => { used[i] = (used[i] || 0) + 1; });
    return puzzles.map((p) => {
      const spec = p.spec;
      if (ok(spec)) return p;
      if (spec.type === 'Chain' && spec.steps.some(ok)) {
        const st = spec.steps.filter(ok);
        return { ...p, spec: st.length === 1 ? st[0] : Spec.Chain(st) };
      }
      // 2nd row window missing or child-locked: the same window mission moves to a front window.
      if (spec.type === 'Window' && input(spec) === 'REAR_WINDOW' && (sup.has('PASSENGER_WINDOW') || sup.has('WINDOW')))
        return { ...p, spec: { ...spec, area: sup.has('PASSENGER_WINDOW') ? 'ROW_1_RIGHT' : null } };
      let cands = picks.filter((i) => i !== 'WIPER' || (used[i] || 0) === 0);
      if (cands.length === 0) cands = picks;
      let best = cands[0];
      for (const c of cands) if ((used[c] || 0) < (used[best] || 0)) best = c; // minBy: first minimum
      used[best] = (used[best] || 0) + 1;
      return { ...p, spec: defaultSpec(best) };
    });
  }

  // ---- randomization
  const WINDOW_TARGETS = [30, 50, 70];
  const ITEMS = [['풍선', '개가 날아가면'], ['사과', '개를 먹으면'], ['별', '개가 숨으면'], ['쿠키', '개를 먹으면']];
  const FRONT = ['ROW_1_LEFT', 'ROW_1_RIGHT'];
  const lr = (rnd, n) => Array.from({ length: n }, () => rnd.nextBoolean() ? 'L' : 'R');

  function reroll(s, rnd, seats, people) {
    switch (s.type) {
      case 'Window': { let t = pick(WINDOW_TARGETS, rnd); if (s.area === 'ROW_2_LEFT' || s.area === 'ROW_2_RIGHT') t = Math.min(t, 50); return { ...s, target: t }; }
      case 'Morse': { const n = rnd.nextIntIn(2, 5); let p = ''; for (let i = 0; i < n; i++) p += rnd.nextBoolean() ? '.' : '-'; return { ...s, pattern: p }; }
      case 'Signals': return { ...s, target: lr(rnd, rnd.nextIntIn(3, 6)) };
      case 'Memory': return Spec.Memory(lr(rnd, rnd.nextIntIn(3, 6)));
      case 'SteeringHeat': return { ...s, level: rnd.nextIntIn(1, s.max + 1) };
      case 'SeatHeat': return { ...s, level: rnd.nextIntIn(1, s.max + 1) };
      case 'Wiper': return { ...s, times: rnd.nextIntIn(1, 4) };
      case 'Twin': return s;
      case 'Chain': return { ...s, steps: s.steps.map((x) => reroll(x, rnd, seats, people)) };
      case 'Riddle': return riddle(reroll(s.inner, rnd, seats, people), rnd);
      case 'Belts': {
        const all = rnd.nextBoolean();
        const pk = rnd.nextIntIn(FRONT.length);
        const occ = FRONT.filter((x) => seats.includes(x));
        if (occ.length >= 2 && (s.ordered || all)) return Spec.Belts({ seats: occ, ordered: s.ordered });
        if (occ.length > 0) return Spec.Belts({ seats: [occ[pk % occ.length]] });
        return Spec.Belts({ count: Math.min(Math.max(people, 1), FRONT.length) });
      }
    }
  }

  function riddle(inner, rnd) {
    const n = number(inner);
    if (n == null) return inner;
    const [item, gone] = pick(ITEMS, rnd);
    const win = inner.type === 'Window' ? { ...inner, tolerance: 4 } : inner;
    if (n >= 2 && rnd.nextBoolean()) {
      const a = rnd.nextIntIn(1, n);
      return Spec.Riddle(win, `${item} ${a}개에 ${n - a}개를 더하면 몇 개일까요?`, `${a} 더하기 ${n - a}`, n);
    }
    const b = rnd.nextIntIn(1, 3);
    return Spec.Riddle(win, `${item} ${n + b}개 중에 ${b}${gone} 몇 개가 남을까요?`, `${n + b} 빼기 ${b}`, n);
  }

  const TWISTS = ['MEMORY', 'RIDDLE', 'CHAIN', 'TIMED'];
  function fits(t, p) {
    switch (t) {
      case 'MEMORY': return p.spec.type === 'Signals';
      case 'RIDDLE': return number(p.spec) != null;
      case 'CHAIN': return ['Window', 'Signals', 'SeatHeat', 'SteeringHeat', 'Morse'].includes(p.spec.type);
      case 'TIMED': return p.timedSec == null;
    }
  }
  function applyTwist(t, p, rnd, avoid = []) {
    switch (t) {
      case 'MEMORY': return { ...p, spec: Spec.Memory(p.spec.target) };
      case 'RIDDLE': return { ...p, spec: riddle(p.spec, rnd) };
      case 'CHAIN': {
        const list = [];
        list.push(Spec.Signals(lr(rnd, 1)));
        list.push(Spec.SeatHeat(rnd.nextIntIn(1, 4)));
        list.push(Spec.SteeringHeat(rnd.nextIntIn(1, 3)));
        list.push(Spec.Window(pick(WINDOW_TARGETS, rnd)));
        const sh = shuffled(list.filter((x) => input(x) !== input(p.spec) && !avoid.includes(input(x))), rnd);
        const extras = sh.slice(0, rnd.nextIntIn(1, 3));
        return { ...p, spec: Spec.Chain(shuffled([p.spec, ...extras], rnd)) };
      }
      case 'TIMED': {
        const ty = p.spec.type;
        return { ...p, timedSec: ty === 'Chain' ? 90 : ty === 'Riddle' ? 75 : (ty === 'Memory' || ty === 'Belts' || ty === 'Twin') ? 60 : 45 };
      }
    }
  }

  /** Puzzles for one play (see Puzzles.kt roll). seats: occupied front-belt seats (array). */
  function roll(ep, rnd, seats = [], people = 1) {
    if (ep.fixed) return ep.puzzles;
    const ps = ep.puzzles;
    const order = ps.length > 2 ? [ps[0], ...shuffled(ps.slice(1, ps.length - 1), rnd), ps[ps.length - 1]] : ps;
    const out = order.map((p) => ({ ...p, spec: reroll(p.spec, rnd, seats, people) }));
    const twisted = new Set();
    const ts = shuffled(TWISTS, rnd).slice(0, ep.twists);
    const sorted = ts.filter((t) => t !== 'TIMED').concat(ts.filter((t) => t === 'TIMED')); // stable sortedBy { it == TIMED }
    for (const t of sorted) {
      const idx = shuffled(out.map((_, i) => i), rnd).find((i) => (t === 'TIMED' || !twisted.has(i)) && fits(t, out[i]));
      if (idx === undefined) continue;
      out[idx] = applyTwist(t, out[idx], rnd, ep.avoid || []);
      if (t !== 'TIMED') twisted.add(idx);
    }
    return out;
  }

  /** Heat levels within the car's capability max; a riddle whose answer moved is asked again. */
  function fit(s, vs) {
    switch (s.type) {
      case 'SteeringHeat': { const m = Math.max(vs.steeringHeatMax, 1); return { ...s, level: Math.min(s.level, m), max: m }; }
      case 'SeatHeat': { const m = Math.max(vs.seatHeatMax, 1); return { ...s, level: Math.min(s.level, m), max: m }; }
      case 'Chain': return { ...s, steps: s.steps.map((x) => fit(x, vs)) };
      case 'Riddle': { const it = fit(s.inner, vs); return number(it) === s.answer ? { ...s, inner: it } : riddle(it, KRandom.ofInt(s.answer)); }
      default: return s;
    }
  }

  // ---- demo steps (DemoStep(waitMs, change)): used by QA and the selftest
  const withWindow = (v, area, x) => area == null ? { ...v, windowDriverPercent: x } : { ...v, windows: { ...v.windows, [area]: x } };
  const step = (waitMs, change) => ({ waitMs, change });
  function demo(s) {
    switch (s.type) {
      case 'Window': return [step(300, (v) => withWindow(v, s.area, s.target > 50 ? 0 : 100)), step(s.holdMs + 300, (v) => withWindow(v, s.area, s.target))];
      case 'Morse': return [step(s.gapResetMs + 100, (v) => ({ ...v, readingLightOn: false }))].concat(
        [...s.pattern].flatMap((c) => [step(c === '.' ? 200 : s.longMs + 300, (v) => ({ ...v, readingLightOn: true })), step(300, (v) => ({ ...v, readingLightOn: false }))]));
      case 'Signals': return [step(300, (v) => ({ ...v, turnSignal: 'OFF' }))].concat(
        s.target.flatMap((x) => [step(300, (v) => ({ ...v, turnSignal: x === 'L' ? 'LEFT' : 'RIGHT' })), step(300, (v) => ({ ...v, turnSignal: 'OFF' }))]));
      case 'SteeringHeat': return [step(300, (v) => ({ ...v, steeringHeatLevel: s.level === s.max ? 0 : s.max })), step(s.holdMs + 300, (v) => ({ ...v, steeringHeatLevel: s.level }))];
      case 'SeatHeat': return [step(300, (v) => ({ ...v, seatHeatDriverLevel: s.level === s.max ? 0 : s.max })), step(s.holdMs + 300, (v) => ({ ...v, seatHeatDriverLevel: s.level }))];
      case 'Wiper': {
        const out = [step(s.doneMs + 300, (v) => ({ ...v, wiperOn: false }))];
        for (let i = 0; i < s.times; i++) out.push(step(300, (v) => ({ ...v, wiperOn: true })), step(300, (v) => ({ ...v, wiperOn: false })));
        out.push(step(s.doneMs + 300, (v) => v));
        return out;
      }
      case 'Memory': return [step(s.revealMs + 300, (v) => ({ ...v, turnSignal: 'OFF' }))].concat(demo(Spec.Signals(s.target)));
      case 'Riddle': return demo(s.inner);
      case 'Chain': return s.steps.flatMap(demo);
      case 'Belts': {
        const order = s.seats.length ? s.seats : CarSeat.slice(0, s.count);
        return [step(300, (v) => ({ ...v, belts: v.belts.filter((x) => !order.includes(x)) }))].concat(
          order.map((seat) => step(300, (v) => ({ ...v, belts: v.belts.includes(seat) ? v.belts : [...v.belts, seat] }))));
      }
      case 'Twin': return [step(300, (v) => withWindow(withWindow(v, null, 0), s.area, 0)), step(s.holdMs + 300, (v) => withWindow(withWindow(v, null, 50), s.area, 50))];
    }
  }

  // ---- PuzzleRun
  const WIPE_CAP = 6;
  const Out = { NONE: 'NONE', TICK: 'TICK', WRONG: 'WRONG', SOLVED: 'SOLVED', TIRED: 'TIRED' };

  class PuzzleRun {
    constructor(spec, start) {
      this.spec = spec;
      this.prev = start;
      this.armed = false;
      this.holdSince = null;
      this.entered = '';
      this.seq = [];
      this.pressAt = null;
      this.lastActAt = 0;
      this.wipes = 0;
      this.totalWipes = 0;
      this.wiperArmed = false;
      this.clicked = [];
      this.startAt = null;
      this.stepIdx = 0;
      this.sub = spec.type === 'Riddle' ? new PuzzleRun(spec.inner, start) : spec.type === 'Chain' ? new PuzzleRun(spec.steps[0], start) : null;
      switch (spec.type) {
        case 'Window': this.armed = !this.inBand(spec, start); break;
        case 'Twin': this.armed = !this.twinOk(spec, start); break;
        case 'SteeringHeat': this.armed = start.steeringHeatLevel !== spec.level; break;
        case 'SeatHeat': this.armed = start.seatHeatDriverLevel !== spec.level; break;
        default: this.armed = true;
      }
    }
    get input() { return this.spec.type === 'Chain' && this.spec.steps[this.stepIdx] ? input(this.spec.steps[this.stepIdx]) : input(this.spec); }
    get needsWiggle() { return this.sub ? this.sub.needsWiggle : !this.armed; }
    inBand(w, s) { return Math.abs(windowPercent(s, w.area) - w.target) <= w.tolerance; }
    twinOk(t, s) {
      const a = s.windowDriverPercent, b = windowPercent(s, t.area);
      return a >= t.min && a <= t.max && b >= t.min && b <= t.max && Math.abs(a - b) <= t.tolerance;
    }
    step(s, now) {
      const sp = this.spec;
      let o;
      switch (sp.type) {
        case 'Window': o = this.hold(this.inBand(sp, s), sp.holdMs, now); break;
        case 'Twin': o = this.hold(this.twinOk(sp, s), sp.holdMs, now); break;
        case 'SteeringHeat': o = this.hold(s.steeringHeatLevel === sp.level, sp.holdMs, now); break;
        case 'SeatHeat': o = this.hold(s.seatHeatDriverLevel === sp.level, sp.holdMs, now); break;
        case 'Morse': o = this.morse(sp, s, now); break;
        case 'Signals': o = this.signals(sp, s); break;
        case 'Wiper': o = this.wiper(sp, s, now); break;
        case 'Belts': o = this.belts(sp, s); break;
        case 'Riddle': o = this.sub.step(s, now); break;
        case 'Memory': o = this.memory(sp, s, now); break;
        case 'Chain': o = this.chain(sp, s, now); break;
      }
      this.prev = s;
      return o;
    }
    hold(ok, holdMs, now) {
      if (!ok) { this.armed = true; this.holdSince = null; return Out.NONE; }
      if (!this.armed) return Out.NONE;
      if (this.holdSince == null) { this.holdSince = now; return Out.TICK; }
      return now - this.holdSince >= holdMs ? Out.SOLVED : Out.NONE;
    }
    check(done, prefix, reset) {
      if (done) return Out.SOLVED;
      if (prefix) return Out.TICK;
      reset(); return Out.WRONG;
    }
    morse(m, s, now) {
      const on = s.readingLightOn;
      if (!this.prev.readingLightOn && this.entered.length && now - this.lastActAt >= m.gapResetMs) this.entered = '';
      if (on && !this.prev.readingLightOn) { this.pressAt = now; return Out.NONE; }
      if (!on && this.prev.readingLightOn) {
        const at = this.pressAt;
        if (at == null) return Out.NONE;
        this.pressAt = null;
        this.lastActAt = now;
        this.entered += now - at < m.longMs ? '.' : '-';
        return this.check(this.entered === m.pattern, m.pattern.startsWith(this.entered), () => { this.entered = ''; });
      }
      return Out.NONE;
    }
    signals(g, s) {
      const sig = s.turnSignal;
      if (sig === this.prev.turnSignal || (sig !== 'LEFT' && sig !== 'RIGHT')) return Out.NONE;
      this.seq.push(sig === 'LEFT' ? 'L' : 'R');
      return this.check(eqList(this.seq, g.target), eqList(g.target.slice(0, this.seq.length), this.seq), () => { this.seq = []; });
    }
    wiper(w, s, now) {
      if (!this.prev.wiperOn && !s.wiperOn && this.wipes > 0 && now - this.lastActAt >= w.doneMs) {
        const ok = this.wipes === w.times;
        this.wipes = 0;
        return ok ? Out.SOLVED : this.wrongWipe();
      }
      if (s.wiperOn && !this.prev.wiperOn) { this.wiperArmed = true; return Out.NONE; }
      if (!s.wiperOn && this.prev.wiperOn && this.wiperArmed) {
        this.wiperArmed = false;
        this.wipes++;
        this.totalWipes++;
        this.lastActAt = now;
        if (this.wipes > w.times) { this.wipes = 0; return this.wrongWipe(); }
        return Out.TICK;
      }
      return Out.NONE;
    }
    wrongWipe() { return this.totalWipes >= WIPE_CAP ? Out.TIRED : Out.WRONG; }
    belts(b, s) {
      let out = Out.NONE;
      for (const seat of CarSeat.filter((x) => s.belts.includes(x) && !this.prev.belts.includes(x))) {
        if (b.seats.length === 0 && !FRONT.includes(seat)) continue;
        if (b.ordered) {
          if (b.seats[this.clicked.length] === seat) this.clicked.push(seat);
          else {
            this.clicked = [];
            if (b.seats[0] === seat) this.clicked.push(seat);
            out = Out.WRONG;
            continue;
          }
        } else if (this.clicked.includes(seat)) continue;
        else if (b.seats.length && !b.seats.includes(seat)) { out = Out.WRONG; continue; }
        else this.clicked.push(seat);
        if (this.clicked.length >= need(b)) return Out.SOLVED;
        out = Out.TICK;
      }
      return out;
    }
    memory(m, s, now) {
      if (this.startAt == null) this.startAt = now;
      if (!this.sub) {
        if (now - this.startAt >= m.revealMs) this.sub = new PuzzleRun(Spec.Signals(m.target), s);
        return Out.NONE;
      }
      return this.sub.step(s, now);
    }
    chain(c, s, now) {
      const o = this.sub.step(s, now);
      if (o !== Out.SOLVED) return o;
      if (++this.stepIdx === c.steps.length) return Out.SOLVED;
      this.sub = new PuzzleRun(c.steps[this.stepIdx], s);
      return Out.TICK;
    }
    visual(s, now) {
      const sp = this.spec;
      switch (sp.type) {
        case 'Window': return { type: 'Dial', target: sp.target, current: windowPercent(s, sp.area), tolerance: sp.tolerance };
        case 'Morse': return { type: 'Morse', pattern: sp.pattern, entered: this.entered };
        case 'Signals': return { type: 'Sequence', target: sp.target, entered: this.seq.slice() };
        case 'SteeringHeat': return { type: 'Level', target: sp.level, current: s.steeringHeatLevel, max: sp.max };
        case 'SeatHeat': return { type: 'Level', target: sp.level, current: s.seatHeatDriverLevel, max: sp.max };
        case 'Wiper': return { type: 'Level', target: sp.times, current: this.wipes, max: sp.times };
        case 'Belts': return { type: 'Seats', target: sp.seats, need: need(sp), done: this.clicked.slice(), buckled: s.belts.slice(), ordered: sp.ordered };
        case 'Twin': return { type: 'Twin', left: s.windowDriverPercent, right: windowPercent(s, sp.area), tolerance: sp.tolerance, min: sp.min, max: sp.max };
        case 'Chain': return { type: 'Chain', chips: sp.steps.map(chip), done: this.stepIdx, current: this.sub.visual(s, now) };
        case 'Riddle': {
          const v = this.sub.visual(s, now);
          if (v.type === 'Dial') return { type: 'Riddle', question: sp.question, current: Math.trunc((v.current + 5) / 10), max: 10 };
          if (v.type === 'Level') return { type: 'Riddle', question: sp.question, current: v.current, max: v.max };
          return { type: 'Riddle', question: sp.question, current: 0, max: sp.answer };
        }
        case 'Memory': {
          const left = this.sub == null ? Math.max(0, sp.revealMs - (now - (this.startAt ?? now))) : 0;
          const entered = this.sub ? (this.sub.visual(s, now).entered || []) : [];
          return { type: 'Memory', target: left > 0 ? sp.target : sp.target.map(() => '?'), entered, revealLeftMs: left };
        }
      }
    }
  }

  // ---------------------------------------------------------------- Episodes.kt
  const Episodes = {
    map15: {
      id: 'map-15', title: '꼬마 탐정과 충전소 지도', minutes: 15,
      intro: '충전소에 보물 지도가 숨어 있대요. 우리 차가 단서를 쥐고 있어요.',
      outro: '보물 지도를 모두 모았어요.', color: 0x3D7BFF, twists: 1, fixed: false,
      // 1화: no turn signals (they confuse the charger queue) and no wipers on dry glass, not even through twists or swaps.
      recap: '', teaser: '다음 충전 때, 우리 차는 우주선이 돼요.', avoid: ['TURN_SIGNAL', 'WIPER'],
      puzzles: [
        puzzle('지도 첫 조각이 창문 금고에 잠겨 있어요.', '찰칵! 지도 첫 조각을 찾았어요.', Spec.Window(50), 0x3D7BFF),
        puzzle('지도 한 조각이 핸들 안에서 꽁꽁 얼었어요.', '핸들이 따뜻해지자 지도 조각이 녹아 나왔어요.', Spec.SteeringHeat(1), 0xFF5C8A),
        puzzle('작은 등대가 불빛 신호를 기다려요.', '등대가 깜빡! 또 한 조각이 나왔어요.', Spec.Morse('.-'), 0x9B6BFF),
        puzzle('마지막 조각은 따뜻한 자리를 좋아해요.', '지도가 완성됐어요! 보물은 바로 우리 가족이에요.', Spec.SeatHeat(2), 0xFF7A45),
      ],
    },
    family20: {
      id: 'family-20', title: '아이오닉 9 우주선 출발 준비', minutes: 20,
      intro: '우리 아이오닉 9가 오늘은 우주선이에요! 충전하는 동안 온 가족이 힘을 모아 출발 준비를 해요.',
      outro: '출발 준비 완료! 온 가족이 한 팀이었어요.', color: 0x2B59C3, twists: 2, fixed: false,
      recap: '지난 충전 때 우리는 충전소 지도를 완성했어요.', teaser: '다음 충전 때, 별빛 우체부가 찾아와요.', avoid: [],
      puzzles: [
        puzzle('첫 점검은 좌석이에요. 우주선 벨트가 느슨해졌대요.', '찰칵찰칵! 좌석 점검 끝이에요.', Spec.Belts(), 0x3D7BFF),
        puzzle('양쪽 날개 창문이 같은 높이여야 균형이 맞아요.', '날개가 반듯해졌어요!', Spec.Twin(), 0x2EC4B6),
        puzzle('관제탑이 깜빡이 신호를 잠깐 보여 주고 숨겨요.', '관제탑이 좋대요! 신호를 기억했어요.', Spec.Memory(['L', 'R', 'L']), 0xFFB020),
        puzzle('앞자리부터 뒷자리까지 창문으로 별가루를 받아요.', '별가루가 가득 모였어요!',
          Spec.Chain([Spec.Window(50), Spec.Window(50, { area: 'ROW_1_RIGHT' }), Spec.Window(50, { area: 'ROW_2_RIGHT' })]), 0x9B6BFF),
        puzzle('마지막 점검! 앞자리 벨트를 찰칵 소리로 확인해요.', '모두 준비됐어요! 카운트다운 시작!', Spec.Belts({ ordered: true }), 0xFF7A45),
      ],
    },
    letter30: {
      id: 'letter-30', title: '별빛 우체부의 잃어버린 편지', minutes: 30,
      intro: '별빛 우체부가 편지 여섯 장을 차 안에 떨어뜨렸어요. 함께 찾아 줄래요?',
      outro: '편지 여섯 장이 모두 주인을 찾았어요.', color: 0x5B5BD6, twists: 3, fixed: false,
      recap: '지난 충전 때 우리 차는 우주선이 되어 출발 준비를 마쳤어요.', teaser: '다음 충전 때, 4화가 열려요.', avoid: [],
      puzzles: [
        puzzle('첫 편지는 차가운 의자 밑에서 떨고 있어요.', '따뜻해지자 편지가 나왔어요!', Spec.SeatHeat(2), 0xFF7A45),
        puzzle('편지 한 장이 창문 틈에 끼어 있어요.', '창문 틈에서 편지를 꺼냈어요.', Spec.Window(30), 0x3D7BFF),
        puzzle('우체부의 등불이 암호를 깜빡여요.', '암호가 풀렸어요! 편지를 한 장 더 찾았어요.', Spec.Morse('-.-'), 0x9B6BFF),
        puzzle('편지에 오른쪽, 왼쪽 길이 그려져 있어요.', '길을 따라가니 편지가 또 있어요.', Spec.Signals(['R', 'R', 'L']), 0xFFB020),
        puzzle('편지 한 장이 핸들 품에서 잠들었어요.', '핸들이 따뜻해지자 편지가 깨어났어요.', Spec.SteeringHeat(1), 0xFF5C8A),
        puzzle('마지막 편지는 앞유리 너머로 날아갔어요.', '와이퍼가 편지를 쓸어 왔어요!', Spec.Wiper(3), 0x2EC4B6),
      ],
    },
    /** 4화, season 1 finale. Continues 3화. 2nd row window stays at half or below (reroll). */
    moon25: {
      id: 'moon-25', title: '별빛 우체부와 달님의 답장', minutes: 25,
      intro: '지난번 편지를 받은 달님이 답장을 보냈대요. 별빛 우체부와 함께 답장 다섯 장을 모아 볼까요?',
      outro: '달님의 답장 다섯 장을 모두 모았어요. 시즌 1 이야기는 여기까지예요.', color: 0x3A4FA8, twists: 2, fixed: false,
      recap: '지난 충전 때 편지 여섯 장이 모두 주인을 찾았어요.', teaser: '시즌 2에서 또 만나요.', avoid: [],
      puzzles: [
        puzzle('첫 답장은 핸들 품에서 쿨쿨 자고 있어요.', '핸들이 따뜻해지자 첫 답장이 기지개를 켰어요.', Spec.SteeringHeat(1), 0xFF5C8A),
        puzzle('달님이 2열 창문에 답장 신호를 비춰요.', '2열 창문이 신호를 받았어요! 답장이 한 장 더 모였어요.', Spec.Window(50, { area: 'ROW_2_RIGHT' }), 0x3D7BFF),
        puzzle('우체부의 등불이 달님의 암호를 깜빡여요.', '암호가 풀렸어요! 답장이 또 한 장 나왔어요.', Spec.Morse('-..'), 0x9B6BFF),
        puzzle('답장 봉투에 왼쪽, 오른쪽 별길이 그려져 있어요.', '별길 끝에서 답장을 찾았어요.', Spec.Signals(['L', 'R', 'R']), 0xFFB020),
        puzzle('마지막 답장은 포근한 의자 위에 내려앉았어요.', '답장 다섯 장이 모두 모였어요! 달님이 방긋 웃어요.', Spec.SeatHeat(1), 0xFF7A45),
      ],
    },
  };
  /** Season 1 in order: index + 1 = N화. */
  Episodes.all = [Episodes.map15, Episodes.family20, Episodes.letter30, Episodes.moon25];

  // ---------------------------------------------------------------- ChargeClock.kt
  class ChargeClock {
    constructor(o = {}) {
      this.windowMs = o.windowMs ?? 600000; this.minSpanMs = o.minSpanMs ?? 90000; this.sampleEveryMs = o.sampleEveryMs ?? 5000;
      this.stallMs = o.stallMs ?? 300000; this.minRatePerMin = o.minRatePerMin ?? 0.02;
      this.samples = [];
    }
    add(now, soc) {
      const last = this.samples[this.samples.length - 1];
      if (last && now - last[0] < this.sampleEveryMs) return;
      this.samples.push([now, soc]);
      while (now - this.samples[0][0] > this.windowMs) this.samples.shift();
    }
    reset() { this.samples = []; }
    ratePerMin() {
      const sm = this.samples;
      if (sm.length < 3) return null;
      const t0 = sm[0][0], end = sm[sm.length - 1][0];
      if (end - t0 < this.minSpanMs) return null;
      if (end - t0 >= this.stallMs) {
        const recent = sm.filter((x) => end - x[0] <= this.stallMs).map((x) => x[1]);
        if (Math.max(...recent) - Math.min(...recent) < 0.5) return null;
      }
      const xs = sm.map((x) => (x[0] - t0) / 60000), ys = sm.map((x) => x[1]);
      const mx = xs.reduce((a, b) => a + b, 0) / xs.length, my = ys.reduce((a, b) => a + b, 0) / ys.length;
      let den = 0, num = 0;
      for (let i = 0; i < xs.length; i++) { den += (xs[i] - mx) ** 2; num += (xs[i] - mx) * (ys[i] - my); }
      if (den === 0) return null;
      const slope = num / den;
      return slope >= this.minRatePerMin ? slope : null;
    }
    etaMinutes(soc, target, charging) {
      if (!charging) return null;
      if (soc >= target) return 0;
      const r = this.ratePerMin();
      if (r == null) return null;
      return Math.min(Math.ceil((target - soc) / r), 999);
    }
  }

  // ---------------------------------------------------------------- Sim.kt
  const SIM_CHARGE_PER_MIN = 1, TIMELAPSE = 30;
  /** Demo range per SoC percent: about 500 km at 100 %. */
  const SIM_METERS_PER_PERCENT = 5000;
  /** SoC climbs toward the target while charging; a known range climbs with it. */
  function simCharge(s, dtMs, speedup = 1) {
    if (!s.charging || s.socPercent >= s.chargeTargetPercent) return s;
    const soc = Math.min(s.socPercent + SIM_CHARGE_PER_MIN * speedup * dtMs / 60000, s.chargeTargetPercent);
    return { ...s, socPercent: soc, rangeMeters: s.rangeMeters == null ? s.rangeMeters : s.rangeMeters + (soc - s.socPercent) * SIM_METERS_PER_PERCENT };
  }

  // ---------------------------------------------------------------- EscapeGame.kt
  const WIGGLE = '지금은 이미 맞춰져 있어요. 한 번 움직였다가 다시 맞춰 보세요.';
  const INTRO_MS = 5000, SOLVED_MS = 3000, REPLUG_MS = 60000;
  const RECAP_MS = 4000; // the "지난 화 한 줄" needs a few more seconds before the first puzzle
  const RESUME_ASK = '이어서 할까요?';
  const READY_TO_GO = '출발 준비 완료!';
  const WINDOW_SLACK = 5; // percent; raw window steps can be coarse
  const WINDOW_NAMES = { ROW_1_RIGHT: '조수석 창문', ROW_2_LEFT: '2열 왼쪽 창문', ROW_2_RIGHT: '2열 오른쪽 창문' };
  const GREEN = 0x3CD070, GOLD = 0xFFC845, WAIT_BLUE = 0x4A7BD0;

  /** 3 = no hint and no skip, 2 = no skip and at most one hint per puzzle on average, else 1. */
  const stars = (h, sk, n) => sk === 0 && h === 0 ? 3 : sk === 0 && h <= n ? 2 : 1;
  const ACTIVE = [Phase.INTRO, Phase.PUZZLE, Phase.SOLVED];

  class EscapeGame {
    constructor(o = {}) {
      this.episodes = o.episodes ?? Episodes.all;
      this.supported = o.supported ?? VehicleInput.slice();
      this.random = o.random ?? KRandom.any();
      this.listeners = [];
      this.log = [];
      this.clock = new ChargeClock();
      this.vs = vehicleState();
      this.lastNow = null;
      this.phase = Phase.TITLE;
      this.pausedFrom = null;
      this.episode = null;
      this.puzzles = [];
      this.index = 0;
      this.run = null;
      this.hintsUsed = 0;
      this.elapsedMs = 0;
      this.phaseAt = 0;
      this.story = '';
      this.endingText = '';
      this.waitingForCharge = false;
      this.daily = false;
      this.hintsTotal = 0;
      this.skips = 0;
      this.lightning = 0;
      this.bonusUntil = null;
      this.solvedKinds = new Set();
      this.starsN = 0;
      this.summary = '';
      this.replugBy = null;
      this.startVs = null; // car at episode start, for 원래대로 출발
      this.restoreDone = false; // "출발 준비 완료" already said
      this.nextIndex = 0; // serial: episodes[nextIndex] is the next 화
      this.readyCard = false; // TITLE: port opened, "N화가 준비됐어요"
      this.lastPort = null;
      this.doorFrom = null;
      this.doorAsk = false;
      this.recap = '';
      this.introMs = INTRO_MS;
      this.ui = null;
      this.render();
    }
    /** Serial progress (충전 1회 = 1화): index of the next 화. The app saves it and sets it back on launch. */
    get nextEpisodeIndex() { return this.nextIndex; }
    set nextEpisodeIndex(v) { const n = this.episodes.length; this.nextIndex = n === 0 ? 0 : ((v % n) + n) % n; this.render(); }
    /** listener(event) gets {type:'Speak',text} | {type:'Sfx',kind} | {type:'Ambient',color,brightness} | {type:'AmbientRestore'} | {type:'Silence'} | {type:'Ui',ui} */
    on(fn) { this.listeners.push(fn); }
    setSupportedInputs(i) { this.supported = i; }
    recommend() {
      const eta = this.eta();
      const byLen = this.episodes.slice().sort((a, b) => a.minutes - b.minutes);
      if (eta != null) { const fitIn = byLen.filter((e) => e.minutes <= eta); if (fitIn.length) return fitIn[fitIn.length - 1]; }
      return byLen[0];
    }
    currentPuzzles() { return this.puzzles; }
    demoSolution(i) { const p = this.puzzles[i]; return p ? demo(p.spec) : []; }
    eta() { return this.clock.etaMinutes(this.vs.socPercent, this.vs.chargeTargetPercent, this.vs.charging); }
    chargeDone() { return this.vs.socPercent >= this.vs.chargeTargetPercent; }

    emit(e) { this.log.push(e); for (const f of this.listeners) f(e); }
    speak(t) { this.emit({ type: 'Speak', text: t }); }
    sfx(k) { this.emit({ type: 'Sfx', kind: k }); }
    ambient(c, b) { this.emit({ type: 'Ambient', color: c, brightness: b }); }

    onVehicle(state, now) {
      const gap = this.lastNow != null ? now - this.lastNow : 0;
      const dt = Math.min(Math.max(gap, 0), 5000);
      if (gap > 5000) this.bonusUntil = null;
      this.lastNow = now;
      this.vs = state;
      if (state.charging) { this.clock.add(now, state.socPercent); this.replugBy = null; } else this.clock.reset();
      // Port closed -> open while the title is up: offer the next 화. Unknown (null) never triggers it.
      const port = state.chargePortOpen ?? null;
      if (this.lastPort === false && port === true && this.phase === Phase.TITLE) this.readyCard = true;
      if (port != null) this.lastPort = port;

      const driving = state.gear !== Gear.PARK || state.speedKmh > 0.5;
      if (driving) {
        if (this.phase !== Phase.PAUSED_DRIVING) {
          this.pausedFrom = this.phase;
          this.phase = Phase.PAUSED_DRIVING;
          this.emit({ type: 'AmbientRestore' });
        }
        return this.render();
      }
      if (this.phase === Phase.PAUSED_DRIVING) {
        this.phase = this.pausedFrom ?? Phase.TITLE;
        this.pausedFrom = null;
        if (!state.charging) this.replugBy = now + REPLUG_MS;
        if (this.phase === Phase.PUZZLE) {
          const p = this.puzzles[this.index];
          this.run = new PuzzleRun(p.spec, state);
          this.ambient(p.color, 60);
        }
        return this.render();
      }
      // Door open: voice and timers stop. Doors never mean "got out", only "wait".
      if (this.phase === Phase.PAUSED_DOOR) {
        if (state.doorOpen) this.doorAsk = false;
        else if (!this.doorAsk) { this.doorAsk = true; this.speak(RESUME_ASK); }
        return this.render();
      }
      if (state.doorOpen && ACTIVE.includes(this.phase)) {
        this.doorFrom = this.phase;
        this.phase = Phase.PAUSED_DOOR;
        this.doorAsk = false;
        this.emit({ type: 'Silence' });
        return this.render();
      }

      if (ACTIVE.includes(this.phase)) {
        this.elapsedMs += dt;
        if (this.chargeDone()) { this.finish(); return this.render(); }
      }
      switch (this.phase) {
        case Phase.INTRO: if (this.elapsedMs - this.phaseAt >= this.introMs) this.beginPuzzle(); break;
        case Phase.PUZZLE: {
          const o = this.run ? this.run.step(state, now) : null;
          if (o === Out.TICK) this.sfx('TICK');
          else if (o === Out.WRONG) this.sfx('WRONG');
          else if (o === Out.SOLVED) this.solved();
          else if (o === Out.TIRED) this.tired();
          break;
        }
        case Phase.SOLVED: if (this.elapsedMs - this.phaseAt >= SOLVED_MS) this.next(); break;
        case Phase.ENDING:
          if (this.waitingForCharge && (this.chargeDone() || this.chargeStopped())) this.finish();
          else if (!this.waitingForCharge && !this.restoreDone) { const l = this.restoreLeft(); if (l && l.length === 0) { this.restoreDone = true; this.speak(READY_TO_GO); } }
          break;
      }
      this.render();
    }

    startMatching() {
      this.readyCard = false;
      this.phase = Phase.MATCHING;
      this.speak('남은 충전 시간에 딱 맞는 이야기를 골랐어요.');
      this.render();
    }
    startDaily(id, epochDay) { this.startEpisode(id, epochDay); }
    /** seed != null: same puzzles for the same seed (오늘의 금고 uses epochDay). */
    startEpisode(id, seed = null) {
      const ep = this.episodes.find((e) => e.id === id);
      if (!ep) return;
      const vs = this.vs;
      this.episode = ep;
      this.daily = seed != null;
      const beltOk = vs.beltSeats.length === 0 ? vs.occupied : vs.occupied.filter((x) => vs.beltSeats.includes(x));
      // Child lock on: 2nd row window missions go to a front window (substitute()).
      const avoid = ep.avoid || [];
      const usable = this.supported.filter((i) => !avoid.includes(i) && !(vs.rearWindowLocked && i === 'REAR_WINDOW'));
      this.puzzles = substitute(roll(ep, seed != null ? KRandom.ofLong(seed) : this.random, beltOk, vs.occupants), usable)
        .map((p) => ({ ...p, spec: fit(p.spec, vs) }));
      const n = this.episodes.indexOf(ep);
      this.recap = n > 0 && n === this.nextIndex ? (ep.recap || '') : ''; // only when the family finished the 화 before
      this.introMs = this.recap === '' ? INTRO_MS : INTRO_MS + RECAP_MS;
      this.startVs = vs;
      this.restoreDone = false;
      this.readyCard = false;
      this.doorFrom = null;
      this.doorAsk = false;
      this.hintsTotal = 0; this.skips = 0; this.lightning = 0; this.bonusUntil = null; this.solvedKinds = new Set();
      this.starsN = 0; this.summary = ''; this.index = 0; this.run = null; this.elapsedMs = 0; this.phaseAt = 0;
      this.waitingForCharge = false; this.endingText = ''; this.replugBy = null;
      this.story = ep.intro;
      this.phase = Phase.INTRO;
      this.ambient(ep.color, 70);
      this.speak(`${this.recap} 오늘은 ${n + 1}화예요. ${ep.intro}`.trim());
      this.render();
    }
    /** "N화가 준비됐어요" card: start that 화. */
    startNext() { if (this.episodes.length) this.startEpisode(this.episodes[this.nextIndex].id); }
    /** Close the card with one tap; other episodes stay one more tap away (시작). */
    dismissReady() { this.readyCard = false; this.render(); }
    /** Doors closed and the family tapped "이어서 하기": back where the door stopped us, inputs re-baselined. */
    resumeAfterDoor() {
      if (this.phase !== Phase.PAUSED_DOOR || !this.doorAsk) return;
      this.phase = this.doorFrom ?? Phase.TITLE;
      this.doorFrom = null;
      this.doorAsk = false;
      if (this.phase === Phase.PUZZLE) {
        const p = this.puzzles[this.index];
        this.run = new PuzzleRun(p.spec, this.vs);
        this.ambient(p.color, 60);
        this.speak(`${p.story} ${prompt(p.spec)}.`);
      }
      this.render();
    }
    skipIntro() {
      if (this.phase !== Phase.INTRO) return;
      this.beginPuzzle();
      this.render();
    }
    requestHint() {
      if (this.phase !== Phase.PUZZLE || this.hintsUsed >= 3) return;
      this.hintsUsed++;
      this.hintsTotal++;
      this.speak(hints(this.puzzles[this.index].spec)[this.hintsUsed - 1]);
      this.render();
    }
    skipPuzzle() {
      if (this.phase !== Phase.PUZZLE) return;
      this.skips++;
      this.sfx('UNLOCK');
      this.next();
      this.render();
    }
    backToTitle() {
      this.phase = Phase.TITLE;
      this.pausedFrom = null;
      this.doorFrom = null;
      this.startVs = null;
      this.readyCard = false;
      this.episode = null;
      this.puzzles = [];
      this.run = null;
      this.log = [];
      this.emit({ type: 'AmbientRestore' });
      this.render();
    }
    beginPuzzle() {
      const p = this.puzzles[this.index];
      this.phase = Phase.PUZZLE;
      this.phaseAt = this.elapsedMs;
      this.hintsUsed = 0;
      this.story = p.story;
      this.run = new PuzzleRun(p.spec, this.vs);
      this.bonusUntil = p.timedSec != null ? this.elapsedMs + p.timedSec * 1000 : null;
      this.ambient(p.color, 60);
      const timer = p.timedSec != null ? ` 번개 금고예요! ${p.timedSec}초 안에 풀면 번개 배지를 받아요.` : '';
      const question = p.spec.type === 'Riddle' ? `${p.spec.question} ` : '';
      const wiggle = this.run.needsWiggle ? ` ${WIGGLE}` : '';
      this.speak(`${p.story} ${question}${prompt(p.spec)}.${wiggle}${timer}`);
    }
    solved() {
      const p = this.puzzles[this.index];
      this.phase = Phase.SOLVED;
      this.phaseAt = this.elapsedMs;
      this.story = p.reward;
      this.run = null;
      this.solvedKinds.add(kind(p.spec));
      const fast = this.bonusUntil != null && this.elapsedMs <= this.bonusUntil;
      this.bonusUntil = null;
      if (fast) { this.lightning++; this.solvedKinds.add('LIGHTNING'); }
      this.sfx('CORRECT');
      this.ambient(GREEN, 90);
      this.speak(fast ? `번개 배지! ${p.reward}` : p.reward);
    }
    tired() {
      this.skips++;
      this.phase = Phase.SOLVED;
      this.phaseAt = this.elapsedMs;
      this.story = '와이퍼가 쉬어야 해요. 이 금고는 열어 둘게요.';
      this.run = null;
      this.bonusUntil = null;
      this.sfx('UNLOCK');
      this.speak(this.story);
    }
    next() {
      this.index++;
      if (this.index < this.puzzles.length) return this.beginPuzzle();
      this.run = null;
      this.bonusUntil = null;
      if (this.chargeDone() || this.chargeStopped()) return this.finish();
      this.score(0);
      this.phase = Phase.ENDING;
      this.waitingForCharge = true;
      this.endingText = '모든 퍼즐을 풀었어요! 충전이 끝나면 엔딩이 열려요.';
      this.ambient(WAIT_BLUE, 40);
      this.speak(this.endingText);
    }
    chargeStopped() { return !this.vs.charging && (this.replugBy == null || (this.lastNow ?? 0) >= this.replugBy); }
    /** Real ending, bonus ending with the rest listed, or "charge stopped" ending (see EscapeGame.kt finish). */
    finish() {
      const ep = this.episode;
      if (!ep) return;
      const vs = this.vs;
      const left = this.puzzles.slice(this.index).filter((_, i) => !(i === 0 && this.phase === Phase.SOLVED));
      const target = vs.chargeTargetPercent;
      const soc = Math.trunc(vs.socPercent);
      const n = this.puzzles.length;
      const solvedLine = `퍼즐 ${n}개 중 ${n - left.length}개 완료`;
      // 원래대로 출발: name only what differs from the start. The app never moves anything back itself.
      const diff = this.restoreLeft() || [];
      this.restoreDone = diff.length === 0;
      const tidy = diff.length === 0 ? '' : ` 마지막 미션, 원래대로 출발! 시작 때와 달라진 곳을 돌려 놓아요. ${diff.join(', ')}.`;
      const km = vs.rangeMeters != null && vs.rangeMeters > 0 ? ` 이제 ${Math.round(vs.rangeMeters / 1000)}km 갈 수 있어요.` : '';
      const idx = this.episodes.indexOf(ep);
      const finished = left.length === 0; // 충전 1회 = 1화: the next 화 opens only when this one was finished
      if (finished && idx >= 0) this.nextIndex = (idx + 1) % this.episodes.length;
      const teaser = finished && ep.teaser ? ` ${ep.teaser}` : '';
      let spoken = null;
      if (!this.chargeDone()) {
        const bye = teaser || ' 다음 충전 때 또 만나요.';
        spoken = `충전이 멈췄어요. 지금 배터리는 ${soc}퍼센트예요.${km} ${solvedLine}! ${ep.outro}${bye}`;
        this.endingText = `충전이 멈췄어요. 지금 배터리 ${soc}%.${km} ${solvedLine}! ${ep.outro}${bye}`;
      } else if (left.length === 0) {
        this.endingText = `${target}% 충전 완료!${km} ${ep.outro} 엔딩 크레딧과 함께 출발하세요.${teaser}`;
      } else {
        this.endingText = `보너스 엔딩! 충전이 먼저 ${target}%에 도착했어요.${km} 남은 퍼즐 ${left.length}개: ` +
          left.map((p) => title(p.spec)).join(', ') + '. 다음 충전 때 다시 만나요.';
      }
      this.phase = Phase.ENDING;
      this.waitingForCharge = false;
      this.run = null;
      this.bonusUntil = null;
      this.score(left.length);
      this.sfx('ENDING');
      this.ambient(GOLD, 100);
      // Web voice is pre-recorded clips, so the spoken range is rounded to 10 km ("약"); the screen keeps the exact km.
      const say = (spoken ?? this.endingText).replace(/ 이제 (\d+)km 갈 수 있어요\./, (_, k) => ` 이제 약 ${Math.round(k / 10) * 10}킬로미터 갈 수 있어요.`);
      this.speak(say + tidy);
    }
    /** Parts that differ from the episode start; only parts read at the start are compared. null = no episode. */
    restoreLeft() {
      const s0 = this.startVs, vs = this.vs;
      if (!s0) return null;
      const out = [];
      const sup = (i) => this.supported.includes(i);
      const moved = (a, b) => Math.abs(a - b) > WINDOW_SLACK;
      if (sup('WINDOW') && moved(vs.windowDriverPercent, s0.windowDriverPercent)) out.push('운전석 창문');
      for (const w of CarWindow) {
        const a = s0.windows[w];
        if (a == null) continue; // unknown at the start: not compared
        if (moved(vs.windows[w] ?? a, a)) out.push(WINDOW_NAMES[w]);
      }
      if (sup('SEAT_HEAT') && vs.seatHeatDriverLevel !== s0.seatHeatDriverLevel) out.push('시트 열선');
      if (sup('STEERING_HEAT') && vs.steeringHeatLevel !== s0.steeringHeatLevel) out.push('핸들 열선');
      if (sup('READING_LIGHT') && vs.readingLightOn !== s0.readingLightOn) out.push('독서등');
      if (sup('WIPER') && vs.wiperOn !== s0.wiperOn) out.push('와이퍼');
      return out;
    }
    score(unsolved) {
      this.starsN = stars(this.hintsTotal, this.skips + unsolved, this.puzzles.length);
      this.summary = `힌트 ${this.hintsTotal}번, 건너뛰기 ${this.skips + unsolved}번, 번개 ${this.lightning}개`;
    }
    render() {
      const ep = this.episode ?? (this.phase === Phase.MATCHING ? this.recommend() : null);
      const p = this.puzzles[this.index];
      const ending = this.phase === Phase.ENDING;
      const timed = this.phase === Phase.PUZZLE && this.bonusUntil != null;
      this.ui = {
        phase: this.phase,
        episodeTitle: ep ? ep.title : '',
        chapterLabel: p ? `${this.index + 1} / ${this.puzzles.length}` : '',
        puzzleIndex: this.index,
        puzzleCount: this.puzzles.length,
        puzzleTitle: p ? title(p.spec) : '',
        story: this.episode == null ? '' : (this.phase === Phase.PUZZLE && this.run && this.run.needsWiggle) ? WIGGLE : this.story,
        prompt: p ? prompt(p.spec) : '',
        visual: this.run ? this.run.visual(this.vs, this.lastNow ?? 0) : null,
        requiredInput: this.run ? this.run.input : p ? input(p.spec) : null,
        hintText: p ? (hints(p.spec)[this.hintsUsed - 1] ?? null) : null,
        hintsUsed: this.hintsUsed,
        socPercent: this.vs.socPercent,
        targetPercent: this.vs.chargeTargetPercent,
        chargeEtaMinutes: this.eta(),
        episodeMinutes: ep ? ep.minutes : 0,
        elapsedSec: Math.floor(this.elapsedMs / 1000),
        endingText: this.endingText,
        waitingForCharge: ending && this.waitingForCharge,
        episodeId: ep ? ep.id : '',
        daily: this.episode != null && this.daily,
        missionKind: p ? kind(p.spec) : null,
        bonusSecTotal: timed ? p.timedSec : null,
        bonusSecLeft: timed ? Math.max(0, Math.trunc((this.bonusUntil - this.elapsedMs + 999) / 1000)) : null,
        lightning: this.lightning,
        solvedKinds: KINDS.filter((k) => this.solvedKinds.has(k)),
        stars: ending ? this.starsN : 0,
        summary: ending ? this.summary : '',
        episodeNumber: ep ? this.episodes.indexOf(ep) + 1 : 0,
        recap: this.phase === Phase.INTRO ? this.recap : '',
        readyEpisode: this.phase === Phase.TITLE && this.readyCard && this.episodes.length ? this.nextIndex + 1 : 0,
        doorAsk: this.phase === Phase.PAUSED_DOOR && this.doorAsk,
        restoreLeft: ending && !this.waitingForCharge ? this.restoreLeft() : null,
      };
      for (const f of this.listeners) f({ type: 'Ui', ui: this.ui });
    }
  }
  EscapeGame.stars = stars;
  EscapeGame.WIGGLE = WIGGLE;
  EscapeGame.INTRO_MS = INTRO_MS;
  EscapeGame.SOLVED_MS = SOLVED_MS;
  EscapeGame.REPLUG_MS = REPLUG_MS;
  EscapeGame.RECAP_MS = RECAP_MS;
  EscapeGame.RESUME_ASK = RESUME_ASK;
  EscapeGame.READY_TO_GO = READY_TO_GO;

  /** LocalDate.now().toEpochDay() in the viewer's time zone. */
  function epochDay(d = new Date()) { return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000); }

  // ---------------------------------------------------------------- Customization.kt (마이 차고)
  // earned = { stars: best stars summed per episode, badges: [MissionKind], clears: episodes finished }
  const DriveMode = ['NORMAL', 'ECO', 'SPORT'];
  const DRIVE_MODE_NAME = { NORMAL: '노멀', ECO: '에코', SPORT: '스포츠' };
  /** Need(stars, badgeCount, clears, badge): all zero = free from the start. */
  const Need = (o = {}) => ({ stars: 0, badgeCount: 0, clears: 0, badge: null, ...o });
  const needMet = (n, e) => e.stars >= n.stars && e.badges.length >= n.badgeCount && e.clears >= n.clears && (n.badge == null || e.badges.includes(n.badge));
  /** One line under a locked item, '' when free. */
  function needHint(n) {
    if (n.badge != null) return `'${MissionKind[n.badge]}' 배지를 받으면 열려요`;
    if (n.stars > 0) return `별을 ${n.stars}개 모으면 열려요`;
    if (n.badgeCount > 0) return `배지를 ${n.badgeCount}개 모으면 열려요`;
    if (n.clears > 0) return `이야기를 ${n.clears}개 끝내면 열려요`;
    return '';
  }
  const item = (group, id, label, need, extra) => ({ group, id, label, need: Need(need), ...extra });
  /** Body colors, rgb = 0xRRGGBB. The first four are free. */
  const Paint = [
    item('paint', 'SNOW', '눈꽃 흰색', {}, { rgb: 0xF2F1EC }),
    item('paint', 'MIDNIGHT', '밤 검정', {}, { rgb: 0x1C2029 }),
    item('paint', 'SILVER', '달빛 은색', {}, { rgb: 0xA8AEB6 }),
    item('paint', 'OCEAN', '바다 파랑', {}, { rgb: 0x2C5D8C }),
    item('paint', 'FOREST', '숲 초록', { stars: 3 }, { rgb: 0x3F6E4A }),
    item('paint', 'SUNSET', '노을 주황', { badgeCount: 3 }, { rgb: 0xE07B39 }),
    item('paint', 'CHERRY', '체리 빨강', { stars: 6 }, { rgb: 0xB7313D }),
    item('paint', 'GOLD', '반짝 금색', { badgeCount: 8 }, { rgb: 0xC9A24B }),
  ];
  const Wheel = [
    item('wheel', 'BASIC', '기본 휠'),
    item('wheel', 'STAR', '별 휠', { stars: 4 }),
    item('wheel', 'PINWHEEL', '바람개비 휠', { clears: 2 }),
    item('wheel', 'GOLD', '금빛 휠', { stars: 10 }),
  ];
  /** One sticker per mission badge, opened by that badge. */
  const STICKERS = KINDS.map((k) => item('sticker', k, MissionKind[k], { badge: k }));
  /** Sticker spots on the body, one sticker each. */
  const StickerSlot = ['FRONT_DOOR', 'REAR_DOOR', 'TRUNK'];
  /** App-wide skins (카카오톡 테마처럼). mood = matching ambient light. NIGHT_SKY is today's palette. */
  const Theme = [
    item('theme', 'NIGHT_SKY', '밤하늘', {}, { mood: 0xE8B962 }),
    item('theme', 'FOREST_CAMP', '숲속 캠핑', {}, { mood: 0x5BD27A }),
    item('theme', 'RACING', '불꽃 서킷', {}, { mood: 0xFF4A3D }),
    item('theme', 'UNDERSEA', '바닷속', { badgeCount: 2 }, { mood: 0x2FB8E8 }),
    item('theme', 'SPACE_STATION', '우주 정거장', { clears: 3 }, { mood: 0x8C7CFF }),
    item('theme', 'STRAWBERRY_MILK', '딸기 우유', { stars: 8 }, { mood: 0xFF8FB8 }),
  ];
  /** Out of the box: 에코 = 숲, 스포츠 = 빨강, 노멀 = 밤하늘. All free, so the default is always usable. */
  const DEFAULT_MODE_THEMES = { NORMAL: 'NIGHT_SKY', ECO: 'FOREST_CAMP', SPORT: 'RACING' };
  const CATALOG = [...Paint, ...Wheel, ...STICKERS, ...Theme];
  const isOpen = (it, e) => needMet(it.need, e);
  const byId = (list, id) => list.find((x) => x.id === id);
  /** Items [after] opens that [before] did not, catalog order. For the ending screen. */
  const newlyOpened = (before, after) => CATALOG.filter((it) => !isOpen(it, before) && isOpen(it, after));

  /**
   * The family's saved car and skin (Look). theme = the fixed pick (모드 따라가기 off, or mode unknown).
   * modeThemes / modeMoods hold only changes from the default. stickers: { StickerSlot: MissionKind }.
   */
  const look = (o) => ({ paint: 'SNOW', wheel: 'BASIC', stickers: {}, theme: 'NIGHT_SKY', modeThemes: {}, modeMoods: {}, followMode: true, ...o });
  const themeOf = (l, mode) => l.modeThemes[mode] ?? DEFAULT_MODE_THEMES[mode];
  const moodOf = (l, mode) => l.modeMoods[mode] ?? byId(Theme, themeOf(l, mode)).mood;
  /** Skin to show now. mode null = unknown: the fixed pick. */
  const shownTheme = (l, mode) => l.followMode && mode != null ? themeOf(l, mode) : l.theme;
  const shownMood = (l, mode) => l.followMode && mode != null ? moodOf(l, mode) : byId(Theme, l.theme).mood;
  // Same string as Look.encode() so a saved look reads the same on both sides.
  const pairs = (m, ks, v) => ks.filter((k) => m[k] != null).map((k) => `${k}:${v(m[k])}`).join(',');
  const encodeLook = (l) => [l.paint, l.wheel, pairs(l.stickers, StickerSlot, String), l.theme, pairs(l.modeThemes, DriveMode, String),
    pairs(l.modeMoods, DriveMode, (c) => (c & 0xFFFFFF).toString(16).toUpperCase().padStart(6, '0')), l.followMode].join('|');
  /** Never fails: each broken or unknown part falls back to its default on its own. */
  function decodeLook(s) {
    const p = (s || '').split('|'), d = look();
    const one = (list, v, dflt) => byId(list, v) ? v : dflt;
    const parse = (str, keys, v) => {
      const out = {};
      for (const e of (str || '').split(',')) {
        const kv = e.split(':');
        if (kv.length !== 2 || !keys.includes(kv[0])) continue;
        const x = v(kv[1]);
        if (x != null) out[kv[0]] = x;
      }
      return out;
    };
    return look({
      paint: one(Paint, p[0], d.paint),
      wheel: one(Wheel, p[1], d.wheel),
      stickers: parse(p[2], StickerSlot, (v) => KINDS.includes(v) ? v : null),
      theme: one(Theme, p[3], d.theme),
      modeThemes: parse(p[4], DriveMode, (v) => byId(Theme, v) ? v : null),
      modeMoods: parse(p[5], DriveMode, (v) => /^[0-9A-Fa-f]{6}$/.test(v) ? parseInt(v, 16) : null),
      followMode: p[6] === 'true' ? true : p[6] === 'false' ? false : d.followMode,
    });
  }

  /**
   * Skin colors per theme, copied from ui/Kit.kt paletteOf (ui.js applies them; selftest checks contrast).
   * NIGHT_SKY = style.css :root / Kit.kt object C exactly. top = the screen gradient's top color.
   */
  const SKINS = {
    NIGHT_SKY: { bg: '#0A111E', deep: '#05080F', surface: '#131C2C', surface2: '#1C2739', line: 'rgba(233,220,196,0.25)', text: '#F4F1EA', sub: '#B7C0CE', faint: '#8590A3', gold: '#E8B962', amber: '#F2994A', onGold: '#1A1305', top: '#0E1728' },
    FOREST_CAMP: { bg: '#0B1A12', deep: '#050D08', surface: '#12261A', surface2: '#1A3324', line: 'rgba(216,235,201,0.25)', text: '#F1F5EC', sub: '#B6CBB9', faint: '#86A08C', gold: '#7FD98F', amber: '#F2B84A', onGold: '#0A1A0E', top: '#12281B' },
    RACING: { bg: '#150C0C', deep: '#0A0505', surface: '#231515', surface2: '#301C1C', line: 'rgba(242,208,200,0.25)', text: '#F7F0EE', sub: '#D0BAB6', faint: '#A48D89', gold: '#FF6A4D', amber: '#FFB13D', onGold: '#1A0503', top: '#241010' },
    UNDERSEA: { bg: '#061620', deep: '#020A10', surface: '#0C2230', surface2: '#13303F', line: 'rgba(196,230,240,0.25)', text: '#EEF8FB', sub: '#AFCAD4', faint: '#7F9DA9', gold: '#4FD2F0', amber: '#7BE0C4', onGold: '#03141C', top: '#0B2A3C' },
    SPACE_STATION: { bg: '#0D0B1E', deep: '#06050F', surface: '#17142E', surface2: '#221E3E', line: 'rgba(217,212,242,0.25)', text: '#F3F1FA', sub: '#BDB8D6', faint: '#8E89AB', gold: '#A99BFF', amber: '#6FE3E8', onGold: '#0E0A24', top: '#15122E' },
    STRAWBERRY_MILK: { bg: '#FFF4F7', deep: '#FCE6ED', surface: '#FFFFFF', surface2: '#FBE9EF', line: 'rgba(138,58,85,0.3)', text: '#3A1A26', sub: '#6B4150', faint: '#7E5464', gold: '#C2185B', amber: '#9C3D78', onGold: '#FFFFFF', top: '#FFFAFC', light: true },
  };
  /** WCAG contrast ratio of two #RRGGBB colors. */
  function contrast(a, b) {
    const lum = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((c) => c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
      .reduce((s, c, i) => s + c * [0.2126, 0.7152, 0.0722][i], 0);
    const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
    return (x + 0.05) / (y + 0.05);
  }

  root.CarEscape = {
    KRandom, shuffled, Gear, VehicleInput, CarSeat, CarWindow, MissionKind, KINDS, Phase, vehicleState, windowPercent,
    Spec, input, inputs, title, prompt, chip, hints, kind, number, substitute, roll, reroll, riddle, fit, demo,
    PuzzleRun, Episodes, ChargeClock, simCharge, SIM_CHARGE_PER_MIN, SIM_METERS_PER_PERCENT, TIMELAPSE, EscapeGame, seatName, epochDay,
    DriveMode, DRIVE_MODE_NAME, Need, needHint, Paint, Wheel, STICKERS, StickerSlot, Theme, DEFAULT_MODE_THEMES, CATALOG, isOpen, byId, newlyOpened,
    look, themeOf, moodOf, shownTheme, shownMood, encodeLook, decodeLook, SKINS, contrast,
  };
})(typeof globalThis !== 'undefined' ? globalThis : this);
