/*
 * Our IONIQ 9 in 3D (assets/ioniq9.glb, built in Blender by tools/ioniq9/build_ioniq9.py; no Hyundai asset).
 * The GLB is geometry + material names; LOOKS below gives paint, glass and lamps their finish. Paint = material body_paint.
 * Two views at most, so two WebGL contexts: 'drive' (Pleos driving view: rear 3/4 parked, straight from behind and small while driving)
 * and 'garage' (마이 차고 preview, drag to turn). ui.js calls CarEscape3D.view(slot, host, look, still) after it renders;
 * a view draws only while its host is on screen. If WebGL or the CDN is missing, the render PNG inside the host stays.
 */
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const CE = window.CarEscape;
// Sticker slots, Blender (x, y, z) -> glTF (x, z, -y); the visible side faces +Z (assets/ioniq9_sticker_slots.json).
const SLOTS = { FRONT_DOOR: [0.62, 0.74], REAR_DOOR: [-0.62, 0.74], TRUNK: [-2.12, 0.92] };
// How each part wears light. Names are the Blender material names; the GLB carries plain PBR values, the car look is set here:
// clear-coated paint, dark reflective glass, piano-black pillars and fascia, lit pixel lamps.
const LOOKS = {
  body_paint: { metalness: 0.2, roughness: 0.42, clearcoat: 1, clearcoatRoughness: 0.05, envMapIntensity: 0.85 },
  glass: { color: 0x0b1017, metalness: 0, roughness: 0.03, clearcoat: 1, clearcoatRoughness: 0.03, envMapIntensity: 0.6 },
  black_gloss: { color: 0x050506, metalness: 0, roughness: 0.14, clearcoat: 1, clearcoatRoughness: 0.05, envMapIntensity: 0.7 },
  lamp_lens: { color: 0x0b0d10, roughness: 0.08, clearcoat: 1, envMapIntensity: 1.4 },
  lamp_front: { color: 0xffffff, emissive: 0xe6f0ff, emissiveIntensity: 2.4 },
  lamp_rear: { color: 0xd0100a, emissive: 0xff0500, emissiveIntensity: 1.25 },
  lamp_pixel: { color: 0x9aa3ae, emissive: 0xc8d4e6, emissiveIntensity: 0.3, roughness: 0.2 },
  chrome: { metalness: 1, roughness: 0.3, envMapIntensity: 1.2 },
  rim: { metalness: 1, roughness: 0.24, envMapIntensity: 1.2 },
  rim_gold: { metalness: 1, roughness: 0.22, envMapIntensity: 1.25 },
};
function dress(root) {
  const made = new Map();
  root.traverse((o) => {
    const name = o.isMesh && o.material?.name, p = LOOKS[name];
    if (!p) return;
    if (!made.has(name)) {
      const m = new THREE.MeshPhysicalMaterial({ color: o.material.color, metalness: o.material.metalness, roughness: o.material.roughness, ...p });
      m.name = name; made.set(name, m);
    }
    o.material = made.get(name);
  });
}
const gltfP = new GLTFLoader().loadAsync('assets/ioniq9.glb').then((gltf) => { dress(gltf.scene); return gltf; });
const views = {};
let broken = false;

const stickerTex = new Map();
function stickerTexture(kind) {
  let t = stickerTex.get(kind);
  if (t) return t;
  const c = document.createElement('canvas'); c.width = c.height = 128;
  t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; stickerTex.set(kind, t);
  const im = new Image();
  im.onload = () => { c.getContext('2d').drawImage(im, 0, 0, 128, 128); t.needsUpdate = true; for (const v of Object.values(views)) v.dirty = true; };
  im.src = 'data:image/svg+xml,' + encodeURIComponent(window.CarEscapeSticker(kind));
  return t;
}
const discGeo = new THREE.CircleGeometry(0.17, 40);

/** A car studio to reflect: dim cove, one big softbox overhead, a bright band at the horizon on each side (it draws the highlight
 *  down the whole body), panels ahead and behind, a pale floor. Car axes: length = X, visible side = +Z. Values are HDR. */
