/* =========================================================================
 * ParticleDrawing 粒子动画编辑器
 * 依赖全局 THREE（vendor/three.min.js）与 THREE.OrbitControls（vendor/OrbitControls.js）
 * ======================================================================= */

const OrbitControls = THREE.OrbitControls;

/* =========================================================================
 * 常量
 * ======================================================================= */

const STYLES = [
  'DUST', 'FLAME', 'SOUL_FIRE', 'NOTE', 'HEART', 'SPARK',
  'GLOW', 'BUBBLE', 'DRAGON_BREATH', 'SMOKE',
];

const EASINGS = [
  ['LINEAR', 0, 0, 1, 1],
  ['EASE_IN', 0.42, 0, 1, 1],
  ['EASE_OUT', 0, 0, 0.58, 1],
  ['EASE_IN_OUT', 0.42, 0, 0.58, 1],
  ['EASE_IN_QUAD', 0.55, 0.085, 0.68, 0.53],
  ['EASE_OUT_QUAD', 0.25, 0.46, 0.45, 0.94],
  ['EASE_IN_OUT_QUAD', 0.455, 0.03, 0.515, 0.955],
  ['EASE_IN_CUBIC', 0.55, 0.055, 0.675, 0.19],
  ['EASE_OUT_CUBIC', 0.215, 0.61, 0.355, 1.0],
  ['EASE_IN_OUT_CUBIC', 0.645, 0.045, 0.355, 1.0],
  ['EASE_IN_BOUNCE', 0.71, 0.01, 0.53, 1.61],
  ['EASE_OUT_BOUNCE', 0.29, -0.61, 0.47, 0.99],
  ['EASE_IN_ELASTIC', 0.56, 0.01, 0.73, 1.61],
  ['EASE_OUT_ELASTIC', 0.25, -0.61, 0.44, 0.99],
];

const PLANES = {
  XZ: { axes: ['X', 'Z'], constant: 'Y', normal: new THREE.Vector3(0, 1, 0), toWorld: (u, v, o) => [u, o, v] },
  XY: { axes: ['X', 'Y'], constant: 'Z', normal: new THREE.Vector3(0, 0, 1), toWorld: (u, v, o) => [u, v, o] },
  YZ: { axes: ['Y', 'Z'], constant: 'X', normal: new THREE.Vector3(1, 0, 0), toWorld: (u, v, o) => [o, u, v] },
};

// 粒子可动画属性分量（树状时间轴的叶子节点）
const PROPERTY_DEFS = [
  { key: 'x', label: 'X', track: 'pos', index: 0 },
  { key: 'y', label: 'Y', track: 'pos', index: 1 },
  { key: 'z', label: 'Z', track: 'pos', index: 2 },
  { key: 'r', label: 'R', track: 'col', index: 0 },
  { key: 'g', label: 'G', track: 'col', index: 1 },
  { key: 'b', label: 'B', track: 'col', index: 2 },
  { key: 'a', label: 'A', track: 'col', index: 3 },
  { key: 'scl', label: '缩放', track: 'scl', index: 0 },
];

const DEFAULT_EASING = 3;
const SNAP_STEP = 0.5;

/* =========================================================================
 * 状态
 * ======================================================================= */

const state = {
  name: 'my_animation',
  loop: true,
  particles: [],
  groups: {},
  tracks: [],
  tool: 'select',
  drawPlane: 'XZ',
  selected: new Set(),
  selectedGroup: null,
  expandedParticles: new Set(),
  expandedProps: new Set(),
  snap: false,
  fileHandle: null,
  time: 0,
  playing: false,
  defaultEasing: DEFAULT_EASING,
  idCounter: 0,
  groupCounter: 0,
};

function nextId() { return 'p' + (state.idCounter++); }
function nextGroupName() { return 'g' + (state.groupCounter++); }
function getParticle(id) { return state.particles.find(p => p.id === id); }
function findTrack(prop, id) { return state.tracks.find(tr => tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === id); }

/* =========================================================================
 * 缓动求值
 * ======================================================================= */

function cubicBezier(t, x1, y1, x2, y2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const xFor = s => ((ax * s + bx) * s + cx) * s;
  const dxFor = s => (3 * ax * s + 2 * bx) * s + cx;
  let s = t;
  for (let i = 0; i < 8; i++) { const e = xFor(s) - t; if (Math.abs(e) < 1e-6) break; s -= e / dxFor(s); }
  return ((ay * s + by) * s + cy) * s;
}

function easeVal(t, easing) {
  const t1 = Math.min(1, Math.max(0, t));
  if (Array.isArray(easing)) return cubicBezier(t1, easing[0], easing[1], easing[2], easing[3]);
  const p = EASINGS[easing] || EASINGS[0];
  return cubicBezier(t1, p[1], p[2], p[3], p[4]);
}

function easeInOut(t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

/* =========================================================================
 * 迷你表达式求值器
 * ======================================================================= */

const FUNCS = {
  sin: 1, cos: 1, tan: 1, sqrt: 1, abs: 1, exp: 1, log: 1, ln: 1,
  floor: 1, ceil: 1, round: 1, pow: 2, min: 2, max: 2, clamp: 3, lerp: 3,
};
const FUNC_IMPL = {
  sin: a => Math.sin(a), cos: a => Math.cos(a), tan: a => Math.tan(a),
  sqrt: a => Math.sqrt(a), abs: a => Math.abs(a), exp: a => Math.exp(a),
  log: a => Math.log(a), ln: a => Math.log(a),
  floor: a => Math.floor(a), ceil: a => Math.ceil(a), round: a => Math.round(a),
  pow: (a, b) => Math.pow(a, b), min: (a, b) => Math.min(a, b), max: (a, b) => Math.max(a, b),
  clamp: (a, b, c) => Math.min(Math.max(a, b), c), lerp: (a, b, c) => a + (b - a) * c,
};
const PREC = { '+': 1, '-': 1, '*': 2, '/': 2, '%': 2, '^': 3 };

function tokenize(expr) {
  const tokens = [];
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t' || c === '\n') { i++; continue; }
    if ((c >= '0' && c <= '9') || c === '.') {
      let j = i; while (j < expr.length && /[0-9.]/.test(expr[j])) j++;
      tokens.push({ t: 'num', v: parseFloat(expr.slice(i, j)) }); i = j; continue;
    }
    if (/[a-zA-Z_]/.test(c)) {
      let j = i; while (j < expr.length && /[a-zA-Z0-9_]/.test(expr[j])) j++;
      const name = expr.slice(i, j);
      if (name === 'pi') tokens.push({ t: 'num', v: Math.PI });
      else if (name === 'e') tokens.push({ t: 'num', v: Math.E });
      else if (name in FUNCS) tokens.push({ t: 'func', name });
      else tokens.push({ t: 'var', name });
      i = j; continue;
    }
    if ('+-*/%^(),'.includes(c)) { tokens.push({ t: c }); i++; continue; }
    i++;
  }
  return tokens;
}

function evaluate(expr, vars) {
  const output = [];
  const stack = [];
  for (const tk of tokenize(expr)) {
    if (tk.t === 'num') output.push(tk.v);
    else if (tk.t === 'var') {
      if (!(tk.name in vars)) throw new Error('未知变量: ' + tk.name);
      output.push(vars[tk.name]);
    } else if (tk.t === 'func') stack.push(tk.name);
    else if (tk.t === ',') { while (stack.length && stack[stack.length - 1] !== '(') output.push(stack.pop()); }
    else if (tk.t === '(') stack.push(tk.t);
    else if (tk.t === ')') {
      while (stack.length && stack[stack.length - 1] !== '(') output.push(stack.pop());
      stack.pop();
      if (stack.length && stack[stack.length - 1] in FUNCS) output.push(stack.pop());
    } else if (tk.t in PREC) {
      const prec = PREC[tk.t], rightAssoc = tk.t === '^';
      while (stack.length) {
        const top = stack[stack.length - 1];
        if (top === '(') break;
        if (top in FUNCS) { output.push(stack.pop()); continue; }
        if (top in PREC && (PREC[top] > prec || (PREC[top] === prec && !rightAssoc))) output.push(stack.pop());
        else break;
      }
      stack.push(tk.t);
    }
  }
  while (stack.length) output.push(stack.pop());
  const s = [];
  for (const o of output) {
    if (typeof o === 'number') s.push(o);
    else if (o in FUNCS) { const args = []; for (let k = 0; k < FUNCS[o]; k++) args.unshift(s.pop()); s.push(FUNC_IMPL[o](...args)); }
    else if (o in PREC) {
      const b = s.pop(), a = s.pop();
      if (o === '+') s.push(a + b); else if (o === '-') s.push(a - b); else if (o === '*') s.push(a * b);
      else if (o === '/') s.push(a / b); else if (o === '%') s.push(a % b); else if (o === '^') s.push(Math.pow(a, b));
    }
  }
  return s[s.length - 1];
}

