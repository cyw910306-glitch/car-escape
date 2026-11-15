// Parity checks against the Kotlin tests (game/src/test). Run: node web/selftest.mjs
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const _readCss = () => readFileSync(new URL('./style.css', import.meta.url), 'utf8');
createRequire(import.meta.url)('./game.js');
const CE = globalThis.CarEscape;
const { KRandom, Spec, Episodes, EscapeGame, vehicleState, roll, reroll, riddle, substitute, hints, fit, number, prompt, title, inputs, ChargeClock, demo, simCharge, TIMELAPSE } = CE;

let n = 0;
const t = (name, fn) => { fn(); n++; console.log('ok', name); };
const strip = (s) => JSON.parse(JSON.stringify(s));
const said = (r) => r.game.log.filter((e) => e.type === 'Speak').map((e) => e.text);

/** Rig from EscapeGameTest.kt: fake time, 100 ms ticks. */
function rig(game = new EscapeGame({ random: KRandom.ofInt(7) }), s = vehicleState({ socPercent: 40 })) {
  const r = { game, s, now: 1_000_000 };
  r.tick = (ms = 100) => { let left = ms; do { const d = Math.min(100, left); r.now += d; left -= d; game.onVehicle(r.s, r.now); } while (left > 0); };
  r.set = (ms, f) => { r.s = f(r.s); r.tick(ms); };
  r.play = (steps) => steps.forEach((x) => r.set(x.waitMs, x.change));
  r.phase = () => game.ui.phase;
  r.sfx = (k) => game.log.filter((e) => e.type === 'Sfx' && e.kind === k).length;
  r.toPuzzle = (id) => { r.tick(); game.startEpisode(id); r.tick(EscapeGame.INTRO_MS + 100); };
  return r;
}

t('DemoSeedTest: seed 35 is the demo play', () => {
  const g = new EscapeGame({ supported: ['WINDOW', 'READING_LIGHT', 'TURN_SIGNAL', 'STEERING_HEAT', 'SEAT_HEAT', 'WIPER'] });
  g.startDaily('map-15', 35);
  const ps = g.currentPuzzles();
  // v3: 1화 has no turn signals or wipers
  assert.deepEqual(strip(ps.map((p) => p.spec)), strip([Spec.Window(50), Spec.SteeringHeat(1), Spec.Morse('-.-.'), Spec.SeatHeat(1)]));
  assert.deepEqual(ps.map((p) => p.timedSec), [null, null, null, null]);
});

t('beltsFollowFrontOccupancy (Random(1) rolls)', () => {
  const occ = ['ROW_3_RIGHT', 'ROW_1_RIGHT', 'ROW_1_LEFT', 'ROW_2_CENTER'];
  const relay = reroll(Spec.Belts({ ordered: true }), KRandom.ofInt(1), occ, 4);
  assert.deepEqual(relay, Spec.Belts({ seats: ['ROW_1_LEFT', 'ROW_1_RIGHT'], ordered: true }));
  assert.equal(prompt(relay), '운전석, 조수석 순서로 한 명씩 벨트를 다시 찰칵 채워 보세요');
  for (let i = 0; i < 20; i++) {
    const free = reroll(Spec.Belts(), KRandom.ofInt(i), occ, 4);
    assert.ok(free.seats.length && free.seats.every((s) => s.startsWith('ROW_1')));
  }
  assert.deepEqual(reroll(Spec.Belts(), KRandom.ofInt(1), [], 5), Spec.Belts({ count: 2 }));
  assert.deepEqual(reroll(Spec.Belts({ ordered: true }), KRandom.ofInt(1), ['ROW_2_LEFT'], 1), Spec.Belts({ count: 1 }));
});

t('riddle(Window(30), Random(1)) answer cell', () => {
  assert.equal(hints(riddle(Spec.Window(30), KRandom.ofInt(1)))[2], '정답: 운전석 창문 10칸 중 3칸.');
});

t('riddleAnswerEqualsSpecNumber seeds 0..30', () => {
  const inners = [Spec.Wiper(1), Spec.Wiper(3), Spec.SeatHeat(2), Spec.SteeringHeat(1), Spec.Window(30), Spec.Window(70)];
  for (let seed = 0; seed <= 30; seed++) for (const inner of inners) {
    const rd = riddle(inner, KRandom.ofInt(seed));
    assert.equal(rd.answer, number(inner));
    const [a, op, b] = rd.math.split(' ');
    assert.equal(rd.answer, op === '더하기' ? +a + +b : +a - +b);
    assert.ok(+a > 0 && +b > 0 && prompt(rd).startsWith('답을 찾으면') && rd.question.endsWith('?'));
    assert.ok(hints(rd)[1].endsWith(`답은 ${rd.answer}!`));
  }
});

t('ScoreTest.starsMath', () => {
  assert.equal(EscapeGame.stars(0, 0, 4), 3);
  assert.equal(EscapeGame.stars(1, 0, 4), 2);
  assert.equal(EscapeGame.stars(4, 0, 4), 2);
  assert.equal(EscapeGame.stars(5, 0, 4), 1);
  assert.equal(EscapeGame.stars(0, 1, 4), 1);
});