function studio() {
  const s = new THREE.Scene();
  s.background = new THREE.Color(0.16, 0.17, 0.19);
  const panel = (w, h, pos, rot, k) => {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), new THREE.MeshBasicMaterial({ color: new THREE.Color(k, k, k * 1.03), side: THREE.DoubleSide }));
    m.position.set(...pos); m.rotation.set(...rot); s.add(m);
  };
  panel(11, 5.5, [0, 6.5, 0], [Math.PI / 2, 0, 0], 6);          // overhead softbox: hood, roof and glass
  panel(18, 1.3, [0, 0.8, 8], [0, 0, 0], 6.5);                 // horizon band, visible side: the highlight line along the doors
  panel(18, 1.5, [0, 0.85, -8], [0, 0, 0], 3);                  // horizon band, far side
  panel(7, 1.5, [10, 0.85, 0], [0, Math.PI / 2, 0], 3.5);       // ahead
  panel(7, 1.5, [-10, 0.85, 0], [0, Math.PI / 2, 0], 3.5);      // behind
  panel(60, 60, [0, -0.02, 0], [Math.PI / 2, 0, 0], 0.5);       // floor
  return s;
}

const SHADOW = [7.2, 3.8]; // ground plane (m) the contact shadow is painted on
/** Soft contact shadow, car-shaped: a wide penumbra, a darker core under the floor, and a patch under each tyre. */
function shadowTexture() {
  const c = document.createElement('canvas'); c.width = 512; c.height = 270;
  const x = c.getContext('2d'), k = c.width / SHADOW[0], OFF = 2000;
  // only the blurred shadow of each shape lands on the canvas (the shape itself is drawn OFF px away)
  const blob = (w, d, blur, a, cx = 0, cz = 0) => {
    x.shadowColor = `rgba(0,0,0,${a})`; x.shadowBlur = blur * k; x.shadowOffsetX = OFF;
    x.beginPath(); x[x.roundRect ? 'roundRect' : 'rect'](c.width / 2 + (cx - w / 2) * k - OFF, c.height / 2 + (cz - d / 2) * k, w * k, d * k, 0.25 * k); x.fill();
  };
  blob(4.9, 1.8, 0.55, 0.45); blob(4.5, 1.5, 0.22, 0.4);
  for (const wx of [1.67, -1.46]) for (const wz of [0.83, -0.83]) blob(0.5, 0.26, 0.09, 0.55, wx, wz);
  return new THREE.CanvasTexture(c);
}

const seenIO = new IntersectionObserver((es) => { for (const e of es) for (const v of Object.values(views)) if (v.host === e.target) { v.seen = e.isIntersecting; v.dirty = true; } });
const sizeRO = new ResizeObserver((es) => { for (const e of es) for (const v of Object.values(views)) if (v.host === e.target) resize(v); });

function makeView(slot) {
  // No GPU (software WebGL): keep the PNG. It would be slow, and its compositor readback floods the console.
  const canvas = document.createElement('canvas');
  const attrs = { antialias: true, alpha: true, powerPreference: 'low-power', failIfMajorPerformanceCaveat: true };
  const context = canvas.getContext('webgl2', attrs);
  if (!context) return null;
  const dbg = context.getExtension('WEBGL_debug_renderer_info');
  if (/swiftshader|llvmpipe|software/i.test(dbg ? context.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : '')) { context.getExtension('WEBGL_lose_context')?.loseContext(); return null; }
  let renderer;
  try { renderer = new THREE.WebGLRenderer({ canvas, context, ...attrs }); } catch (e) { return null; }
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1.0;
  const scene = new THREE.Scene();
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(studio(), 0.04).texture; // what the clear coat and glass reflect
  pmrem.dispose();
  // key from above the visible (+Z) side, a weaker one from behind so the tail is not flat in the driving view
  const sun = new THREE.DirectionalLight(0xffffff, 0.85); sun.position.set(3, 8, 5); scene.add(sun);
  const back = new THREE.DirectionalLight(0xdfe8ff, 0.4); back.position.set(-6, 4, 2); scene.add(back);
  const camera = new THREE.PerspectiveCamera(26, 1, 0.5, 60); // long lens: car proportions stay true; tight depth range keeps the flush trim clean
  const pivot = new THREE.Group(); scene.add(pivot);
  const v = { slot, renderer, scene, camera, pivot, host: null, seen: true, dirty: true, ready: false, still: false, lookKey: '', look: null, controls: null };
  renderer.domElement.addEventListener('webglcontextlost', (e) => { e.preventDefault(); broken = true; detach(v); });
  if (slot === 'garage') {
    const c = new OrbitControls(camera, renderer.domElement);
    Object.assign(c, { enablePan: false, enableZoom: false, enableDamping: true, dampingFactor: 0.12, minPolarAngle: POLAR[0], maxPolarAngle: POLAR[1], rotateSpeed: 0.8 });
    c.target.set(0, GARAGE_Y, 0);
    c.addEventListener('change', () => { v.dirty = true; });
    v.controls = c;
  }
  gltfP.then((gltf) => {
    const model = gltf.scene.clone(true);
    const box = new THREE.Box3().setFromObject(model), mid = box.getCenter(new THREE.Vector3());
    model.position.set(-mid.x, -box.min.y, -mid.z);
    pivot.add(model);
    const shadow = new THREE.Mesh(new THREE.PlaneGeometry(...SHADOW), new THREE.MeshBasicMaterial({ map: shadowTexture(), transparent: true, depthWrite: false }));
    shadow.rotation.x = -Math.PI / 2; shadow.position.y = 0.005; pivot.add(shadow);
    v.model = model; v.stickers = new THREE.Group(); model.add(v.stickers);
    v.body = []; model.traverse((o) => { if (o.isMesh && o.material?.name === 'body_paint') v.body.push(o); });
    v.ready = true;
    if (v.look) applyLook(v, v.look);
    frame(v);
  }, () => { broken = true; detach(v); });
  return v;
}