/* =========================================================================
 * Three.js 场景
 * ======================================================================= */

const viewport = document.getElementById('viewport');
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.setClearColor(0x14161c, 1);
viewport.appendChild(renderer.domElement);

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 1000);
camera.position.set(12, 8, 14);

const controls = new OrbitControls(camera, renderer.domElement);
controls.target.set(0, 1, 0);
controls.update();
controls.mouseButtons = { LEFT: null, MIDDLE: THREE.MOUSE.ROTATE, RIGHT: THREE.MOUSE.PAN };

const raycaster = new THREE.Raycaster();
const pointer = new THREE.Vector2();

const grid = new THREE.GridHelper(40, 40, 0x4a5568, 0x2c3342);
grid.position.y = 0;
scene.add(grid);
scene.add(new THREE.AxesHelper(4));

// 无模糊小方形贴图（2D 广告牌，始终朝向摄像头）
function makeSquareTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(1, 1, 14, 14);
  const tex = new THREE.CanvasTexture(c);
  tex.minFilter = THREE.NearestFilter;
  tex.magFilter = THREE.NearestFilter;
  return tex;
}

function focalLengthPx() {
  const h = renderer.domElement.clientHeight || 1;
  return h / (2 * Math.tan(THREE.MathUtils.degToRad(camera.fov) / 2));
}

const pointsMaterial = new THREE.ShaderMaterial({
  uniforms: { uMap: { value: makeSquareTexture() }, uPixelScale: { value: focalLengthPx() } },
  vertexShader: `
    uniform float uPixelScale;
    attribute vec4 aColor;
    attribute float aSize;
    varying vec4 vColor;
    void main() {
      vColor = aColor;
      vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
      gl_PointSize = aSize * uPixelScale / max(0.1, -mvPosition.z);
      gl_Position = projectionMatrix * mvPosition;
    }
  `,
  fragmentShader: `
    uniform sampler2D uMap;
    varying vec4 vColor;
    void main() {
      vec4 tex = texture2D(uMap, gl_PointCoord);
      gl_FragColor = vec4(vColor.rgb, vColor.a) * tex;
    }
  `,
  transparent: true,
  depthWrite: false,
  blending: THREE.NormalBlending,
});

// 选中描边（橙色方框，略大）
const selectedMaterial = new THREE.ShaderMaterial({
  uniforms: { uMap: { value: makeSquareTexture() }, uPixelScale: { value: focalLengthPx() } },
  vertexShader: `
    uniform float uPixelScale;
    attribute float aSize;
    void main() {
      vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
      gl_PointSize = aSize * uPixelScale / max(0.1, -mvPosition.z) * 1.25;
      gl_Position = projectionMatrix * mvPosition;
    }
  `,
  fragmentShader: `
    uniform sampler2D uMap;
    void main() {
      vec4 tex = texture2D(uMap, gl_PointCoord);
      gl_FragColor = vec4(1.0, 0.6, 0.25, 1.0) * tex;
    }
  `,
  transparent: true,
  depthWrite: false,
  blending: THREE.NormalBlending,
});

let points = new THREE.Points(new THREE.BufferGeometry(), pointsMaterial);
let selectedPoints = new THREE.Points(new THREE.BufferGeometry(), selectedMaterial);
let previewPoints = new THREE.Points(new THREE.BufferGeometry(), pointsMaterial);
scene.add(points);
scene.add(selectedPoints);
scene.add(previewPoints);

const gizmoGroup = new THREE.Group();
scene.add(gizmoGroup);
gizmoGroup.visible = false;
(function buildGizmo() {
  const defs = { X: [1, 0, 0, 0xff5555], Y: [0, 1, 0, 0x55ff55], Z: [0, 0, 1, 0x5588ff] };
  for (const [axis, [x, y, z, color]] of Object.entries(defs)) {
    const arrow = new THREE.ArrowHelper(new THREE.Vector3(x, y, z), new THREE.Vector3(0, 0, 0), 1.2, color, 0.25, 0.12);
    arrow.name = axis;
    gizmoGroup.add(arrow);
  }
})();

let camTransition = null;

/* =========================================================================
 * 动画状态查询
 * ======================================================================= */

function baseValue(p, prop) {
  if (prop === 'pos') return p.pos;
  if (prop === 'col') return p.color;
  return [p.scale];
}

function tracksForParticle(prop, pId) {
  for (const tr of state.tracks) if (tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === pId) return tr;
  for (const tr of state.tracks) {
    if (tr.pr !== prop) continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const members = state.groups[id.slice(2)];
        if (members && members.includes(pId)) return tr;
      }
    }
  }
  return null;
}

function particleValueAt(p, prop, T) {
  const tr = tracksForParticle(prop, p.id);
  const base = baseValue(p, prop);
  if (!tr || tr.kf.length === 0) return base;
  const kfs = tr.kf;
  if (T < kfs[0][0]) return base;
  if (T >= kfs[kfs.length - 1][0]) return kfs[kfs.length - 1][1];
  for (let i = 0; i < kfs.length - 1; i++) {
    const a = kfs[i], b = kfs[i + 1];
    if (T >= a[0] && T <= b[0]) {
      const dur = b[0] - a[0];
      return lerpArray(a[1], b[1], easeVal(dur === 0 ? 1 : (T - a[0]) / dur, a[2]));
    }
  }
  return base;
}

function lerpArray(a, b, t) {
  const out = new Array(a.length);
  for (let i = 0; i < a.length; i++) out[i] = a[i] + (b[i] - a[i]) * t;
  return out;
}

function currentVisual(p) {
  return {
    pos: particleValueAt(p, 'pos', state.time),
    color: particleValueAt(p, 'col', state.time),
    scale: particleValueAt(p, 'scl', state.time)[0],
  };
}

function maxTick() {
  let m = 0;
  for (const tr of state.tracks) for (const k of tr.kf) m = Math.max(m, k[0]);
  return m;
}

/* =========================================================================
 * 渲染
 * ======================================================================= */

function setPointsGeometry(pts, positions, colors, sizes) {
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(positions, 3));
  geo.setAttribute('aColor', new THREE.BufferAttribute(colors, 4));
  geo.setAttribute('aSize', new THREE.BufferAttribute(sizes, 1));
  const old = pts.geometry;
  pts.geometry = geo;
  old.dispose();
}

function rebuildPoints() {
  const n = state.particles.length;
  const positions = new Float32Array(n * 3), colors = new Float32Array(n * 4), sizes = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const p = state.particles[i];
    const v = currentVisual(p);
    positions[i * 3] = v.pos[0]; positions[i * 3 + 1] = v.pos[1]; positions[i * 3 + 2] = v.pos[2];
    colors[i * 4] = v.color[0]; colors[i * 4 + 1] = v.color[1]; colors[i * 4 + 2] = v.color[2]; colors[i * 4 + 3] = v.color[3];
    sizes[i] = Math.max(0.02, v.scale);
  }
  setPointsGeometry(points, positions, colors, sizes);

  const sel = state.particles.filter(p => state.selected.has(p.id));
  const spos = new Float32Array(sel.length * 3), ssiz = new Float32Array(sel.length);
  for (let i = 0; i < sel.length; i++) {
    const v = currentVisual(sel[i]);
    spos[i * 3] = v.pos[0]; spos[i * 3 + 1] = v.pos[1]; spos[i * 3 + 2] = v.pos[2];
    ssiz[i] = Math.max(0.02, v.scale);
  }
  setPointsGeometry(selectedPoints, spos, new Float32Array(sel.length * 4), ssiz);

  updateGizmo();
  drawTimeline();
  updatePropPanel();
  refreshTreeSelection();
}