t('matchingPicksByEta', () => {
  const g = new EscapeGame();
  assert.equal(g.recommend().id, 'map-15');
  let tm = 0, soc = 40;
  for (let i = 0; i < 60; i++) { g.onVehicle(vehicleState({ socPercent: soc }), tm); tm += 10_000; soc += 0.1; }
  assert.equal(g.recommend().id, 'letter-30');
  g.startMatching();
  assert.equal(g.ui.phase, 'MATCHING');
  assert.equal(g.ui.episodeMinutes, 30);
  const fast = new EscapeGame();
  soc = 70; tm = 0;
  for (let i = 0; i < 20; i++) { fast.onVehicle(vehicleState({ socPercent: soc }), tm); tm += 10_000; soc += 0.3; }
  assert.equal(fast.recommend().id, 'map-15');
});

t('unsupportedInputsAreSwapped', () => {
  const out = substitute(Episodes.letter30.puzzles, ['READING_LIGHT', 'TURN_SIGNAL', 'WINDOW']);
  assert.deepEqual(out.map((p) => p.spec.type === 'Window' ? 'WINDOW' : p.spec.type === 'Morse' ? 'READING_LIGHT' : 'TURN_SIGNAL'),
    ['WINDOW', 'WINDOW', 'READING_LIGHT', 'TURN_SIGNAL', 'READING_LIGHT', 'TURN_SIGNAL']);
  assert.deepEqual(substitute(Episodes.map15.puzzles, []), Episodes.map15.puzzles);
  const rear = substitute(Episodes.letter30.puzzles, ['REAR_WINDOW', 'SEATBELT']);
  assert.ok(rear.every((p) => p.spec.type === 'Belts'));
});

t('RandomizeTest: same seed same puzzles, variety, first/last fixed', () => {
  for (const ep of Episodes.all) {
    assert.deepEqual(strip(roll(ep, KRandom.ofInt(42))), strip(roll(ep, KRandom.ofInt(42))));
    const distinct = new Set(Array.from({ length: 20 }, (_, i) => JSON.stringify(roll(ep, KRandom.ofInt(i + 1)).map((p) => [p.spec, p.story]))));
    assert.ok(distinct.size >= 15, `${ep.id} ${distinct.size}`);
    const r = roll(ep, KRandom.ofInt(5));
    assert.equal(r[0].story, ep.puzzles[0].story);
    assert.equal(r[r.length - 1].story, ep.puzzles[ep.puzzles.length - 1].story);
  }
});

t('rerollStaysInSafeRanges + rear window <= 50 + timed fits kind (seeds 0..300)', () => {
  const check = (s) => {
    if (s.type === 'Window') { assert.ok([30, 50, 70].includes(s.target)); if (s.area && s.area.startsWith('ROW_2')) assert.ok(s.target <= 50); }
    if (s.type === 'Wiper') assert.ok(s.times >= 1 && s.times <= 3);
    if (s.type === 'Morse') assert.ok(s.pattern.length >= 2 && s.pattern.length <= 4);
    if (s.type === 'Memory') assert.ok(s.target.length >= 3 && s.target.length <= 5);
    if (s.type === 'Chain') { assert.ok(!s.steps.some((x) => x.type === 'Wiper')); s.steps.forEach(check); }
    if (s.type === 'Riddle') check(s.inner);
  };
  for (let seed = 0; seed <= 300; seed++) for (const ep of Episodes.all) for (const p of roll(ep, KRandom.ofInt(seed))) {
    check(p.spec);
    const want = { Chain: 90, Riddle: 75, Memory: 60, Belts: 60, Twin: 60 }[p.spec.type] ?? 45;
    if (p.timedSec != null) assert.equal(p.timedSec, want);
  }
});

t('dailyRollIgnoresOccupancy (days 0..100)', () => {
  for (let day = 0; day <= 100; day++) for (const ep of Episodes.all) {
    const a = roll(ep, KRandom.ofLong(day), [], 1), b = roll(ep, KRandom.ofLong(day), ['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT'], 3);
    const m = (x) => strip(x.map((p) => p.spec.type === 'Belts' ? null : p));
    assert.deepEqual(m(a), m(b));
  }
});

t('dailySeedGivesSamePuzzles', () => {
  const daily = (day, other) => { const g = new EscapeGame({ random: KRandom.ofInt(other) }); g.startDaily('letter-30', day); return [strip(g.currentPuzzles()), g.ui.daily]; };
  assert.deepEqual(daily(20000, 1), daily(20000, 2));
  assert.ok(daily(20000, 1)[1]);
  assert.notDeepEqual(daily(20000, 1)[0], daily(20001, 1)[0]);
});

