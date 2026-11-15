// Collects every sentence the game can speak (sentence = one voice clip). node voice/enumerate.mjs > voice/lines.json
import '../game.js';
const C = globalThis.CarEscape;
export const split = (t) => (t.match(/[^.!?,]+[.!?,]+|[^.!?,]+$/g) || []).map((s) => s.trim()).filter(Boolean);
const out = new Set();
const add = (t) => split(t).forEach((s) => out.add(s));
const vs = C.vehicleState();
add('남은 충전 시간에 딱 맞는 이야기를 골랐어요.');
add(C.EscapeGame.WIGGLE);
add('와이퍼가 쉬어야 해요. 이 금고는 열어 둘게요.');
add('모든 퍼즐을 풀었어요! 충전이 끝나면 엔딩이 열려요.');
add('번개 배지!');
for (const t of [' 출발 전에 창문을 모두 닫고 열선을 꺼 주세요.', ' 출발 전에 창문을 모두 닫아 주세요.', ' 출발 전에 열선을 꺼 주세요.', '다음 충전 때 또 만나요.', '엔딩 크레딧과 함께 출발하세요.', '다음 충전 때 다시 만나요.']) add(t);
for (const ep of C.Episodes.all) {
  add(ep.intro); add(ep.outro);
  for (let n = 1; n <= 8; n++) for (let k = 0; k <= n; k++) add(`퍼즐 ${n}개 중 ${k}개 완료!`);
}
for (let s = 0; s <= 100; s++) add(`충전이 멈췄어요. 지금 배터리는 ${s}퍼센트예요.`);
for (let t = 0; t <= 100; t++) { add(`${t}% 충전 완료!`); add(`보너스 엔딩! 충전이 먼저 ${t}%에 도착했어요.`); }
for (let k = 1; k <= 8; k++) add(`남은 퍼즐 ${k}개.`);
// v3
add('이어서 할까요?'); add('출발 준비 완료!'); add('마지막 미션, 원래대로 출발!'); add('시작 때와 달라진 곳을 돌려 놓아요.');
for (const w of ['운전석 창문', '조수석 창문', '2열 왼쪽 창문', '2열 오른쪽 창문', '시트 열선', '핸들 열선', '독서등', '와이퍼']) { add(w + ','); add(w + '.'); }
for (let n = 1; n <= 8; n++) add(`오늘은 ${n}화예요.`);
for (let k = 0; k <= 900; k += 10) add(`이제 약 ${k}킬로미터 갈 수 있어요.`);
for (const ep of C.Episodes.all) { if (ep.recap) add(ep.recap); if (ep.teaser) add(ep.teaser); }
const eps = C.Episodes.all;
for (let seed = 0; seed < 4000; seed++) for (const ep of eps) {
  const ps = C.roll(ep, C.KRandom.ofLong(seed), [], 1).map((p) => ({ ...p, spec: C.fit(p.spec, vs) }));
  for (const p of ps) {
    const q = p.spec.type === 'Riddle' ? `${p.spec.question} ` : '';
    add(`${p.story} ${q}${C.prompt(p.spec)}.`);
    if (p.timedSec != null) add(` 번개 금고예요! ${p.timedSec}초 안에 풀면 번개 배지를 받아요.`);
    add(p.reward);
    C.hints(p.spec).forEach(add);
  }
}
console.log(JSON.stringify([...out], null, 1));