function setPreview(positions) {
  const n = positions.length;
  const pos = new Float32Array(n * 3), col = new Float32Array(n * 4), siz = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    pos[i * 3] = positions[i][0]; pos[i * 3 + 1] = positions[i][1]; pos[i * 3 + 2] = positions[i][2];
    col[i * 4] = 1; col[i * 4 + 1] = 1; col[i * 4 + 2] = 1; col[i * 4 + 3] = 0.6;
    siz[i] = 0.4;
  }
  setPointsGeometry(previewPoints, pos, col, siz);
}

function clearPreview() { setPointsGeometry(previewPoints, new Float32Array(0), new Float32Array(0), new Float32Array(0)); }

/* =========================================================================
 * 编辑：写入关键帧
 * ======================================================================= */

function applyBaseValue(p, prop, value) {
  if (prop === 'pos') p.pos = value.slice(0, 3);
  else if (prop === 'col') p.color = value.slice(0, 4);
  else p.scale = value[0];
}

function setValueAtTime(ids, prop, values) {
  const t = Math.round(state.time);
  for (const id of ids) {
    const p = getParticle(id);
    if (!p) continue;
    let tr = findTrack(prop, id);
    if (!tr) {
      tr = { pr: prop, ids: [id], kf: [[0, baseValue(p, prop).slice(), state.defaultEasing]] };
      state.tracks.push(tr);
    }
    const idx = tr.kf.findIndex(k => k[0] === t);
    if (idx >= 0) tr.kf[idx][1] = values.slice();
    else { tr.kf.push([t, values.slice(), state.defaultEasing]); tr.kf.sort((a, b) => a[0] - b[0]); }
    if (t === 0) applyBaseValue(p, prop, values);
  }
  rebuildPoints();
  refreshParticleTree();
}

function setComponentValue(particleId, comp, time, value) {
  const p = getParticle(particleId);
  if (!p) return;
  const prop = comp.track;
  let tr = findTrack(prop, particleId);
  if (!tr) {
    tr = { pr: prop, ids: [particleId], kf: [[0, baseValue(p, prop).slice(), state.defaultEasing]] };
    state.tracks.push(tr);
  }
  let kf = tr.kf.find(k => k[0] === time);
  if (!kf) {
    const cur = particleValueAt(p, prop, time);
    kf = [time, cur.slice(), state.defaultEasing];
    tr.kf.push(kf);
    tr.kf.sort((a, b) => a[0] - b[0]);
  }
  kf[1][comp.index] = value;
  if (time === 0) applyBaseValue(p, prop, kf[1]);
  rebuildPoints();
  refreshParticleTree();
}

function updateKeyframeTime(particleId, prop, oldT, newT) {
  const tr = findTrack(prop, particleId);
  const kf = tr && tr.kf.find(k => k[0] === oldT);
  if (!kf) return;
  kf[0] = Math.max(0, newT);
  tr.kf.sort((a, b) => a[0] - b[0]);
  rebuildPoints();
  refreshParticleTree();
}

function updateKeyframeEasing(particleId, prop, t, easingIdx) {
  const tr = findTrack(prop, particleId);
  const kf = tr && tr.kf.find(k => k[0] === t);
  if (!kf) return;
  kf[2] = easingIdx;
  rebuildPoints();
}

function removeKeyframe(particleId, prop, t) {
  const tr = findTrack(prop, particleId);
  if (!tr) return;
  tr.kf = tr.kf.filter(k => k[0] !== t);
  if (tr.kf.length === 0) state.tracks = state.tracks.filter(x => x !== tr);
  rebuildPoints();
  refreshParticleTree();
}

function addParticle(base) {
  const p = Object.assign({ id: nextId(), style: 'DUST', color: [1, 1, 1, 1], scale: 1, glow: false, lightLevel: 0, pos: [0, 0, 0] }, base);
  state.particles.push(p);
  return p;
}

/* =========================================================================
 * 吸附
 * ======================================================================= */

function snapPos(p) {
  if (!state.snap) return p;
  return p.map(v => Math.round(v / SNAP_STEP) * SNAP_STEP);
}

/* =========================================================================
 * 变换方向轴
 * ======================================================================= */

function selectionCentroid() {
  const sel = state.particles.filter(p => state.selected.has(p.id));
  if (sel.length === 0) return null;
  const c = [0, 0, 0];
  for (const p of sel) { const v = currentVisual(p).pos; c[0] += v[0]; c[1] += v[1]; c[2] += v[2]; }
  return [c[0] / sel.length, c[1] / sel.length, c[2] / sel.length];
}

function updateGizmo() {
  const c = selectionCentroid();
  if (!c) { gizmoGroup.visible = false; return; }
  gizmoGroup.visible = true;
  gizmoGroup.position.set(c[0], c[1], c[2]);
}

/* =========================================================================
 * 坐标工具
 * ======================================================================= */

function screenToNdc(clientX, clientY) {
  const rect = renderer.domElement.getBoundingClientRect();
  pointer.x = ((clientX - rect.left) / rect.width) * 2 - 1;
  pointer.y = -((clientY - rect.top) / rect.height) * 2 + 1;
}

function planeInfo() {
  const def = PLANES[state.drawPlane] || PLANES.XZ;
  const off = parseFloat(document.getElementById('height-y').value) || 0;
  return { def, plane: new THREE.Plane(def.normal, -off), off };
}

function planePointAt(clientX, clientY) {
  screenToNdc(clientX, clientY);
  raycaster.setFromCamera(pointer, camera);
  const hit = new THREE.Vector3();
  return raycaster.ray.intersectPlane(planeInfo().plane, hit) ? hit : null;
}

function pickParticleAt(clientX, clientY) {
  screenToNdc(clientX, clientY);
  raycaster.setFromCamera(pointer, camera);
  raycaster.params.Points.threshold = 0.5;
  const hits = raycaster.intersectObject(points);
  return hits.length ? hits[0].index : -1;
}

function particleAt(index) { return state.particles[index] || null; }

function projectToScreen(x, y, z) {
  const v = new THREE.Vector3(x, y, z).project(camera);
  const rect = renderer.domElement.getBoundingClientRect();
  return { x: (v.x + 1) / 2 * rect.width, y: (1 - v.y) / 2 * rect.height };
}

function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  let t = lenSq === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / lenSq;
  t = Math.max(0, Math.min(1, t));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

function worldToUV(p) {
  if (state.drawPlane === 'XZ') return [p.x, p.z];
  if (state.drawPlane === 'XY') return [p.x, p.y];
  return [p.y, p.z];
}

const shapeCount = () => Math.max(2, parseInt(document.getElementById('shape-count').value) || 30);

function computeShapePositions(mode, u0, v0, u1, v1, off) {
  const toWorld = PLANES[state.drawPlane].toWorld;
  const out = [];
  const push = (u, v) => { const [x, y, z] = toWorld(u, v, off); out.push(snapPos([x, y, z])); };
  if (mode === 'line') {
    const n = shapeCount();
    for (let i = 0; i < n; i++) { const t = n === 1 ? 0.5 : i / (n - 1); push(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t); }
  } else if (mode === 'circle') {
    const r = Math.hypot(u1 - u0, v1 - v0);
    const n = shapeCount();
    for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2; push(u0 + Math.cos(a) * r, v0 + Math.sin(a) * r); }
  } else if (mode === 'rect') {
    const n = Math.max(2, Math.round(Math.sqrt(shapeCount())));
    const uMin = Math.min(u0, u1), uMax = Math.max(u0, u1), vMin = Math.min(v0, v1), vMax = Math.max(v0, v1);
    for (let i = 0; i <= n; i++) for (let j = 0; j <= n; j++) push(uMin + (uMax - uMin) * i / n, vMin + (vMax - vMin) * j / n);
  }
  return out;
}

/* =========================================================================
 * 交互：Blender 式操作
 * ======================================================================= */

let drag = null;
let modal = null;
let boxSel = null;
const lastMouse = { x: 0, y: 0 };

function currentSelected() { return state.particles.filter(p => state.selected.has(p.id)); }

function enterGrab(clientX, clientY) {
  if (state.selected.size === 0) return;
  const origins = new Map();
  for (const p of currentSelected()) origins.set(p.id, currentVisual(p).pos.slice());
  const c = selectionCentroid();
  const pt = planePointAt(clientX, clientY);
  modal = { type: 'grab', origins, axis: null, startWorld: pt ? { x: pt.x, z: pt.z } : null, startClient: { x: clientX, y: clientY }, y: c ? c[1] : 0 };
  controls.enabled = false;
}