t('heatLevelsFitTheCar', () => {
  const one = vehicleState({ steeringHeatMax: 1, seatHeatMax: 2 });
  assert.deepEqual(fit(Spec.SteeringHeat(2), one), Spec.SteeringHeat(1, { max: 1 }));
  assert.deepEqual(fit(Spec.SeatHeat(3), one), Spec.SeatHeat(2, { max: 2 }));
  const rd = fit(Spec.Riddle(Spec.SeatHeat(3), 'q', '1 더하기 2', 3), one);
  assert.equal(rd.answer, 2);
  assert.deepEqual(rd.inner, Spec.SeatHeat(2, { max: 2 }));
});

t('demoSolvesEveryPuzzleOfEveryEpisode (seeds 1..12)', () => {
  for (let seed = 1; seed <= 12; seed++) for (const ep of Episodes.all) {
    const occ = seed % 2 === 0 ? ['ROW_1_LEFT', 'ROW_2_RIGHT', 'ROW_3_LEFT'] : [];
    const r = rig(new EscapeGame({ random: KRandom.ofInt(seed) }), vehicleState({ socPercent: 40, occupied: occ }));
    r.toPuzzle(ep.id);
    ep.puzzles.forEach((_, i) => {
      assert.equal(r.phase(), 'PUZZLE', `${ep.id} #${i}`);
      assert.equal(r.game.ui.puzzleIndex, i);
      r.play(r.game.demoSolution(i));
      assert.equal(r.phase(), 'SOLVED', `${ep.id} #${i} seed ${seed}`);
      r.tick(EscapeGame.SOLVED_MS + 100);
    });
    assert.equal(r.phase(), 'ENDING');
    assert.ok(r.game.ui.endingText.startsWith('모든 퍼즐을 풀었어요') && r.game.ui.waitingForCharge);
    r.set(100, (s) => ({ ...s, socPercent: 80 }));
    assert.ok(r.game.ui.endingText.startsWith('80% 충전 완료!'));
    assert.equal(r.sfx('ENDING'), 1);
  }
});

t('bonus ending, stopped ending + spoken 원래대로 출발', () => {
  const r = rig();
  r.toPuzzle('map-15');
  r.play(r.game.demoSolution(0));
  r.set(100, (s) => ({ ...s, socPercent: 80 }));
  const left = r.game.currentPuzzles().slice(1).map((p) => title(p.spec)).join(', ');
  assert.ok(r.game.ui.endingText.startsWith('보너스 엔딩!') && r.game.ui.endingText.includes(`남은 퍼즐 3개: ${left}`));
  assert.equal(r.game.ui.stars, 1);

  const w = rig();
  w.toPuzzle('map-15');
  Episodes.map15.puzzles.forEach((_, i) => { w.play(w.game.demoSolution(i)); w.tick(EscapeGame.SOLVED_MS + 100); });
  w.set(100, (s) => ({ ...s, charging: false, socPercent: 63.4 }));
  assert.ok(w.game.ui.endingText.startsWith('충전이 멈췄어요. 지금 배터리 63%.') && w.game.ui.endingText.includes('퍼즐 4개 중 4개 완료!'));

  // speech = the screen text in spoken form, then the 원래대로 출발 line (heaters were turned on)
  assert.ok(said(w).at(-1).startsWith(w.game.ui.endingText.replace('배터리 63%.', '배터리는 63퍼센트예요.') + ' 마지막 미션'));
  assert.ok(w.game.ui.endingText.endsWith(Episodes.map15.teaser));
});

const p = (spec) => ({ story: '이야기', reward: '보상', spec, color: 0, timedSec: null });
/** single() from EscapeGameTest.kt: one fixed puzzle plus an unsolvable wiper. */
function single(spec, start = vehicleState({ socPercent: 40 })) {
  const ep = { id: 't', title: '테스트', minutes: 15, intro: '인트로', outro: '끝', color: 0, twists: 0, fixed: true, puzzles: [p(spec), p(Spec.Wiper(9))] };
  const r = rig(new EscapeGame({ episodes: [ep] }), start);
  r.toPuzzle('t');
  return r;
}

t('V2Test.endingListsWhatDiffersFromTheStartUntilPutBack', () => {
  const r = single(Spec.Window(70), vehicleState({ socPercent: 40, windowDriverPercent: 20, windows: { ROW_2_RIGHT: 0 } }));
  r.play(demo(Spec.Window(70)));
  r.set(100, (s) => ({ ...s, socPercent: 80, seatHeatDriverLevel: 2, readingLightOn: true, windows: { ...s.windows, ROW_2_RIGHT: 40, ROW_1_RIGHT: 50 } }));
  assert.equal(r.phase(), 'ENDING');
  assert.deepEqual(r.game.ui.restoreLeft, ['운전석 창문', '2열 오른쪽 창문', '시트 열선', '독서등']);
  assert.ok(said(r).at(-1).endsWith('마지막 미션, 원래대로 출발! 시작 때와 달라진 곳을 돌려 놓아요. 운전석 창문, 2열 오른쪽 창문, 시트 열선, 독서등.'));
  assert.ok(!r.game.ui.endingText.includes('마지막 미션'));
  r.set(100, (s) => ({ ...s, windowDriverPercent: 20, seatHeatDriverLevel: 0, readingLightOn: false }));
  assert.deepEqual(r.game.ui.restoreLeft, ['2열 오른쪽 창문']);
  assert.ok(!said(r).includes(EscapeGame.READY_TO_GO));
  r.set(100, (s) => ({ ...s, windows: { ...s.windows, ROW_2_RIGHT: 0 } }));
  r.tick(1000);
  assert.deepEqual(r.game.ui.restoreLeft, []);
  assert.equal(said(r).filter((x) => x === EscapeGame.READY_TO_GO).length, 1);
});