function detach(v) {
  if (v.host) { v.host.classList.remove('live'); seenIO.unobserve(v.host); sizeRO.unobserve(v.host); }
  v.host = null;
  v.renderer.domElement.remove();
}

function resize(v) {
  if (!v.host) return;
  const w = v.host.clientWidth, h = v.host.clientHeight;
  if (!w || !h) return;
  // draw at the size it shows (the Pleos mock is scaled with transforms), never past 2x
  const shown = v.host.getBoundingClientRect().width / w;
  v.renderer.setPixelRatio(Math.min(2, Math.max(0.5, shown * devicePixelRatio)));
  v.renderer.setSize(w, h, false);
  v.camera.aspect = w / h;
  frame(v);
}

const POLAR = [1.27, 1.42], GARAGE_Y = 0.42; // garage: how far the view tips (rad from straight up), and the height it looks at (low: centres the car on screen)
const BOX = [2.56, 1.79, 1.2];              // the car: half length, height, half width with mirrors (m)
/** Garage and title: the nearest the camera can sit with the whole car in view at every turn the family can make.
 *  Checks the car's box corners at every angle, so the car is as big as the stage allows and never clipped. */
function fitDist(camera) {
  const t = Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2), fx = 0.90 * t * camera.aspect, fy = 0.97 * t; // the box corners are empty air, so the car itself ends up ~80% wide
  let dist = 0;
  for (const polar of POLAR) for (let az = 0; az < Math.PI * 2; az += Math.PI / 12) {
    const se = Math.cos(polar), ce = Math.sin(polar), ca = Math.cos(az), sa = Math.sin(az);
    const d = [ce * ca, se, ce * sa], r = [-sa, 0, ca], u = [-se * ca, ce, -se * sa]; // toward the camera, right, up
    for (const x of [-BOX[0], BOX[0]]) for (const y of [-GARAGE_Y, BOX[1] - GARAGE_Y]) for (const z of [-BOX[2], BOX[2]]) {
      const dot = (a) => a[0] * x + a[1] * y + a[2] * z;
      dist = Math.max(dist, dot(d) + Math.abs(dot(r)) / fx, dot(d) + Math.abs(dot(u)) / fy);
    }
  }
  return dist;
}