function enterScale(clientX) {
  if (state.selected.size === 0) return;
  const origins = new Map();
  for (const p of currentSelected()) origins.set(p.id, currentVisual(p).scale);
  modal = { type: 'scale', origins, startClient: { x: clientX } };
  controls.enabled = false;
}

function cancelModal() {
  if (!modal) return;
  if (modal.type === 'grab') for (const [id, orig] of modal.origins) setValueAtTime([id], 'pos', orig);
  else if (modal.type === 'scale') for (const [id, orig] of modal.origins) setValueAtTime([id], 'scl', [orig]);
  modal = null;
  controls.enabled = true;
  rebuildPoints();
}

function confirmModal() { modal = null; controls.enabled = true; }

function updateGrab(clientX, clientY) {
  const m = modal;
  if (!m || m.type !== 'grab') return;
  if (!m.startWorld) {
    const pt = planePointAt(clientX, clientY);
    if (!pt) return;
    m.startWorld = { x: pt.x, z: pt.z };
    m.startClient = { x: clientX, y: clientY };
    return;
  }
  const pt = planePointAt(clientX, clientY);
  if (!pt) return;
  let dx = pt.x - m.startWorld.x, dy = 0, dz = pt.z - m.startWorld.z;
  if (m.axis === 'X') dz = 0;
  else if (m.axis === 'Z') dx = 0;
  else if (m.axis === 'Y') { dx = 0; dz = 0; dy = -(clientY - m.startClient.y) * 0.02; }
  for (const [id, orig] of m.origins) {
    const p = snapPos([orig[0] + dx, orig[1] + dy, orig[2] + dz]);
    setValueAtTime([id], 'pos', p);
  }
}

function updateScale(clientX) {
  const m = modal;
  if (!m || m.type !== 'scale') return;
  const factor = Math.max(0.02, 1 + (clientX - m.startClient.x) * 0.01);
  for (const [id, orig] of m.origins) setValueAtTime([id], 'scl', [orig * factor]);
}

function deleteSelected() {
  for (const id of state.selected) {
    const idx = state.particles.findIndex(p => p.id === id);
    if (idx >= 0) state.particles.splice(idx, 1);
    state.tracks = state.tracks.filter(tr => !tr.ids.includes(id));
  }
  for (const g in state.groups) {
    state.groups[g] = state.groups[g].filter(id => state.particles.some(p => p.id === id));
    if (state.groups[g].length === 0) delete state.groups[g];
  }
  state.selected.clear();
  state.selectedGroup = null;
  refreshGroupList();
  rebuildPoints();
  refreshParticleTree();
}

function selectAll() {
  if (state.selected.size === state.particles.length && state.particles.length > 0) state.selected.clear();
  else state.selected = new Set(state.particles.map(p => p.id));
  state.selectedGroup = null;
  rebuildPoints();
}

function hitGizmoAxis(clientX, clientY) {
  const c = selectionCentroid();
  if (!c) return null;
  const rect = renderer.domElement.getBoundingClientRect();
  const px = clientX - rect.left, py = clientY - rect.top;
  for (const [axis, v] of Object.entries({ X: [1, 0, 0], Y: [0, 1, 0], Z: [0, 0, 1] })) {
    const s = projectToScreen(c[0], c[1], c[2]);
    const e = projectToScreen(c[0] + v[0] * 1.2, c[1] + v[1] * 1.2, c[2] + v[2] * 1.2);
    if (distToSegment(px, py, s.x, s.y, e.x, e.y) < 10) return axis;
  }
  return null;
}

renderer.domElement.addEventListener('pointerdown', (ev) => {
  lastMouse.x = ev.clientX; lastMouse.y = ev.clientY;
  if (ev.button === 1 || ev.button === 2) return;
  if (ev.button !== 0) return;
  if (modal) { confirmModal(); return; }

  if (state.tool === 'select') {
    const axis = hitGizmoAxis(ev.clientX, ev.clientY);
    if (axis) {
      const origins = new Map();
      for (const p of currentSelected()) origins.set(p.id, currentVisual(p).pos.slice());
      const c = selectionCentroid();
      const pt = planePointAt(ev.clientX, ev.clientY);
      modal = { type: 'grab', origins, axis, startWorld: pt ? { x: pt.x, z: pt.z } : null, startClient: { x: ev.clientX, y: ev.clientY }, y: c ? c[1] : 0 };
      controls.enabled = false;
      return;
    }
    const idx = pickParticleAt(ev.clientX, ev.clientY);
    if (idx >= 0) {
      const p = particleAt(idx);
      if (p) {
        if (ev.shiftKey) { state.selected.has(p.id) ? state.selected.delete(p.id) : state.selected.add(p.id); }
        else if (!state.selected.has(p.id)) { state.selected.clear(); state.selected.add(p.id); }
        state.selectedGroup = null;
        rebuildPoints();
        enterGrab(ev.clientX, ev.clientY);
        return;
      }
    }
    boxSel = { x0: ev.clientX, y0: ev.clientY, x1: ev.clientX, y1: ev.clientY, shift: ev.shiftKey };
    document.getElementById('box-overlay').style.display = 'block';
    updateBoxOverlay();
    return;
  }

  if (state.tool === 'pencil') {
    const pt = planePointAt(ev.clientX, ev.clientY);
    if (pt) {
      const [u, v] = worldToUV(pt);
      const [x, y, z] = PLANES[state.drawPlane].toWorld(u, v, planeInfo().off);
      addParticle({ pos: snapPos([x, y, z]) });
      rebuildPoints(); refreshParticleTree();
    }
    return;
  }
  if (state.tool === 'erase') {
    const idx = pickParticleAt(ev.clientX, ev.clientY);
    const p = particleAt(idx);
    if (p) {
      state.particles.splice(idx, 1);
      state.selected.delete(p.id);
      state.tracks = state.tracks.filter(tr => !tr.ids.includes(p.id));
      rebuildPoints(); refreshParticleTree();
    }
    return;
  }
  if (['line', 'circle', 'rect', 'freehand'].includes(state.tool)) {
    const pt = planePointAt(ev.clientX, ev.clientY);
    if (!pt) return;
    const [u, v] = worldToUV(pt);
    controls.enabled = false;
    drag = { mode: state.tool, start: { u, v }, off: planeInfo().off, last: { u, v } };
    if (state.tool === 'freehand') {
      const [x, y, z] = PLANES[state.drawPlane].toWorld(u, v, drag.off);
      addParticle({ pos: snapPos([x, y, z]) });
      rebuildPoints(); refreshParticleTree();
    }
    return;
  }
});

renderer.domElement.addEventListener('pointermove', (ev) => {
  lastMouse.x = ev.clientX; lastMouse.y = ev.clientY;

  if (modal) {
    if (modal.type === 'grab') updateGrab(ev.clientX, ev.clientY);
    else if (modal.type === 'scale') updateScale(ev.clientX);
    return;
  }
  if (boxSel) { boxSel.x1 = ev.clientX; boxSel.y1 = ev.clientY; updateBoxOverlay(); return; }
  if (!drag) return;

  if (drag.mode === 'freehand') {
    const pt = planePointAt(ev.clientX, ev.clientY);
    if (!pt) return;
    const [u, v] = worldToUV(pt);
    const d = Math.hypot(u - drag.last.u, v - drag.last.v);
    if (d >= 0.25) {
      const [x, y, z] = PLANES[state.drawPlane].toWorld(u, v, drag.off);
      addParticle({ pos: snapPos([x, y, z]) });
      drag.last = { u, v };
      rebuildPoints(); refreshParticleTree();
    }
    return;
  }

  // 形状预览
  const pt = planePointAt(ev.clientX, ev.clientY);
  if (pt) {
    const [u, v] = worldToUV(pt);
    setPreview(computeShapePositions(drag.mode, drag.start.u, drag.start.v, u, v, drag.off));
  }
});

renderer.domElement.addEventListener('pointerup', (ev) => {
  if (ev.button !== 0) return;
  if (modal) { confirmModal(); return; }
  if (boxSel) {
    boxSel.x1 = ev.clientX; boxSel.y1 = ev.clientY;
    applyBoxSelection();
    boxSel = null;
    document.getElementById('box-overlay').style.display = 'none';
    rebuildPoints();
    return;
  }
  if (!drag) return;
  if (['line', 'circle', 'rect'].includes(drag.mode)) {
    const pt = planePointAt(ev.clientX, ev.clientY);
    if (pt) {
      const [u, v] = worldToUV(pt);
      const positions = computeShapePositions(drag.mode, drag.start.u, drag.start.v, u, v, drag.off);
      for (const pos of positions) addParticle({ pos });
    }
  }
  drag = null;
  controls.enabled = true;
  clearPreview();
  rebuildPoints();
  refreshParticleTree();
});