t('V2Test.nothingChangedMeansReadyWithoutAnExtraLine', () => {
  const r = single(Spec.Window(50));
  r.play(demo(Spec.Window(50)));
  r.set(100, (s) => ({ ...s, windowDriverPercent: 0, socPercent: 80 }));
  assert.deepEqual(r.game.ui.restoreLeft, []);
  r.tick(1000);
  assert.ok(!said(r).includes(EscapeGame.READY_TO_GO));
});

// ---------------------------------------------------------------- V3Test.kt
t('V3Test.portOpeningOnTitleOffersTheNextEpisodeOnce', () => {
  const r = rig();
  r.set(100, (s) => ({ ...s, chargePortOpen: true }));
  assert.equal(r.game.ui.readyEpisode, 0);
  r.set(100, (s) => ({ ...s, chargePortOpen: false }));
  r.set(100, (s) => ({ ...s, chargePortOpen: null }));
  r.set(100, (s) => ({ ...s, chargePortOpen: true }));
  assert.equal(r.game.ui.readyEpisode, 1);
  r.game.dismissReady();
  assert.equal(r.game.ui.readyEpisode, 0);
  r.tick();
  assert.equal(r.game.ui.readyEpisode, 0);
  r.game.nextEpisodeIndex = 2;
  r.set(100, (s) => ({ ...s, chargePortOpen: false }));
  r.set(100, (s) => ({ ...s, chargePortOpen: true }));
  assert.equal(r.game.ui.readyEpisode, 3);
  r.game.startNext();
  assert.equal(r.phase(), 'INTRO');
  assert.equal(r.game.ui.episodeId, 'letter-30');
  assert.equal(r.game.ui.episodeNumber, 3);
});

t('V3Test.portOutsideTitleShowsNoCard', () => {
  const r = rig();
  r.set(100, (s) => ({ ...s, chargePortOpen: false }));
  r.game.startMatching();
  r.set(100, (s) => ({ ...s, chargePortOpen: true }));
  r.game.backToTitle();
  r.tick();
  assert.equal(r.game.ui.readyEpisode, 0);
});

t('V3Test.finishingAnEpisodeOpensTheNextWithRecapAndTeaser', () => {
  const r = rig();
  r.toPuzzle('map-15');
  assert.equal(r.game.ui.recap, '');
  assert.ok(said(r).some((x) => x.startsWith('오늘은 1화예요. ')));
  Episodes.map15.puzzles.forEach((_, i) => { r.play(r.game.demoSolution(i)); r.tick(EscapeGame.SOLVED_MS + 100); });
  r.set(100, (s) => ({ ...s, socPercent: 80 }));
  assert.ok(r.game.ui.endingText.endsWith('다음 충전 때, 우리 차는 우주선이 돼요.'), r.game.ui.endingText);
  assert.equal(r.game.nextEpisodeIndex, 1);
  r.game.backToTitle();
  r.set(100, (s) => ({ ...s, socPercent: 40 }));
  r.game.startEpisode('family-20');
  assert.equal(r.game.ui.recap, '지난 충전 때 우리는 충전소 지도를 완성했어요.');
  assert.equal(r.game.ui.episodeNumber, 2);
  assert.ok(said(r).at(-1).startsWith('지난 충전 때 우리는 충전소 지도를 완성했어요. 오늘은 2화예요.'));
  r.tick(EscapeGame.INTRO_MS + 100);
  assert.equal(r.phase(), 'INTRO');
  r.tick(EscapeGame.RECAP_MS);
  assert.equal(r.phase(), 'PUZZLE');
  r.game.backToTitle();
  r.game.startEpisode('letter-30');
  assert.equal(r.game.ui.recap, '');
});

t('V3Test.bonusEndingKeepsTheSameEpisodeNext', () => {
  const r = rig();
  r.toPuzzle('map-15');
  r.set(100, (s) => ({ ...s, socPercent: 80 }));
  assert.ok(r.game.ui.endingText.startsWith('보너스 엔딩!'));
  assert.ok(!r.game.ui.endingText.includes(Episodes.map15.teaser));
  assert.equal(r.game.nextEpisodeIndex, 0);
});

t('V3Test.seasonOneWrapsAfterTheFourthEpisode', () => {
  const g = new EscapeGame();
  g.nextEpisodeIndex = 4; assert.equal(g.nextEpisodeIndex, 0);
  g.nextEpisodeIndex = -1; assert.equal(g.nextEpisodeIndex, 3);
});