/** Camera distance and direction: garage = as large as fits at any turn, drive = the Pleos driving view. */
function frame(v) {
  const vf = THREE.MathUtils.degToRad(v.camera.fov), hf = 2 * Math.atan(Math.tan(vf / 2) * v.camera.aspect);
  const r = 2.75, moving = v.slot === 'drive' && v.still, garage = v.slot === 'garage';
  // moving: the 1.98 m wide car is ~179 of the 853 px panel in emulator capture 21 (D, 60 km/h); the host is 433 wide then; 0.30 not 0.41, the tail is nearer the camera than the target
  const dist = moving ? 1.98 / (2 * 0.30 * Math.tan(hf / 2)) : garage ? fitDist(v.camera) : r / Math.sin(Math.min(vf, hf) / 2) * 0.9;
  // drive: parked = rear 3/4 from the visible (+Z) side; moving = straight from behind, a little above, centred (capture 21)
  const dir = v.slot !== 'drive' ? new THREE.Vector3(1, 0.36, 1.15) : v.still ? new THREE.Vector3(-1, 0.42, 0) : new THREE.Vector3(-1, 0.62, 0.95);
  if (v.controls && v.framed) dir.copy(v.camera.position).sub(v.controls.target); // keep where the family turned it
  v.framed = true;
  const target = new THREE.Vector3(0, garage ? GARAGE_Y : 0.8, 0);
  v.camera.position.copy(target).addScaledVector(dir.normalize(), dist);
  v.camera.lookAt(target);
  v.camera.updateProjectionMatrix();
  if (v.controls) v.controls.update();
  v.dirty = true;
}

function applyLook(v, look) {
  const paint = CE.byId(CE.Paint, look.paint).rgb;
  for (const m of v.body) m.material.color.setHex(paint, THREE.SRGBColorSpace);
  for (const w of CE.Wheel) { const g = v.model.getObjectByName('wheels_' + w.id); if (g) g.visible = w.id === look.wheel; }
  for (const m of v.stickers.children) m.material.dispose(); // textures are cached per kind, only the materials go
  v.stickers.clear();
  v.model.updateMatrixWorld(true);
  const ray = new THREE.Raycaster();
  for (const slot of CE.StickerSlot) {
    const kind = look.stickers[slot];
    if (!kind) continue;
    const [x, y] = SLOTS[slot];
    // put the decal on the actual body surface: cast in from the visible (+Z) side, fall back to the slot's own depth
    const from = v.model.localToWorld(new THREE.Vector3(x, y, 3)), to = v.model.localToWorld(new THREE.Vector3(x, y, 0));
    ray.set(from, to.sub(from).normalize());
    const hit = ray.intersectObjects(v.body, false)[0];
    const disc = new THREE.Mesh(discGeo, new THREE.MeshStandardMaterial({ map: stickerTexture(kind), transparent: true, roughness: 0.45, polygonOffset: true, polygonOffsetFactor: -4 }));
    v.stickers.add(disc);
    if (hit) {
      const n = hit.face.normal.clone().transformDirection(hit.object.matrixWorld);
      disc.position.copy(v.stickers.worldToLocal(hit.point.clone().addScaledVector(n, 0.008)));
      disc.lookAt(hit.point.clone().add(n));
    } else disc.position.set(x, y, 1.0);
  }
  v.dirty = true;
}

function view(slot, host, look, still) {
  let v = views[slot];
  if (!host || broken) { if (v) detach(v); return; }
  if (!v) { v = makeView(slot); if (!v) { broken = true; return; } views[slot] = v; }
  if (v.host !== host) {
    if (v.host) { v.host.classList.remove('live'); seenIO.unobserve(v.host); sizeRO.unobserve(v.host); }
    v.host = host; host.appendChild(v.renderer.domElement);
    seenIO.observe(host); sizeRO.observe(host); resize(v);
    if (v.drawn) host.classList.add('live');
  }
  if (v.still !== still) {
    v.still = still;
    if (slot === 'drive') frame(v);
  }
  const key = CE.encodeLook(look);
  if (key !== v.lookKey) { v.lookKey = key; v.look = look; if (v.ready) applyLook(v, look); }
  v.dirty = true;
}

function loop() {
  for (const v of Object.values(views)) {
    if (!v.host || !v.ready || !v.seen || document.hidden) continue;
    if (v.controls && v.controls.update()) v.dirty = true;
    if (!v.dirty) continue;
    v.dirty = false;
    v.renderer.render(v.scene, v.camera);
    if (!v.drawn) { v.drawn = true; v.host.classList.add('live'); }
  }
  requestAnimationFrame(loop);
}
requestAnimationFrame(loop);

window.CarEscape3D = { view };
window.dispatchEvent(new Event('car3d-ready'));