renderer.domElement.addEventListener('contextmenu', (ev) => { ev.preventDefault(); if (modal) cancelModal(); });

window.addEventListener('keydown', (ev) => {
  if (ev.target && ev.target.matches && ev.target.matches('input,select,textarea')) return;
  const k = ev.key.toLowerCase();
  if (modal) {
    if (k === 'escape') cancelModal();
    else if (k === 'enter') confirmModal();
    else if (modal.type === 'grab' && (k === 'x' || k === 'y' || k === 'z')) modal.axis = modal.axis === k.toUpperCase() ? null : k.toUpperCase();
    return;
  }
  if (ev.ctrlKey && k === 'g') { ev.preventDefault(); createGroup(); }
  else if (k === 'g') enterGrab(lastMouse.x, lastMouse.y);
  else if (k === 's') enterScale(lastMouse.x);
  else if (k === 'a') { ev.preventDefault(); selectAll(); }
  else if (k === 'x' || k === 'delete' || k === 'backspace') deleteSelected();
  else if (k === 'escape') { state.selected.clear(); state.selectedGroup = null; rebuildPoints(); }
});

function updateBoxOverlay() {
  const ov = document.getElementById('box-overlay');
  const rect = renderer.domElement.getBoundingClientRect();
  const x = Math.min(boxSel.x0, boxSel.x1) - rect.left;
  const y = Math.min(boxSel.y0, boxSel.y1) - rect.top;
  ov.style.left = x + 'px'; ov.style.top = y + 'px';
  ov.style.width = Math.abs(boxSel.x1 - boxSel.x0) + 'px';
  ov.style.height = Math.abs(boxSel.y1 - boxSel.y0) + 'px';
}

function applyBoxSelection() {
  const rect = renderer.domElement.getBoundingClientRect();
  const x0 = Math.min(boxSel.x0, boxSel.x1) - rect.left, y0 = Math.min(boxSel.y0, boxSel.y1) - rect.top;
  const x1 = Math.max(boxSel.x0, boxSel.x1) - rect.left, y1 = Math.max(boxSel.y0, boxSel.y1) - rect.top;
  const sel = new Set();
  for (const p of state.particles) {
    const v = currentVisual(p).pos;
    const s = projectToScreen(v[0], v[1], v[2]);
    if (s.x >= x0 && s.x <= x1 && s.y >= y0 && s.y <= y1) sel.add(p.id);
  }
  if (boxSel.shift) for (const id of sel) state.selected.add(id);
  else state.selected = sel;
  state.selectedGroup = null;
}

/* =========================================================================
 * 右上角世界方向轴
 * ======================================================================= */

const gizmoCanvas = document.getElementById('axis-gizmo');
const gizmoCtx = gizmoCanvas.getContext('2d');
gizmoCanvas.width = 96; gizmoCanvas.height = 96;

function drawAxisGizmo() {
  const c = gizmoCtx;
  c.clearRect(0, 0, 96, 96);
  const cx = 48, cy = 48;
  const inv = new THREE.Quaternion().copy(camera.quaternion).invert();
  const axes = [
    { dir: new THREE.Vector3(1, 0, 0), color: '#ff5555', label: 'X' },
    { dir: new THREE.Vector3(0, 1, 0), color: '#55ff55', label: 'Y' },
    { dir: new THREE.Vector3(0, 0, 1), color: '#5588ff', label: 'Z' },
  ];
  for (const ax of axes) {
    const v = ax.dir.clone().applyQuaternion(inv);
    const front = v.z < 0;
    const sx = cx + v.x * 34, sy = cy - v.y * 34;
    c.strokeStyle = ax.color; c.globalAlpha = front ? 1 : 0.35; c.lineWidth = 2.5;
    c.beginPath(); c.moveTo(cx, cy); c.lineTo(sx, sy); c.stroke();
    c.fillStyle = ax.color; c.font = 'bold 11px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
    c.fillText(ax.label, sx + (sx - cx) * 0.15, sy + (sy - cy) * 0.15);
  }
  c.globalAlpha = 1;
}

gizmoCanvas.addEventListener('pointerdown', (ev) => {
  const rect = gizmoCanvas.getBoundingClientRect();
  const px = ev.clientX - rect.left, py = ev.clientY - rect.top;
  const inv = new THREE.Quaternion().copy(camera.quaternion).invert();
  let best = null, bestD = 16;
  for (const ax of [{ dir: new THREE.Vector3(1, 0, 0) }, { dir: new THREE.Vector3(0, 1, 0) }, { dir: new THREE.Vector3(0, 0, 1) }]) {
    const v = ax.dir.clone().applyQuaternion(inv);
    const d = Math.hypot(px - (48 + v.x * 34), py - (48 - v.y * 34));
    if (d < bestD) { bestD = d; best = ax; }
  }
  if (best) orientToAxis(best.dir);
});

function orientToAxis(dir) {
  const dist = camera.position.distanceTo(controls.target);
  const d = dir.clone().normalize();
  const endPos = controls.target.clone().sub(d.clone().multiplyScalar(dist));
  const endUp = Math.abs(dir.y) > 0.9 ? new THREE.Vector3(0, 0, 1) : new THREE.Vector3(0, 1, 0);
  camTransition = { startPos: camera.position.clone(), startUp: camera.up.clone(), endPos, endUp, target: controls.target.clone(), t0: performance.now(), dur: 320 };
  if (Math.abs(dir.x) > 0.5) state.drawPlane = 'YZ';
  else if (Math.abs(dir.y) > 0.5) state.drawPlane = 'XZ';
  else state.drawPlane = 'XY';
  document.getElementById('draw-plane').value = state.drawPlane;
}

/* =========================================================================
 * 组
 * ======================================================================= */

function createGroup() {
  if (state.selected.size < 1) { alert('请先选中粒子'); return; }
  const name = nextGroupName();
  state.groups[name] = [...state.selected];
  state.selectedGroup = name;
  refreshGroupList();
}

function refreshGroupList() {
  const box = document.getElementById('group-list');
  box.innerHTML = '';
  for (const [name, members] of Object.entries(state.groups)) {
    const row = document.createElement('div');
    row.className = 'group-row' + (state.selectedGroup === name ? ' active' : '');
    row.innerHTML = `<span>${name}</span><span class="count">${members.length}</span>`;
    row.onclick = () => {
      state.selected = new Set(members.filter(id => state.particles.some(p => p.id === id)));
      state.selectedGroup = name;
      rebuildPoints();
    };
    const del = document.createElement('button');
    del.className = 'del-x'; del.textContent = '×';
    del.onclick = (e) => { e.stopPropagation(); delete state.groups[name]; if (state.selectedGroup === name) state.selectedGroup = null; refreshGroupList(); };
    row.appendChild(del);
    box.appendChild(row);
  }
}

/* =========================================================================
 * 粒子列表（树状时间轴）
 * ======================================================================= */

function refreshParticleTree() {
  const box = document.getElementById('particle-tree');
  if (!box) return;
  box.innerHTML = '';
  if (state.particles.length === 0) {
    box.innerHTML = '<div class="hint" style="padding:8px">暂无粒子，请使用右侧工具绘制</div>';
    return;
  }
  for (const p of state.particles) box.appendChild(renderParticleNode(p));
}

function refreshTreeSelection() {
  document.querySelectorAll('.ptree-head').forEach(head => {
    head.classList.toggle('selected', state.selected.has(head.dataset.pid));
  });
}

function renderParticleNode(p) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const expanded = state.expandedParticles.has(p.id);
  const head = document.createElement('div');
  head.className = 'ptree-head' + (state.selected.has(p.id) ? ' selected' : '');
  head.dataset.pid = p.id;
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete(p.id); else state.expandedParticles.add(p.id);
    refreshParticleTree();
  };
  const pid = document.createElement('span');
  pid.className = 'pid'; pid.textContent = p.id;
  const style = document.createElement('span');
  style.className = 'pstyle'; style.textContent = p.style;
  head.appendChild(arrow); head.appendChild(pid); head.appendChild(style);
  head.onclick = () => {
    if (!evShift(head)) { state.selected.clear(); }
    state.selected.add(p.id);
    state.selectedGroup = null;
    rebuildPoints();
  };
  root.appendChild(head);

  if (expanded) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const comp of PROPERTY_DEFS) props.appendChild(renderPropNode(p, comp));
    root.appendChild(props);
  }
  return root;
}