t('V3Test.doorPausesVoiceAndTimersAndAsksOnceWhenClosed', () => {
  const r = rig();
  r.toPuzzle('map-15');
  const idx = r.game.ui.puzzleIndex, t0 = r.game.ui.elapsedSec;
  r.set(100, (s) => ({ ...s, doorOpen: true }));
  assert.equal(r.phase(), 'PAUSED_DOOR');
  assert.equal(r.game.ui.doorAsk, false);
  assert.ok(r.game.log.some((e) => e.type === 'Silence'));
  r.set(60000, (s) => ({ ...s, windowDriverPercent: 50 }));
  assert.equal(r.game.ui.elapsedSec, t0);
  r.game.resumeAfterDoor();
  assert.equal(r.phase(), 'PAUSED_DOOR');
  r.set(100, (s) => ({ ...s, doorOpen: false }));
  assert.ok(r.game.ui.doorAsk);
  r.tick(5000);
  assert.equal(said(r).filter((x) => x === EscapeGame.RESUME_ASK).length, 1);
  assert.equal(r.phase(), 'PAUSED_DOOR');
  r.game.resumeAfterDoor();
  assert.equal(r.phase(), 'PUZZLE');
  assert.equal(r.game.ui.puzzleIndex, idx);
  r.tick(3000);
  assert.equal(r.phase(), 'PUZZLE');
});

t('V3Test.drivingWinsOverDoorPauseAndBothResume', () => {
  const r = rig();
  r.toPuzzle('map-15');
  r.set(100, (s) => ({ ...s, doorOpen: true }));
  r.set(100, (s) => ({ ...s, doorOpen: false, gear: 'DRIVE' }));
  assert.equal(r.phase(), 'PAUSED_DRIVING');
  r.set(100, (s) => ({ ...s, gear: 'PARK' }));
  r.tick();
  assert.equal(r.phase(), 'PAUSED_DOOR');
  assert.ok(r.game.ui.doorAsk);
  r.game.resumeAfterDoor();
  assert.equal(r.phase(), 'PUZZLE');
});

t('V3Test.doorOnTitleOrEndingDoesNothing', () => {
  const r = rig();
  r.set(100, (s) => ({ ...s, doorOpen: true }));
  assert.equal(r.phase(), 'TITLE');
});

t('V3Test.endingSaysRangeOnlyWhenKnown', () => {
  const r = rig(undefined, vehicleState({ socPercent: 40, rangeMeters: 312400 }));
  r.toPuzzle('map-15');
  r.set(100, (s) => ({ ...s, socPercent: 80 }));
  assert.ok(r.game.ui.endingText.startsWith('보너스 엔딩! 충전이 먼저 80%에 도착했어요. 이제 312km 갈 수 있어요. 남은'), r.game.ui.endingText);
  assert.ok(said(r).at(-1).includes('이제 약 310킬로미터 갈 수 있어요.'));
  const none = rig();
  none.toPuzzle('map-15');
  none.set(100, (s) => ({ ...s, socPercent: 80 }));
  assert.ok(!none.game.ui.endingText.includes('갈 수 있어요'));
});

const hasRear = (g) => g.currentPuzzles().some((q) => inputs(q.spec).includes('REAR_WINDOW'));
t('V3Test.childLockMovesSecondRowWindowsToTheFront', () => {
  for (let seed = 0; seed <= 30; seed++) {
    const g = new EscapeGame({ random: KRandom.ofInt(seed) });
    g.onVehicle(vehicleState({ rearWindowLocked: true }), 0);
    g.startEpisode('moon-25');
    assert.ok(!hasRear(g), `${seed}`);
    g.startEpisode('family-20');
    assert.ok(!hasRear(g));
  }
  const unlocked = new EscapeGame({ random: KRandom.ofInt(1) });
  unlocked.startEpisode('moon-25');
  assert.ok(hasRear(unlocked));
});

t('V3Test.lockedRearWindow swaps to passenger, else driver', () => {
  const all = CE.VehicleInput.filter((i) => i !== 'REAR_WINDOW');
  assert.deepEqual(substitute([p(Spec.Window(40, { area: 'ROW_2_RIGHT' }))], all)[0].spec, Spec.Window(40, { area: 'ROW_1_RIGHT' }));
  assert.deepEqual(substitute([p(Spec.Window(40, { area: 'ROW_2_RIGHT' }))], ['WINDOW', 'WIPER'])[0].spec, Spec.Window(40));
});

t('V3Test.firstEpisodeNeverUsesSignalsOrWipers', () => {
  for (let seed = 0; seed <= 200; seed++) {
    const g = new EscapeGame({ random: KRandom.ofInt(seed) });
    g.startEpisode('map-15');
    const ins = g.currentPuzzles().flatMap((q) => inputs(q.spec));
    assert.ok(!ins.includes('TURN_SIGNAL') && !ins.includes('WIPER'), String(ins));
  }
  const g = new EscapeGame({ supported: ['WINDOW', 'READING_LIGHT', 'TURN_SIGNAL', 'WIPER'], random: KRandom.ofInt(3) });
  g.startEpisode('map-15');
  assert.ok(g.currentPuzzles().flatMap((q) => inputs(q.spec)).every((i) => i === 'WINDOW' || i === 'READING_LIGHT'));
});