let evShift = () => false; // 占位，稍后在 initUI 中通过 window 事件设置

function renderPropNode(p, comp) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = p.id + '|' + comp.key;
  const expanded = state.expandedProps.has(key);
  const tr = findTrack(comp.track, p.id);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  head.innerHTML = `<span class="arrow">${expanded ? '▾' : '▸'}</span><span class="plabel">${comp.label}</span><span class="pval">${tr ? tr.kf.length + ' 节点' : '—'}</span>`;
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) for (const kf of tr.kf) kfs.appendChild(renderKfRow(p, comp, kf));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => setComponentValue(p.id, comp, Math.round(state.time), componentValueAt(p, comp, state.time));
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function componentValueAt(p, comp, T) {
  return particleValueAt(p, comp.track, T)[comp.index];
}

function renderKfRow(p, comp, kf) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.onchange = () => updateKeyframeTime(p.id, comp.track, kf[0], parseInt(tIn.value) || 0);
  const vIn = document.createElement('input');
  vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][comp.index]);
  vIn.onchange = () => setComponentValue(p.id, comp, kf[0], parseFloat(vIn.value) || 0);
  const easeSel = document.createElement('select');
  for (let i = 0; i < EASINGS.length; i++) { const o = document.createElement('option'); o.value = i; o.textContent = EASINGS[i][0]; easeSel.appendChild(o); }
  easeSel.value = Array.isArray(kf[2]) ? DEFAULT_EASING : kf[2];
  easeSel.onchange = () => updateKeyframeEasing(p.id, comp.track, kf[0], parseInt(easeSel.value));
  const del = document.createElement('button');
  del.className = 'del-x'; del.textContent = '×';
  del.onclick = () => removeKeyframe(p.id, comp.track, kf[0]);
  row.appendChild(tIn); row.appendChild(vIn); row.appendChild(easeSel); row.appendChild(del);
  return row;
}

/* =========================================================================
 * 函数 / 傅里叶 生成
 * ======================================================================= */

function collectVariables() {
  const vars = {};
  document.querySelectorAll('#var-list .var-row').forEach(row => {
    const name = row.querySelector('.var-name').value.trim();
    const val = parseFloat(row.querySelector('.var-value').value);
    if (name && !isNaN(val)) vars[name] = val;
  });
  return vars;
}

function addVarRow(name, value) {
  const box = document.getElementById('var-list');
  const row = document.createElement('div');
  row.className = 'var-row';
  row.innerHTML = '<input class="var-name" type="text" placeholder="名称" /><input class="var-value" type="text" placeholder="值" />';
  if (name) row.querySelector('.var-name').value = name;
  if (value != null) row.querySelector('.var-value').value = value;
  const del = document.createElement('button');
  del.className = 'del-x'; del.textContent = '×';
  del.onclick = () => row.remove();
  row.appendChild(del);
  box.appendChild(row);
}

function evalOr(expr, env, fallback) { const e = (expr || '').trim(); return e === '' ? fallback : evaluate(e, env); }

function generateFunction() {
  const vars = collectVariables();
  const count = Math.max(1, parseInt(document.getElementById('fn-count').value) || 30);
  const duration = Math.max(0, parseInt(document.getElementById('fn-duration').value) || 100);
  const step = Math.max(1, parseInt(document.getElementById('fn-step').value) || 5);
  const fx = document.getElementById('fn-x').value.trim(), fy = document.getElementById('fn-y').value.trim(), fz = document.getElementById('fn-z').value.trim();
  const fr = document.getElementById('fn-r').value.trim(), fg = document.getElementById('fn-g').value.trim(), fb = document.getElementById('fn-b').value.trim(), fa = document.getElementById('fn-a').value.trim();
  const fs = document.getElementById('fn-s').value.trim(), fglow = document.getElementById('fn-glow').value.trim(), flight = document.getElementById('fn-light').value.trim();
  const animPos = fx && fy && fz, animCol = fr && fg && fb, animScl = fs !== '';
  try {
    for (let i = 0; i < count; i++) {
      const env0 = { t: 0, i, n: count, ...vars };
      const pos = [evalOr(fx, env0, 0), evalOr(fy, env0, 0), evalOr(fz, env0, 0)];
      const color = [evalOr(fr, env0, 1), evalOr(fg, env0, 1), evalOr(fb, env0, 1), evalOr(fa, env0, 1)];
      const scale = evalOr(fs, env0, 1);
      const glow = evalOr(fglow, env0, 0) > 0.5;
      const light = Math.round(Math.min(15, Math.max(0, evalOr(flight, env0, 0))));
      const p = addParticle({ pos, color, scale, glow, lightLevel: light });
      if (duration > 0) {
        const posKf = [], colKf = [], sclKf = [];
        for (let t = step; t <= duration; t += step) {
          const env = { t, i, n: count, ...vars };
          if (animPos) posKf.push([t, [evaluate(fx, env), evaluate(fy, env), evaluate(fz, env)], state.defaultEasing]);
          if (animCol) colKf.push([t, [evaluate(fr, env), evaluate(fg, env), evaluate(fb, env), evalOr(fa, env, 1)], state.defaultEasing]);
          if (animScl) sclKf.push([t, [evaluate(fs, env)], state.defaultEasing]);
        }
        if (posKf.length) state.tracks.push({ pr: 'pos', ids: [p.id], kf: [[0, pos.slice(), state.defaultEasing], ...posKf] });
        if (colKf.length) state.tracks.push({ pr: 'col', ids: [p.id], kf: [[0, color.slice(), state.defaultEasing], ...colKf] });
        if (sclKf.length) state.tracks.push({ pr: 'scl', ids: [p.id], kf: [[0, [scale], state.defaultEasing], ...sclKf] });
      }
    }
  } catch (e) { alert('表达式错误：' + e.message); return; }
  rebuildPoints();
  refreshParticleTree();
}

function renderFourierInputs() {
  const box = document.getElementById('four-coeffs');
  box.innerHTML = '';
  const plane = PLANES[document.getElementById('four-plane').value] || PLANES.XZ;
  for (const axis of plane.axes) {
    const blk = document.createElement('div');
    blk.className = 'axis-block';
    blk.innerHTML = `<div class="ax-title">${axis} 轴</div>`;
    const a0wrap = document.createElement('label');
    a0wrap.className = 'row'; a0wrap.innerHTML = 'a0';
    const a0in = document.createElement('input');
    a0in.type = 'number'; a0in.step = '0.1'; a0in.value = axis === plane.axes[0] ? '3' : '0';
    a0in.dataset.a0 = axis;
    a0wrap.appendChild(a0in); blk.appendChild(a0wrap);
    const list = document.createElement('div');
    list.className = 'four-terms'; list.dataset.axis = axis;
    blk.appendChild(list);
    const addBtn = document.createElement('button');
    addBtn.className = 'mini'; addBtn.textContent = '+ 添加项';
    addBtn.onclick = () => addFourierTerm(axis);
    blk.appendChild(addBtn);
    box.appendChild(blk);
  }
  const constAx = plane.constant;
  const blk = document.createElement('div');
  blk.className = 'axis-block';
  blk.innerHTML = `<div class="ax-title">${constAx} 常量</div>`;
  const cwrap = document.createElement('label');
  cwrap.className = 'row';
  const cin = document.createElement('input');
  cin.type = 'number'; cin.step = '0.1'; cin.value = '0'; cin.id = 'four-' + constAx;
  cwrap.appendChild(cin); blk.appendChild(cwrap); box.appendChild(blk);
}

function addFourierTerm(axis, n, a, b) {
  const list = document.querySelector(`.four-terms[data-axis="${axis}"]`);
  if (!list) return;
  const row = document.createElement('div');
  row.className = 'term-row';
  row.innerHTML = '<input class="term-n" type="number" min="1" placeholder="n" /><input class="term-a" type="number" step="0.1" placeholder="a" /><input class="term-b" type="number" step="0.1" placeholder="b" />';
  row.querySelector('.term-n').value = n != null ? n : (list.children.length + 1);
  row.querySelector('.term-a').value = a != null ? a : 0;
  row.querySelector('.term-b').value = b != null ? b : 0;
  const del = document.createElement('button');
  del.className = 'del-x'; del.textContent = '×';
  del.onclick = () => row.remove();
  row.appendChild(del);
  list.appendChild(row);
}

function evalFourier(axis, omega, t) {
  const a0 = parseFloat(document.querySelector(`input[data-a0="${axis}"]`)?.value) || 0;
  let v = a0;
  document.querySelectorAll(`.four-terms[data-axis="${axis}"] .term-row`).forEach(row => {
    const n = parseFloat(row.querySelector('.term-n').value) || 0;
    const a = parseFloat(row.querySelector('.term-a').value) || 0;
    const b = parseFloat(row.querySelector('.term-b').value) || 0;
    v += a * Math.cos(n * omega * t) + b * Math.sin(n * omega * t);
  });
  return v;
}

function generateFourier() {
  const plane = PLANES[document.getElementById('four-plane').value] || PLANES.XZ;
  const omega = parseFloat(document.getElementById('four-omega').value) || 1;
  const count = Math.max(2, parseInt(document.getElementById('four-count').value) || 200);
  const constant = parseFloat(document.getElementById('four-' + plane.constant)?.value) || 0;
  for (let i = 0; i < count; i++) {
    const t = (i / count) * (Math.PI * 2 / omega);
    const val = { X: constant, Y: constant, Z: constant };
    for (const axis of plane.axes) val[axis] = evalFourier(axis, omega, t);
    addParticle({ pos: [val.X, val.Y, val.Z] });
  }
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 属性面板
 * ======================================================================= */

function updatePropPanel() {
  const sel = currentSelected();
  if (sel.length === 0) return;
  const p = sel[0];
  document.getElementById('prop-style').value = p.style;
  document.getElementById('prop-color').value = rgbToHex(p.color[0], p.color[1], p.color[2]);
  document.getElementById('prop-alpha').value = p.color[3];
  document.getElementById('alpha-val').textContent = p.color[3].toFixed(2);
  document.getElementById('prop-scale').value = p.scale;
  document.getElementById('prop-glow').checked = p.glow;
  document.getElementById('prop-light').value = p.lightLevel;
  document.getElementById('light-val').textContent = p.lightLevel;
  const pos = currentVisual(p).pos;
  document.getElementById('prop-posx').value = pos[0].toFixed(2);
  document.getElementById('prop-posy').value = pos[1].toFixed(2);
  document.getElementById('prop-posz').value = pos[2].toFixed(2);
}

function rgbToHex(r, g, b) {
  const c = v => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, '0');
  return '#' + c(r) + c(g) + c(b);
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
}

/* =========================================================================
 * 时间轴（底部：仅播放进度）
 * ======================================================================= */

function drawTimeline() {
  const canvas = document.getElementById('timeline');
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const mx = Math.max(100, maxTick());
  const pxPerTick = (w - 40) / mx;
  ctx.fillStyle = '#1f222a'; ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke();
  const step = niceStep(mx);
  ctx.fillStyle = '#9aa0ad'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top';
  for (let t = 0; t <= mx; t += step) {
    const x = 40 + t * pxPerTick;
    ctx.fillText(t, x + 2, 2);
    ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(x, h / 2 - 6); ctx.lineTo(x, h / 2 + 6); ctx.stroke();
  }
  const phx = 40 + state.time * pxPerTick;
  ctx.strokeStyle = '#ffcc55'; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(phx, 0); ctx.lineTo(phx, h); ctx.stroke();
  ctx.fillStyle = '#ffcc55'; ctx.beginPath(); ctx.moveTo(phx - 5, 0); ctx.lineTo(phx + 5, 0); ctx.lineTo(phx, 8); ctx.closePath(); ctx.fill();
}

function niceStep(mx) {
  const rough = mx / 10;
  const pow = Math.pow(10, Math.floor(Math.log10(rough)));
  const norm = rough / pow;
  return (norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10) * pow;
}

function timelineXToTick(clientX) {
  const canvas = document.getElementById('timeline');
  const rect = canvas.getBoundingClientRect();
  const mx = Math.max(100, maxTick());
  const pxPerTick = (canvas.clientWidth - 40) / mx;
  return Math.max(0, Math.round((clientX - rect.left - 40) / pxPerTick));
}

/* =========================================================================
 * 导出 / 导入 / 文件
 * ======================================================================= */

const r3 = x => Math.round(x * 1000) / 1000;
const roundArr = a => a.map(r3);
function encodeEasing(e) { return Array.isArray(e) ? e.map(r3) : e; }

function exportJSON() {
  const p = state.particles.map(pt => ({ id: pt.id, s: pt.style, c: roundArr(pt.color), sc: r3(pt.scale), g: pt.glow ? 1 : 0, l: pt.lightLevel, pos: roundArr(pt.pos) }));
  const t = state.tracks.map(tr => ({ pr: tr.pr, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], roundArr(k[1]), encodeEasing(k[2])]) }));
  const g = {};
  for (const [name, members] of Object.entries(state.groups)) if (members.length) g[name] = members.slice();
  return { v: 1, loop: state.loop, g, p, t };
}