t('V3Test.seasonOneHasFourEpisodesInOrder', () => {
  assert.deepEqual(Episodes.all.map((e) => e.id), ['map-15', 'family-20', 'letter-30', 'moon-25']);
  assert.equal(Episodes.all[0].recap, '');
  Episodes.all.slice(1).forEach((e) => assert.ok(e.recap.startsWith('지난 충전 때'), e.id));
  Episodes.all.forEach((e) => assert.ok(e.teaser.trim(), e.id));
  assert.equal(Episodes.all.at(-1).teaser, '시즌 2에서 또 만나요.');
});

const FORBIDDEN = ['지루', '심심', '피곤', '졸리', '졸려', '배고', '배가 고', '무섭', '무서', '힘들', '짜증', '답답', '지쳤', '울지', '슬프',
  '기분이', '기다리기 힘', '많이 기다렸', '오래 기다렸',
  '아이 잔류', '잔류 감지', '사고 예방', '안전 보장', '안전을 지켜', '자동 복원', '자동으로 되돌', '자동으로 닫',
  '손을 내밀', '몸을 내밀', '고개를 내밀', '밖으로 손', '창밖으로', '운전 중', '달리는 동안'];
t('V3Test.noForbiddenPhrases (+ no em dash)', () => {
  const texts = [];
  for (const ep of Episodes.all) {
    texts.push(ep.title, ep.intro, ep.outro, ep.recap, ep.teaser);
    ep.puzzles.forEach((q) => texts.push(q.story, q.reward));
    for (let seed = 0; seed <= 60; seed++) roll(ep, KRandom.ofInt(seed)).forEach((q) => texts.push(title(q.spec), prompt(q.spec), ...hints(q.spec)));
  }
  texts.push(EscapeGame.WIGGLE, EscapeGame.RESUME_ASK, EscapeGame.READY_TO_GO);
  const r = rig(undefined, vehicleState({ socPercent: 40, rangeMeters: 300000 }));
  r.toPuzzle('map-15');
  r.set(100, (s) => ({ ...s, doorOpen: true })); r.set(100, (s) => ({ ...s, doorOpen: false })); r.game.resumeAfterDoor();
  r.game.requestHint(); r.game.requestHint(); r.game.requestHint();
  r.set(100, (s) => ({ ...s, seatHeatDriverLevel: 2, socPercent: 80 }));
  texts.push(...said(r), r.game.ui.endingText);
  for (const x of texts) {
    for (const bad of FORBIDDEN) assert.ok(!x.includes(bad), `"${bad}" in: ${x}`);
    assert.ok(!x.includes('\u2014'), `em dash in: ${x}`);
  }
});

t('Sim: range grows with SoC while charging', () => {
  const a = simCharge(vehicleState({ socPercent: 42, rangeMeters: 210000 }), 60000);
  assert.ok(Math.abs(a.socPercent - 43) < 1e-9 && Math.abs(a.rangeMeters - 215000) < 1e-6);
  assert.equal(simCharge(vehicleState({ socPercent: 42 }), 60000).rangeMeters, null);
});

t('clean run: 3 stars, lightning, kinds, summary', () => {
  const ep = { id: 'f', title: '고정', minutes: 15, intro: '인트로', outro: '끝', color: 0, twists: 0, fixed: true, puzzles: [
    { story: 'a', reward: 'b', spec: Spec.Signals(['L']), color: 0, timedSec: 45 }, { story: 'c', reward: 'd', spec: Spec.Belts({ count: 1 }), color: 0, timedSec: null }] };
  const r = rig(new EscapeGame({ episodes: [ep] }));
  r.toPuzzle('f');
  assert.equal(r.game.ui.bonusSecLeft, 45);
  r.play(r.game.demoSolution(0)); r.tick(EscapeGame.SOLVED_MS + 100);
  r.play(r.game.demoSolution(1)); r.tick(EscapeGame.SOLVED_MS + 100);
  const ui = r.game.ui;
  assert.equal(ui.phase, 'ENDING');
  assert.equal(ui.stars, 3);
  assert.equal(ui.lightning, 1);
  assert.deepEqual(ui.solvedKinds, ['SIGNALS', 'SEATBELT', 'LIGHTNING']);
  assert.equal(ui.summary, '힌트 0번, 건너뛰기 0번, 번개 1개');
});