function importJSON(obj) {
  state.particles = (obj.p || []).map(pt => ({
    id: pt.id || nextId(), style: STYLES.includes(pt.s) ? pt.s : 'DUST',
    color: (pt.c || [1, 1, 1, 1]).slice(0, 4), scale: pt.sc != null ? pt.sc : 1,
    glow: !!pt.g, lightLevel: pt.l || 0, pos: (pt.pos || [0, 0, 0]).slice(0, 3),
  }));
  state.idCounter = state.particles.length;
  state.groups = {};
  for (const [name, members] of Object.entries(obj.g || {})) state.groups[name] = members.slice();
  state.groupCounter = Object.keys(state.groups).length;
  state.tracks = (obj.t || []).map(tr => ({
    pr: ['pos', 'col', 'scl'].includes(tr.pr) ? tr.pr : 'pos',
    ids: (tr.ids || []).slice(),
    kf: (tr.kf || []).map(k => [k[0], k[1].slice(), Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
  }));
  state.loop = !!obj.loop;
  state.selected.clear(); state.selectedGroup = null; state.time = 0;
  state.expandedParticles.clear(); state.expandedProps.clear();
  updateTimeUI(); refreshGroupList(); rebuildPoints(); refreshParticleTree();
}

function download(json, filename) {
  const blob = new Blob([json], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

async function openFile() {
  if (window.showOpenFilePicker) {
    try {
      const [h] = await window.showOpenFilePicker({ types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      const f = await h.getFile();
      importJSON(JSON.parse(await f.text()));
      state.name = f.name.replace(/\.json$/i, '');
      document.getElementById('anim-name').value = state.name;
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  document.getElementById('file-import').click();
}

async function saveFile() {
  const json = JSON.stringify(exportJSON());
  if (state.fileHandle && state.fileHandle.createWritable) {
    try {
      const w = await state.fileHandle.createWritable();
      await w.write(json); await w.close();
      return;
    } catch (e) { /* 回退到下载 */ }
  }
  download(json, state.name + '.json');
}

async function saveFileAs() {
  const json = JSON.stringify(exportJSON());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.json', types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      const w = await h.createWritable();
      await w.write(json); await w.close();
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  const name = prompt('文件名', state.name + '.json');
  if (name) download(json, name);
}

/* =========================================================================
 * UI 初始化与主循环
 * ======================================================================= */

function updateTimeUI() {
  document.getElementById('tl-time').value = Math.round(state.time);
  document.getElementById('tl-max').textContent = maxTick();
}

let shiftHeld = false;
window.addEventListener('keydown', (e) => { if (e.key === 'Shift') shiftHeld = true; });
window.addEventListener('keyup', (e) => { if (e.key === 'Shift') shiftHeld = false; });
evShift = () => shiftHeld;

function initUI() {
  const styleSel = document.getElementById('prop-style');
  STYLES.forEach(s => { const o = document.createElement('option'); o.value = s; o.textContent = s; styleSel.appendChild(o); });
  const easeSel = document.getElementById('tl-easing');
  EASINGS.forEach((e, i) => { const o = document.createElement('option'); o.value = i; o.textContent = e[0]; easeSel.appendChild(o); });
  easeSel.value = DEFAULT_EASING;

  // 菜单
  document.querySelectorAll('.menu-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      const menu = btn.closest('.menu');
      const wasOpen = menu.classList.contains('open');
      document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
      if (!wasOpen) menu.classList.add('open');
    });
  });
  document.addEventListener('click', (ev) => {
    if (!ev.target.closest('.menu')) document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
  });
  const closeMenus = () => document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
  document.getElementById('btn-open').addEventListener('click', () => { closeMenus(); openFile(); });
  document.getElementById('btn-save').addEventListener('click', () => { closeMenus(); saveFile(); });
  document.getElementById('btn-saveas').addEventListener('click', () => { closeMenus(); saveFileAs(); });
  document.getElementById('btn-export').addEventListener('click', () => { closeMenus(); state.name = document.getElementById('anim-name').value.trim() || 'my_animation'; download(JSON.stringify(exportJSON()), state.name + '.json'); });
  document.getElementById('btn-import').addEventListener('click', () => { closeMenus(); document.getElementById('file-import').click(); });
  document.getElementById('btn-clear').addEventListener('click', () => { closeMenus(); clearAll(); });
  document.getElementById('btn-selall').addEventListener('click', () => { closeMenus(); selectAll(); });
  document.getElementById('btn-delete-selected').addEventListener('click', () => { closeMenus(); deleteSelected(); });
  document.getElementById('btn-group').addEventListener('click', () => { closeMenus(); createGroup(); });

  // 工具
  document.getElementById('tools').addEventListener('click', (ev) => {
    const btn = ev.target.closest('.tool');
    if (!btn) return;
    state.tool = btn.dataset.tool;
    document.querySelectorAll('.tool').forEach(b => b.classList.toggle('active', b === btn));
  });
  document.getElementById('draw-plane').addEventListener('change', (ev) => { state.drawPlane = ev.target.value; });
  document.getElementById('snap-toggle').addEventListener('change', (ev) => { state.snap = ev.target.checked; });

  // 函数 / 傅里叶 / 变量
  document.getElementById('btn-var-add').addEventListener('click', () => addVarRow());
  document.getElementById('btn-fn').addEventListener('click', generateFunction);
  document.getElementById('btn-four').addEventListener('click', generateFourier);
  document.getElementById('four-plane').addEventListener('change', renderFourierInputs);

  // 属性
  document.getElementById('prop-style').addEventListener('change', (ev) => { currentSelected().forEach(p => { p.style = ev.target.value; }); rebuildPoints(); });
  document.getElementById('prop-glow').addEventListener('change', (ev) => { currentSelected().forEach(p => { p.glow = ev.target.checked; }); rebuildPoints(); });
  document.getElementById('prop-light').addEventListener('input', (ev) => { document.getElementById('light-val').textContent = ev.target.value; currentSelected().forEach(p => { p.lightLevel = parseInt(ev.target.value); }); rebuildPoints(); });
  document.getElementById('prop-alpha').addEventListener('input', (ev) => { document.getElementById('alpha-val').textContent = parseFloat(ev.target.value).toFixed(2); applyColorFromInputs(); });
  document.getElementById('prop-color').addEventListener('input', applyColorFromInputs);
  document.getElementById('prop-scale').addEventListener('input', (ev) => { setValueAtTime([...state.selected], 'scl', [parseFloat(ev.target.value) || 1]); });
  ['prop-posx', 'prop-posy', 'prop-posz'].forEach(id => document.getElementById(id).addEventListener('input', applyPositionFromInputs));

  // 时间轴
  document.getElementById('btn-play').addEventListener('click', () => {
    state.playing = !state.playing;
    document.getElementById('btn-play').textContent = state.playing ? '⏸ 暂停' : '▶ 播放';
  });
  document.getElementById('tl-time').addEventListener('input', (ev) => { state.time = parseFloat(ev.target.value) || 0; updateTimeUI(); rebuildPoints(); });
  document.getElementById('tl-loop').addEventListener('change', (ev) => { state.loop = ev.target.checked; });
  document.getElementById('loop-toggle').addEventListener('change', (ev) => { state.loop = ev.target.checked; document.getElementById('tl-loop').checked = ev.target.checked; });
  document.getElementById('tl-easing').addEventListener('change', (ev) => { state.defaultEasing = parseInt(ev.target.value); });
  document.getElementById('btn-capture').addEventListener('click', () => {
    const ids = [...state.selected];
    if (ids.length === 0) { alert('请先选择粒子'); return; }
    for (const id of ids) {
      const p = getParticle(id);
      if (!p) continue;
      const v = currentVisual(p);
      setValueAtTime([id], 'pos', v.pos);
      setValueAtTime([id], 'col', v.color);
      setValueAtTime([id], 'scl', [v.scale]);
    }
  });

  // 文件导入
  document.getElementById('file-import').addEventListener('change', (ev) => {
    const f = ev.target.files[0];
    if (!f) return;
    f.text().then(txt => { importJSON(JSON.parse(txt)); state.name = f.name.replace(/\.json$/i, ''); document.getElementById('anim-name').value = state.name; });
    ev.target.value = '';
  });
  document.getElementById('anim-name').addEventListener('input', (ev) => { state.name = ev.target.value.trim() || 'my_animation'; });

  // 时间轴点击/拖动
  const tlCanvas = document.getElementById('timeline');
  let tlDragging = false;
  tlCanvas.addEventListener('pointerdown', (ev) => { tlDragging = true; tlCanvas.setPointerCapture(ev.pointerId); state.time = timelineXToTick(ev.clientX); updateTimeUI(); rebuildPoints(); });
  tlCanvas.addEventListener('pointermove', (ev) => { if (tlDragging) { state.time = timelineXToTick(ev.clientX); updateTimeUI(); rebuildPoints(); } });
  tlCanvas.addEventListener('pointerup', () => { tlDragging = false; });

  renderFourierInputs();
  addVarRow('speed', '0.2');
  rebuildPoints();
  refreshParticleTree();
}

function clearAll() {
  state.particles = []; state.tracks = []; state.groups = {};
  state.selected.clear(); state.selectedGroup = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0; state.idCounter = 0; state.groupCounter = 0;
  refreshGroupList(); updateTimeUI(); rebuildPoints(); refreshParticleTree();
}

function applyColorFromInputs() {
  const rgb = hexToRgb(document.getElementById('prop-color').value);
  const a = parseFloat(document.getElementById('prop-alpha').value);
  setValueAtTime([...state.selected], 'col', [rgb[0], rgb[1], rgb[2], a]);
}

function applyPositionFromInputs() {
  const x = parseFloat(document.getElementById('prop-posx').value);
  const y = parseFloat(document.getElementById('prop-posy').value);
  const z = parseFloat(document.getElementById('prop-posz').value);
  if ([x, y, z].some(isNaN)) return;
  setValueAtTime([...state.selected], 'pos', snapPos([x, y, z]));
}

function resize() {
  const w = viewport.clientWidth, h = viewport.clientHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  pointsMaterial.uniforms.uPixelScale.value = focalLengthPx();
  selectedMaterial.uniforms.uPixelScale.value = focalLengthPx();
  drawTimeline();
}
window.addEventListener('resize', resize);
resize();

let last = performance.now();
function animate(now) {
  requestAnimationFrame(animate);
  const dt = Math.min((now - last) / 1000, 0.1);
  last = now;

  if (camTransition) {
    const t = Math.min(1, (now - camTransition.t0) / camTransition.dur);
    const e = easeInOut(t);
    camera.position.lerpVectors(camTransition.startPos, camTransition.endPos, e);
    camera.up.lerpVectors(camTransition.startUp, camTransition.endUp, e).normalize();
    camera.lookAt(camTransition.target);
    if (t >= 1) camTransition = null;
    controls.update();
  }

  if (state.playing) {
    state.time += dt * 20;
    const mx = maxTick();
    if (state.time >= mx && mx > 0) {
      if (state.loop) state.time = 0;
      else { state.time = mx; state.playing = false; document.getElementById('btn-play').textContent = '▶ 播放'; }
    }
    updateTimeUI();
    rebuildPoints();
  }
  controls.update();
  renderer.render(scene, camera);
  drawAxisGizmo();
}

initUI();
requestAnimationFrame(animate);