t('ChargeClock: thin data, noisy steps, stall', () => {
  const c = new ChargeClock();
  c.add(0, 40); c.add(10000, 40.2); c.add(20000, 40.4);
  assert.equal(c.etaMinutes(40.4, 80, true), null);
  const d = new ChargeClock();
  for (let i = 0; i <= 72; i++) { const tm = i * 5000; d.add(tm, Math.trunc(40 + tm / 60000) + [0, 1, -1][i % 3]); }
  const rate = d.ratePerMin();
  assert.ok(rate >= 0.8 && rate <= 1.2, String(rate));
  const eta = d.etaMinutes(46, 80, true);
  assert.ok(eta >= 28 && eta <= 43);
  const s = new ChargeClock();
  for (let i = 0; i <= 12; i++) s.add(i * 60000, 50 + Math.min(i, 3));
  assert.equal(s.ratePerMin(), null);
  assert.equal(s.etaMinutes(80, 80, true), 0);
});

t('IntegrationTest: map15 with driving pause and x30 timelapse', () => {
  let sim = vehicleState({ socPercent: 42 }), now = 0, tl = 1;
  const g = new EscapeGame({ random: KRandom.ofInt(3) });
  const tick = (ms) => { let left = ms; do { const d = Math.min(200, left); now += d; left -= d; sim = simCharge(sim, d, tl); g.onVehicle(sim, now); } while (left > 0); };
  tick(200); g.startMatching(); tick(200);
  g.startEpisode('map-15'); g.skipIntro(); tick(200);
  g.currentPuzzles().forEach((_, i) => {
    if (i === 2) {
      sim = { ...sim, gear: 'DRIVE', speedKmh: 12 }; tick(3000);
      assert.equal(g.ui.phase, 'PAUSED_DRIVING');
      const frozen = g.ui.elapsedSec; tick(5000); assert.equal(g.ui.elapsedSec, frozen);
      sim = { ...sim, gear: 'PARK', speedKmh: 0 }; tick(200);
      assert.equal(g.ui.phase, 'PUZZLE');
    }
    g.demoSolution(i).forEach((st) => { sim = st.change(sim); tick(st.waitMs); });
    assert.equal(g.ui.phase, 'SOLVED', `puzzle ${i}`);
    tick(EscapeGame.SOLVED_MS + 200);
  });
  assert.ok(g.ui.endingText.startsWith('모든 퍼즐을 풀었어요'));
  tl = TIMELAPSE; tick(120000);
  assert.ok(Math.abs(sim.socPercent - 80) < 0.01);
  assert.ok(g.ui.endingText.startsWith('80% 충전 완료!'));
});

// ---------------------------------------------------------------- CustomizationTest.kt (마이 차고)
{
  const { Paint, Wheel, STICKERS, Theme, DEFAULT_MODE_THEMES, DriveMode, CATALOG, isOpen, byId, newlyOpened, needHint, Need, look, encodeLook, decodeLook, shownTheme, shownMood, SKINS, SKINS_LIGHT, contrast, KINDS } = CE;
  const E = (o = {}) => ({ stars: 0, badges: [], clears: 0, ...o });
  const ids = (xs) => xs.map((x) => x.id);

  t('garage: freshFamilyHasTheBasics', () => {
    const none = E();
    assert.deepEqual(ids(Paint.filter((x) => isOpen(x, none))), ['SNOW', 'MIDNIGHT', 'SILVER', 'OCEAN']);
    assert.deepEqual(ids(Wheel.filter((x) => isOpen(x, none))), ['BASIC']);
    assert.ok(STICKERS.every((x) => !isOpen(x, none)));
    assert.ok(Object.values(DEFAULT_MODE_THEMES).every((id) => isOpen(byId(Theme, id), none)), 'default mode mapping must be usable');
    assert.equal(STICKERS.length, 12);
    assert.deepEqual(Object.keys(DEFAULT_MODE_THEMES).sort(), DriveMode.slice().sort());
    assert.ok(Theme.some((x) => !isOpen(x, none)), 'some themes are earned');
  });

  t('garage: rulesAndHints', () => {
    const P = (id) => byId(Paint, id), S = (k) => byId(STICKERS, k);
    assert.ok(isOpen(P('FOREST'), E({ stars: 3 })));
    assert.ok(!isOpen(P('FOREST'), E({ stars: 2 })));
    assert.ok(isOpen(byId(Wheel, 'PINWHEEL'), E({ clears: 2 })));
    assert.ok(isOpen(byId(Theme, 'UNDERSEA'), E({ badges: ['WINDOW', 'MORSE'] })));
    assert.ok(isOpen(S('MORSE'), E({ badges: ['MORSE'] })));
    assert.ok(!isOpen(S('WIPER'), E({ badges: ['MORSE'] })));
    assert.equal(needHint(P('FOREST').need), '별을 3개 모으면 열려요');
    assert.equal(needHint(S('MORSE').need), "'등대' 배지를 받으면 열려요");
    assert.equal(needHint(byId(Wheel, 'PINWHEEL').need), '이야기를 2개 끝내면 열려요');
    assert.equal(needHint(P('SNOW').need), '');
    for (const it of CATALOG) if (JSON.stringify(it.need) !== JSON.stringify(Need())) { const h = needHint(it.need); assert.ok(h && !h.includes('\u2014'), it.label); }
    assert.ok(CATALOG.every((x) => isOpen(x, E({ stars: 12, badges: KINDS, clears: 4 }))));
  });

  t('garage: newlyOpenedListsOnlyTheNewOnes', () => {
    const before = E({ stars: 2, badges: ['WINDOW'] }), after = E({ stars: 4, badges: ['WINDOW', 'MORSE'], clears: 1 });
    assert.deepEqual(newlyOpened(before, after).map((x) => `${x.group}:${x.id}`), ['paint:FOREST', 'wheel:STAR', 'sticker:MORSE', 'theme:UNDERSEA']);
    assert.deepEqual(newlyOpened(after, after), []);
  });

  t('garage: lookRoundTrip + same string as Look.encode()', () => {
    const l = look({ paint: 'CHERRY', wheel: 'GOLD', stickers: { FRONT_DOOR: 'WINDOW', TRUNK: 'LIGHTNING' }, theme: 'STRAWBERRY_MILK',
      modeThemes: { ECO: 'UNDERSEA' }, modeMoods: { SPORT: 0xFF0000, NORMAL: 0x00AA11 }, followMode: false });
    assert.deepEqual(decodeLook(encodeLook(l)), l);
    assert.deepEqual(decodeLook(encodeLook(look())), look());
    assert.equal(encodeLook(l), 'CHERRY|GOLD|FRONT_DOOR:WINDOW,TRUNK:LIGHTNING|STRAWBERRY_MILK|ECO:UNDERSEA|NORMAL:00AA11,SPORT:FF0000|false');
  });

  t('garage: brokenStringsFallBackPartByPart', () => {
    for (const bad of [null, '', 'garbage', '||||||||||']) assert.deepEqual(decodeLook(bad), look());
    const l = decodeLook('PURPLE|STAR|FRONT_DOOR:WINDOW,REAR_DOOR:NOPE,BAD|SPACE_STATION|ECO:RACING,X:Y|SPORT:FFZZ00,ECO:00FF00|maybe');
    assert.equal(l.paint, 'SNOW');
    assert.equal(l.wheel, 'STAR');
    assert.deepEqual(l.stickers, { FRONT_DOOR: 'WINDOW' });
    assert.equal(l.theme, 'SPACE_STATION');
    assert.deepEqual(l.modeThemes, { ECO: 'RACING' });
    assert.deepEqual(l.modeMoods, { ECO: 0x00FF00 });
    assert.equal(l.followMode, true);
  });

  t('garage: skinFollowsTheModeOnlyWhenAsked', () => {
    const l = look({ theme: 'UNDERSEA', modeMoods: { SPORT: 0x123456 } });
    assert.equal(shownTheme(l, 'ECO'), 'FOREST_CAMP');
    assert.equal(shownTheme(l, 'SPORT'), 'RACING');
    assert.equal(shownMood(l, 'SPORT'), 0x123456);
    assert.equal(shownMood(l, 'ECO'), byId(Theme, 'FOREST_CAMP').mood);
    assert.equal(shownTheme(l, null), 'UNDERSEA', 'unknown mode: the fixed pick');
    assert.equal(shownMood(l, null), byId(Theme, 'UNDERSEA').mood);
    const fixed = { ...l, followMode: false };
    assert.equal(shownTheme(fixed, 'SPORT'), 'UNDERSEA');
    assert.equal(shownMood(fixed, 'SPORT'), byId(Theme, 'UNDERSEA').mood);
  });

  t('garage: skins, default = style.css palette, text contrast >= 4.5', () => {
    assert.deepEqual(Object.keys(SKINS), ids(Theme));
    const css = _readCss();
    for (const [k, v] of Object.entries({ bg: '--bg', deep: '--bg-deep', surface: '--surface', surface2: '--surface2', text: '--text', sub: '--sub', faint: '--faint', gold: '--gold', amber: '--amber', onGold: '--on-gold' }))
      assert.ok(new RegExp(`${v}:\\s*${SKINS.NIGHT_SKY[k]}\\b`, 'i').test(css), `NIGHT_SKY.${k} = style.css ${v}`);
    assert.deepEqual(Object.keys(SKINS_LIGHT), ids(Theme));
    for (const [id, s] of [...Object.entries(SKINS), ...Object.entries(SKINS_LIGHT).map(([k, v]) => [k + ' (light)', v])]) {
      for (const fg of ['text', 'sub', 'faint', 'gold', 'amber']) for (const bg of ['bg', 'deep', 'surface', 'surface2', 'top'])
        assert.ok(contrast(s[fg], s[bg]) >= 4.5, `${id}: ${fg} on ${bg} = ${contrast(s[fg], s[bg]).toFixed(2)}`);
      for (const bg of ['gold', 'amber']) assert.ok(contrast(s.onGold, s[bg]) >= 4.5, `${id}: onGold on ${bg} = ${contrast(s.onGold, s[bg]).toFixed(2)}`);
    }
  });
}

console.log(`\n${n} checks passed`);
