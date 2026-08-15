/* =========================================================================
 * ParticleDrawing 粒子动画编辑器
 * 依赖全局 THREE（vendor/three.min.js）与 THREE.OrbitControls（vendor/OrbitControls.js）
 * ======================================================================= */

const OrbitControls = THREE.OrbitControls;

/* =========================================================================
 * 常量
 * ======================================================================= */

const STYLES = [
  'DOT', 'DUST', 'FLAME', 'SOUL_FIRE', 'NOTE', 'HEART', 'SPARK',
  'GLOW', 'BUBBLE', 'SMOKE',
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

// 粒子可动画轨道（与组一致：位置/颜色/缩放，多分量合并在同一节点内）
const PARTICLE_TRACK_DEFS = [
  { key: 'pos', label: '位置', labels: ['X', 'Y', 'Z'] },
  { key: 'vel', label: '速度', labels: ['X', 'Y', 'Z'] },
  { key: 'col', label: '颜色', labels: ['R', 'G', 'B', 'A'] },
  { key: 'scl', label: '缩放', labels: ['缩放'] },
];

const DEFAULT_EASING = 3;
const SNAP_STEP = 1.0;
const ROT_SNAP = Math.PI / 4; // 按住 Shift 时旋转吸附的步长（45°）
const PARTICLE_SIZE_FACTOR = 0.5; // 编辑器渲染缩放（与游戏内 quad 的可见点大小一致）

// 组的属性（轨道级）：位置/旋转/颜色/缩放，支持「设置(set)」或「操作(op)」两种模式
const GROUP_PROP_DEFS = [
  { key: 'pos', label: '位置', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'rot', label: '旋转', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'col', label: '颜色', size: 4, labels: ['R', 'G', 'B', 'A'] },
  { key: 'scl', label: '缩放', size: 1, labels: ['缩放'] },
];

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
  playSpeed: 1,
  defaultEasing: DEFAULT_EASING,
  dirty: false,
};

function nextId() {
  let n = 0;
  while (state.particles.some(p => p.id === 'p' + n)) n++;
  return 'p' + n;
}
function nextGroupName() {
  let n = 0;
  while (('g' + n) in state.groups) n++;
  return 'g' + n;
}
function getParticle(id) { return state.particles.find(p => p.id === id); }
function findTrack(prop, id) { return state.tracks.find(tr => tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === id); }

function nextFreeTime(tr, startTime) {
  let t = Math.max(0, Math.round(startTime));
  while (tr.kf.some(k => k[0] === t)) t += 5;
  return t;
}

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

// 方形贴图（2D 广告牌，始终朝向摄像头）
function makeSquareTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 16;
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, 16, 16);
  const tex = new THREE.CanvasTexture(c);
  tex.minFilter = THREE.NearestFilter;
  tex.magFilter = THREE.NearestFilter;
  return tex;
}

// 选中描边用方框贴图（中心透明，露出粒子本色，形状与粒子一致）
function makeRingTexture() {
  const c = document.createElement('canvas');
  c.width = c.height = 32;
  const ctx = c.getContext('2d');
  ctx.strokeStyle = '#ffffff';
  ctx.lineWidth = 2;
  ctx.strokeRect(1, 1, 30, 30);
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
  depthWrite: true,
  blending: THREE.NormalBlending,
});

// 选中描边（方形边框，中心透明露出粒子本色）
const selectedMaterial = new THREE.ShaderMaterial({
  uniforms: { uMap: { value: makeRingTexture() }, uPixelScale: { value: focalLengthPx() } },
  vertexShader: `
    uniform float uPixelScale;
    attribute float aSize;
    void main() {
      vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
      gl_PointSize = aSize * uPixelScale / max(0.1, -mvPosition.z) * 1.1;
      gl_Position = projectionMatrix * mvPosition;
    }
  `,
  fragmentShader: `
    uniform sampler2D uMap;
    void main() {
      vec4 tex = texture2D(uMap, gl_PointCoord);
      gl_FragColor = vec4(1.0, 0.6, 0.25, 1.0) * tex.a;
    }
  `,
  transparent: true,
  depthWrite: false,
  blending: THREE.NormalBlending,
});

let points = new THREE.Points(new THREE.BufferGeometry(), pointsMaterial);
let selectedPoints = new THREE.Points(new THREE.BufferGeometry(), selectedMaterial);
let previewPoints = new THREE.Points(new THREE.BufferGeometry(), pointsMaterial);
points.renderOrder = 0;
selectedPoints.renderOrder = 1;
previewPoints.renderOrder = 0;
scene.add(points);
scene.add(selectedPoints);
scene.add(previewPoints);

const gizmoGroup = new THREE.Group();
scene.add(gizmoGroup);
gizmoGroup.visible = false;
const GIZMO_RING_RENDER_ORDER = 10;
const GIZMO_ARROW_RENDER_ORDER = 11;
// 旋转控制器：三个轴向的彩色圆环（环面垂直对应轴，DoubleSide 保证完整圆环）
const gizmoRings = {};
(function buildRotateRings() {
  const defs = { X: [0xff5555, new THREE.Vector3(0, Math.PI / 2, 0)], Y: [0x55ff55, new THREE.Vector3(Math.PI / 2, 0, 0)], Z: [0x5588ff, new THREE.Vector3(0, 0, 0)] };
  for (const [axis, [color, rot]] of Object.entries(defs)) {
    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(0.5, 0.018, 16, 96),
      new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: true, transparent: true, side: THREE.DoubleSide })
    );
    ring.rotation.set(rot.x, rot.y, rot.z);
    ring.name = axis;
    ring.renderOrder = GIZMO_RING_RENDER_ORDER;
    gizmoRings[axis] = ring;
    gizmoGroup.add(ring);
  }
})();
// 移动控制器：柱身 + 锥头
const gizmoArrows = {};
(function buildMoveArrows() {
  const defs = { X: [1, 0, 0, 0xff5555], Y: [0, 1, 0, 0x55ff55], Z: [0, 0, 1, 0x5588ff] };
  const up = new THREE.Vector3(0, 1, 0);
  const shaftLen = 1.28, shaftR = 0.014, headH = 0.22, headR = 0.07;
  for (const [axis, [x, y, z, color]] of Object.entries(defs)) {
    const dir = new THREE.Vector3(x, y, z);
    const group = new THREE.Group();
    group.name = axis;
    const shaft = new THREE.Mesh(new THREE.CylinderGeometry(shaftR, shaftR, shaftLen, 10), new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: true, transparent: true }));
    shaft.position.y = shaftLen / 2;
    const head = new THREE.Mesh(new THREE.ConeGeometry(headR, headH, 14), new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: true, transparent: true }));
    head.position.y = shaftLen + headH / 2;
    group.add(shaft); group.add(head);
    group.quaternion.setFromUnitVectors(up, dir);
    shaft.renderOrder = GIZMO_ARROW_RENDER_ORDER;
    head.renderOrder = GIZMO_ARROW_RENDER_ORDER;
    gizmoArrows[axis] = { group, shaft, head };
    gizmoGroup.add(group);
  }
})();

let camTransition = null;
let planePulse = null; // 绘制平面切换时的脉动动画 { mesh, t0, dur }

/* =========================================================================
 * 动画状态查询
 * ======================================================================= */

function baseValue(p, prop) {
  if (prop === 'pos') return p.pos;
  if (prop === 'col') return p.color;
  if (prop === 'vel') return p.vel;
  return [p.scale];
}

function zeroArray(prop) {
  if (prop === 'pos' || prop === 'rot' || prop === 'vel') return [0, 0, 0];
  if (prop === 'col') return [0, 0, 0, 0];
  return [0];
}

function addArrays(a, b) {
  const out = new Array(a.length);
  for (let i = 0; i < a.length; i++) out[i] = a[i] + b[i];
  return out;
}

function trackValueAt(tr, T, fallback) {
  const kfs = tr.kf;
  if (T < kfs[0][0]) return fallback;
  if (T >= kfs[kfs.length - 1][0]) return kfs[kfs.length - 1][1];
  for (let i = 0; i < kfs.length - 1; i++) {
    const a = kfs[i], b = kfs[i + 1];
    if (T >= a[0] && T <= b[0]) {
      const dur = b[0] - a[0];
      return lerpArray(a[1], b[1], easeVal(dur === 0 ? 1 : (T - a[0]) / dur, a[2]));
    }
  }
  return fallback;
}

function tracksForParticle(prop, pId) {
  for (const tr of state.tracks) if (tr.pr === prop && tr.m !== 'op' && tr.ids.length === 1 && tr.ids[0] === pId) return tr;
  for (const tr of state.tracks) {
    if (tr.pr !== prop || tr.m === 'op') continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const members = state.groups[id.slice(2)];
        if (members && members.includes(pId)) return tr;
      }
    }
  }
  return null;
}

function groupOpDelta(p, prop, T) {
  let delta = null;
  for (const tr of state.tracks) {
    if (tr.pr !== prop || tr.m !== 'op' || tr.kf.length === 0) continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const members = state.groups[id.slice(2)];
        if (members && members.includes(p.id)) {
          const d = trackValueAt(tr, T, zeroArray(prop));
          delta = delta ? addArrays(delta, d) : d.slice();
        }
      }
    }
  }
  return delta;
}

function groupRotationInfo(p, T) {
  for (const tr of state.tracks) {
    if (tr.pr !== 'rot' || tr.kf.length === 0) continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const gname = id.slice(2);
        const members = state.groups[gname];
        if (members && members.includes(p.id)) {
          return { rot: trackValueAt(tr, T, [0, 0, 0]), pivot: groupCentroidValue(gname, 'pos') };
        }
      }
    }
  }
  return null;
}

function applyGroupRotation(p, value, T) {
  const info = groupRotationInfo(p, T);
  if (!info) return value;
  const rot = info.rot;
  if (rot[0] === 0 && rot[1] === 0 && rot[2] === 0) return value;
  const pivot = info.pivot;
  let r = [value[0] - pivot[0], value[1] - pivot[1], value[2] - pivot[2]];
  r = rotateVector(r, [1, 0, 0], rot[0]);
  r = rotateVector(r, [0, 1, 0], rot[1]);
  r = rotateVector(r, [0, 0, 1], rot[2]);
  return [pivot[0] + r[0], pivot[1] + r[1], pivot[2] + r[2]];
}

function particleValueAt(p, prop, T) {
  if (prop === 'pos') {
    const tr = tracksForParticle('pos', p.id);
    let value = baseValue(p, 'pos');
    if (tr && tr.kf.length > 0) value = trackValueAt(tr, T, value);
    value = applyGroupRotation(p, value, T);
    const op = groupOpDelta(p, 'pos', T);
    if (op) value = addArrays(value, op);
    return value;
  }
  const tr = tracksForParticle(prop, p.id);
  const base = baseValue(p, prop);
  let value = base;
  if (tr && tr.kf.length > 0) value = trackValueAt(tr, T, base);
  const op = groupOpDelta(p, prop, T);
  if (op) value = addArrays(value, op);
  return value;
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
    sizes[i] = Math.max(0.02, v.scale * PARTICLE_SIZE_FACTOR);
  }
  setPointsGeometry(points, positions, colors, sizes);

  const sel = state.particles.filter(p => state.selected.has(p.id));
  const spos = new Float32Array(sel.length * 3), ssiz = new Float32Array(sel.length);
  for (let i = 0; i < sel.length; i++) {
    const v = currentVisual(sel[i]);
    spos[i * 3] = v.pos[0]; spos[i * 3 + 1] = v.pos[1]; spos[i * 3 + 2] = v.pos[2];
    ssiz[i] = Math.max(0.02, v.scale * PARTICLE_SIZE_FACTOR);
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
    siz[i] = 1 * PARTICLE_SIZE_FACTOR;
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
  else if (prop === 'vel') p.vel = value.slice(0, 3);
  else p.scale = value[0];
}

function setValueAtTime(ids, prop, values) {
  const t = Math.round(state.time);
  for (const id of ids) {
    const p = getParticle(id);
    if (!p) continue;
    let tr = findTrack(prop, id);
    if (!tr) {
      tr = { pr: prop, m: 'set', ids: [id], kf: [[0, baseValue(p, prop).slice(), state.defaultEasing]] };
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

// 直接修改基础值（不创建关键帧），并同步 t=0 关键帧（若存在）
function editBaseValue(ids, prop, values) {
  for (const id of ids) {
    const p = getParticle(id);
    if (!p) continue;
    applyBaseValue(p, prop, values);
    const tr = findTrack(prop, id);
    if (tr) {
      const kf0 = tr.kf.find(k => k[0] === 0);
      if (kf0) kf0[1] = values.slice();
    }
  }
}

function setComponentValue(particleId, comp, time, value) {
  const p = getParticle(particleId);
  if (!p) return;
  pushUndo();
  const prop = comp.track;
  let tr = findTrack(prop, particleId);
  if (!tr) {
    tr = { pr: prop, m: 'set', ids: [particleId], kf: [[0, baseValue(p, prop).slice(), state.defaultEasing]] };
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
  pushUndo();
  kf[0] = Math.max(0, newT);
  tr.kf.sort((a, b) => a[0] - b[0]);
  rebuildPoints();
  refreshParticleTree();
}

function updateKeyframeEasing(particleId, prop, t, easing) {
  const tr = findTrack(prop, particleId);
  const kf = tr && tr.kf.find(k => k[0] === t);
  if (!kf) return;
  pushUndo();
  kf[2] = easing;
  rebuildPoints();
}

function removeKeyframe(particleId, prop, t) {
  const tr = findTrack(prop, particleId);
  if (!tr) return;
  pushUndo();
  tr.kf = tr.kf.filter(k => k[0] !== t);
  if (tr.kf.length === 0) state.tracks = state.tracks.filter(x => x !== tr);
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 组：操作/设置 关键帧
 * ======================================================================= */

function findGroupTrack(prop, groupName) {
  return state.tracks.find(tr => tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === 'g:' + groupName);
}

function setGroupTrackValue(groupName, prop, mode, time, value) {
  let tr = findGroupTrack(prop, groupName);
  if (!tr) {
    const base = mode === 'op' ? zeroArray(prop).slice() : groupCentroidValue(groupName, prop);
    tr = { pr: prop, m: mode, ids: ['g:' + groupName], kf: [[0, base, state.defaultEasing]] };
    state.tracks.push(tr);
  } else {
    tr.m = mode;
  }
  let kf = tr.kf.find(k => k[0] === time);
  if (!kf) { kf = [time, value.slice(), state.defaultEasing]; tr.kf.push(kf); tr.kf.sort((a, b) => a[0] - b[0]); }
  else kf[1] = value.slice();
  rebuildPoints();
  refreshParticleTree();
}

function setGroupTrackMode(groupName, prop, mode) {
  const tr = findGroupTrack(prop, groupName);
  if (!tr) return;
  pushUndo();
  tr.m = mode;
  rebuildPoints();
  refreshParticleTree();
}

function addParticle(base) {
  const p = Object.assign({ id: nextId(), style: 'DOT', color: [1, 1, 1, 1], scale: 1, glow: false, lightLevel: 0, pos: [0, 0, 0], vel: [0, 0, 0] }, base);
  state.particles.push(p);
  return p;
}

function autoGroup(ids) {
  if (!ids || ids.length === 0) return null;
  const name = nextGroupName();
  state.groups[name] = ids.slice();
  state.expandedParticles.delete('g:' + name); // 新建组默认折叠
  return name;
}

function removeGroupAndTracks(name) {
  const members = state.groups[name] || [];
  for (const id of members) {
    const idx = state.particles.findIndex(p => p.id === id);
    if (idx >= 0) state.particles.splice(idx, 1);
    state.tracks = state.tracks.filter(tr => !tr.ids.includes(id));
  }
  delete state.groups[name];
  state.tracks = state.tracks.filter(tr => !tr.ids.includes('g:' + name));
  if (state.selectedGroup === name) state.selectedGroup = null;
  state.expandedParticles.delete('g:' + name);
  state.expandedProps.delete('g:' + name + '|@members');
  state.expandedProps.delete('g:' + name + '|@props');
}

function renameParticle(oldId, newId) {
  newId = (newId || '').trim();
  if (!newId || newId === oldId || getParticle(newId)) return false;
  pushUndo();
  const p = getParticle(oldId);
  p.id = newId;
  for (const g in state.groups) {
    const m = state.groups[g];
    const idx = m.indexOf(oldId);
    if (idx >= 0) m[idx] = newId;
  }
  for (const tr of state.tracks) {
    const idx = tr.ids.indexOf(oldId);
    if (idx >= 0) tr.ids[idx] = newId;
  }
  if (state.selected.has(oldId)) { state.selected.delete(oldId); state.selected.add(newId); }
  if (state.expandedParticles.has(oldId)) { state.expandedParticles.delete(oldId); state.expandedParticles.add(newId); }
  rebuildPoints();
  refreshParticleTree();
  return true;
}

function renameGroup(oldName, newName) {
  newName = (newName || '').trim();
  if (!newName || newName === oldName || newName in state.groups) return false;
  pushUndo();
  state.groups[newName] = state.groups[oldName];
  delete state.groups[oldName];
  for (const tr of state.tracks) {
    const idx = tr.ids.indexOf('g:' + oldName);
    if (idx >= 0) tr.ids[idx] = 'g:' + newName;
  }
  if (state.selectedGroup === oldName) state.selectedGroup = newName;
  if (state.expandedParticles.has('g:' + oldName)) { state.expandedParticles.delete('g:' + oldName); state.expandedParticles.add('g:' + newName); }
  refreshParticleTree();
  return true;
}

function moveParticlesToGroup(ids, groupName) {
  if (ids.length === 0) return;
  pushUndo();
  const idSet = new Set(ids);
  for (const g in state.groups) {
    if (g === groupName) continue;
    state.groups[g] = state.groups[g].filter(id => !idSet.has(id));
    if (state.groups[g].length === 0) delete state.groups[g];
  }
  const set = new Set(state.groups[groupName] || []);
  for (const id of ids) set.add(id);
  state.groups[groupName] = [...set];
  rebuildPoints();
  refreshParticleTree();
}

function removeParticlesFromGroups(ids) {
  if (ids.length === 0) return;
  pushUndo();
  for (const g in state.groups) {
    state.groups[g] = state.groups[g].filter(id => !ids.includes(id));
    if (state.groups[g].length === 0) delete state.groups[g];
  }
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 撤回 / 重做
 * ======================================================================= */

const undoStack = [];
const redoStack = [];

function snapshot() {
  return {
    particles: state.particles.map(p => ({ ...p, color: p.color.slice(), pos: p.pos.slice(), vel: p.vel ? p.vel.slice() : [0, 0, 0] })),
    tracks: state.tracks.map(tr => ({ pr: tr.pr, m: tr.m, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]) })),
    groups: JSON.parse(JSON.stringify(state.groups)),
    name: state.name,
    loop: state.loop,
    selected: [...state.selected],
    selectedGroup: state.selectedGroup,
  };
}

function restore(s) {
  state.particles = s.particles.map(p => ({ ...p, color: p.color.slice(), pos: p.pos.slice(), vel: p.vel ? p.vel.slice() : [0, 0, 0] }));
  state.tracks = s.tracks.map(tr => ({ pr: tr.pr, m: tr.m, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]) }));
  state.groups = JSON.parse(JSON.stringify(s.groups));
  state.name = s.name;
  state.loop = s.loop;
  state.selected = new Set(s.selected);
  state.selectedGroup = s.selectedGroup;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  rebuildPoints();
  refreshParticleTree();
}

function pushUndo() {
  undoStack.push(snapshot());
  if (undoStack.length > 100) undoStack.shift();
  redoStack.length = 0;
  state.dirty = true;
}

function popUndo() { undoStack.pop(); }

let continuousDirty = false;
function beginContinuous() { if (!continuousDirty) { pushUndo(); continuousDirty = true; } }
function endContinuous() { continuousDirty = false; }

function undo() {
  if (undoStack.length === 0) return;
  redoStack.push(snapshot());
  restore(undoStack.pop());
  state.dirty = true;
}

function redo() {
  if (redoStack.length === 0) return;
  undoStack.push(snapshot());
  restore(redoStack.pop());
  state.dirty = true;
}

/* =========================================================================
 * 吸附
 * ======================================================================= */

function snapValue(v) {
  if (!state.snap) return v;
  return Math.round(v / SNAP_STEP) * SNAP_STEP;
}

function snapPos(p) {
  return p.map(snapValue);
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
  gizmoGroup.scale.setScalar(2); // 固定世界尺寸，随相机远近自然缩放（越远越小）
  setGizmoHover(null);
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

// 绘制平面切换时：在原点显示一个逐渐变大、变透明的浅白色平面
function triggerDrawPlanePulse() {
  if (planePulse) {
    scene.remove(planePulse.mesh);
    planePulse.mesh.geometry.dispose();
    planePulse.mesh.material.dispose();
  }
  const def = PLANES[state.drawPlane] || PLANES.XZ;
  const off = parseFloat(document.getElementById('height-y').value) || 0;
  const mesh = new THREE.Mesh(
    new THREE.CircleGeometry(1, 64),
    new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.55, depthWrite: false, side: THREE.DoubleSide })
  );
  if (state.drawPlane === 'XZ') mesh.rotation.x = -Math.PI / 2;
  else if (state.drawPlane === 'YZ') mesh.rotation.y = Math.PI / 2;
  mesh.position.set(
    def.constant === 'X' ? off : 0,
    def.constant === 'Y' ? off : 0,
    def.constant === 'Z' ? off : 0
  );
  mesh.renderOrder = 5;
  scene.add(mesh);
  planePulse = { mesh, t0: performance.now(), dur: 400 };
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
  const push = (u, v) => { const [x, y, z] = toWorld(snapValue(u), snapValue(v), off); out.push([x, y, z]); };
  const sU0 = snapValue(u0), sV0 = snapValue(v0), sU1 = snapValue(u1), sV1 = snapValue(v1);
  if (mode === 'line') {
    const n = shapeCount();
    for (let i = 0; i < n; i++) { const t = n === 1 ? 0.5 : i / (n - 1); push(sU0 + (sU1 - sU0) * t, sV0 + (sV1 - sV0) * t); }
  } else if (mode === 'circle') {
    const r = Math.hypot(sU1 - sU0, sV1 - sV0);
    const n = shapeCount();
    for (let i = 0; i < n; i++) { const a = (i / n) * Math.PI * 2; push(sU0 + Math.cos(a) * r, sV0 + Math.sin(a) * r); }
  } else if (mode === 'rect') {
    const n = Math.max(2, Math.round(Math.sqrt(shapeCount())));
    const uMin = Math.min(sU0, sU1), uMax = Math.max(sU0, sU1), vMin = Math.min(sV0, sV1), vMax = Math.max(sV0, sV1);
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
let dragIds = null;
const lastMouse = { x: 0, y: 0 };

function currentSelected() { return state.particles.filter(p => state.selected.has(p.id)); }

function selectedGroupName() {
  return state.selectedGroup && state.groups[state.selectedGroup] ? state.selectedGroup : null;
}

function rotateVector(v, axis, angle) {
  const c = Math.cos(angle), s = Math.sin(angle);
  const dot = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2];
  return [
    v[0] * c + (axis[1] * v[2] - axis[2] * v[1]) * s + axis[0] * dot * (1 - c),
    v[1] * c + (axis[2] * v[0] - axis[0] * v[2]) * s + axis[1] * dot * (1 - c),
    v[2] * c + (axis[0] * v[1] - axis[1] * v[0]) * s + axis[2] * dot * (1 - c),
  ];
}

function enterGrab(clientX, clientY) {
  if (state.selected.size === 0) return;
  pushUndo();
  const origins = new Map();
  for (const p of currentSelected()) origins.set(p.id, currentVisual(p).pos.slice());
  const c = selectionCentroid();
  const pt = planePointAt(clientX, clientY);
  const gname = selectedGroupName();
  modal = { type: 'grab', groupName: gname, startDelta: gname ? groupPosDeltaAt(gname, Math.round(state.time)) : null, origins, axis: null, startWorld: pt ? { x: pt.x, z: pt.z } : null, startClient: { x: clientX, y: clientY }, y: c ? c[1] : 0 };
  controls.enabled = false;
}

function enterScale(clientX) {
  if (state.selected.size === 0) return;
  pushUndo();
  const origins = new Map();
  for (const p of currentSelected()) origins.set(p.id, currentVisual(p).scale);
  modal = { type: 'scale', origins, startClient: { x: clientX } };
  controls.enabled = false;
}

const AXIS_VECTORS = { X: [1, 0, 0], Y: [0, 1, 0], Z: [0, 0, 1] };
const AXIS_INDEX = { X: 0, Y: 1, Z: 2 };

// 将鼠标射线与「过质心、法线为旋转轴」的平面求交，返回交点（世界坐标）
function rayOnAxisPlane(clientX, clientY, axisVec, centroid) {
  screenToNdc(clientX, clientY);
  raycaster.setFromCamera(pointer, camera);
  const normal = new THREE.Vector3(axisVec[0], axisVec[1], axisVec[2]);
  const origin = new THREE.Vector3(centroid[0], centroid[1], centroid[2]);
  const plane = new THREE.Plane(normal, -normal.dot(origin));
  const hit = new THREE.Vector3();
  return raycaster.ray.intersectPlane(plane, hit) ? hit : null;
}

function angleInBasis(point, centroid, u, v) {
  const rel = point.clone().sub(new THREE.Vector3(centroid[0], centroid[1], centroid[2]));
  return Math.atan2(rel.dot(v), rel.dot(u));
}

function groupRotationValueAt(gname, T) {
  const tr = findGroupTrack('rot', gname);
  if (tr && tr.kf.length > 0) return trackValueAt(tr, T, [0, 0, 0]);
  return [0, 0, 0];
}

function groupPosDeltaAt(gname, T) {
  const tr = findGroupTrack('pos', gname);
  if (tr && tr.kf.length > 0) return trackValueAt(tr, T, [0, 0, 0]);
  return [0, 0, 0];
}

function enterRotate(clientX, clientY, axis) {
  if (state.selected.size === 0) return;
  const gname = selectedGroupName();
  pushUndo();
  const c = selectionCentroid();
  const axArr = AXIS_VECTORS[axis] || AXIS_VECTORS.Y;
  const a = new THREE.Vector3(axArr[0], axArr[1], axArr[2]);
  let u = new THREE.Vector3(1, 0, 0);
  if (Math.abs(a.dot(u)) > 0.9) u.set(0, 1, 0);
  u.crossVectors(a, u).normalize();
  const v = new THREE.Vector3().crossVectors(a, u).normalize();
  const p0 = rayOnAxisPlane(clientX, clientY, axArr, c);
  const startAngle = p0 ? angleInBasis(p0, c, u, v) : 0;
  if (gname) {
    modal = {
      type: 'group-rotate', gname, centroid: c, axis: axArr,
      axisIndex: AXIS_INDEX[axis] || 1,
      startRot: groupRotationValueAt(gname, Math.round(state.time)),
      u, v, startAngle,
    };
  } else {
    const origins = new Map();
    for (const p of currentSelected()) origins.set(p.id, currentVisual(p).pos.slice());
    modal = {
      type: 'rotate', origins, centroid: c, axis: axArr, u, v, startAngle,
    };
  }
  controls.enabled = false;
}

function cancelModal() {
  if (!modal) return;
  if (modal.type === 'grab') {
    if (modal.groupName) setGroupTrackValue(modal.groupName, 'pos', 'op', Math.round(state.time), modal.startDelta || [0, 0, 0]);
    else for (const [id, orig] of modal.origins) editBaseValue([id], 'pos', orig);
  } else if (modal.type === 'scale') {
    for (const [id, orig] of modal.origins) editBaseValue([id], 'scl', [orig]);
  } else if (modal.type === 'rotate') {
    for (const [id, orig] of modal.origins) editBaseValue([id], 'pos', orig);
  } else if (modal.type === 'group-rotate') {
    setGroupTrackValue(modal.gname, 'rot', 'op', Math.round(state.time), modal.startRot);
  }
  modal = null;
  controls.enabled = true;
  rebuildPoints();
  popUndo();
}

function confirmModal() { modal = null; controls.enabled = true; }

function updateGrab(clientX, clientY) {
  const m = modal;
  if (!m || m.type !== 'grab') return;

  // Y 轴移动：仅依赖鼠标 Y 位移，不依赖绘制平面求交（避免镜头水平时求交失败产生“空气墙”）
  if (m.axis === 'Y') {
    const dy = -(clientY - m.startClient.y) * 0.02;
    const sdy = shiftHeld ? Math.round(dy / SNAP_STEP) * SNAP_STEP : dy;
    if (m.groupName) {
      const d = m.startDelta || [0, 0, 0];
      setGroupTrackValue(m.groupName, 'pos', 'op', Math.round(state.time), [d[0], d[1] + sdy, d[2]]);
    } else {
      for (const [id, orig] of m.origins) {
        editBaseValue([id], 'pos', [orig[0], orig[1] + sdy, orig[2]]);
      }
      rebuildPoints();
    }
    return;
  }

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
  const doSnap = shiftHeld;
  const sdx = doSnap ? Math.round(dx / SNAP_STEP) * SNAP_STEP : dx;
  const sdy = 0;
  const sdz = doSnap ? Math.round(dz / SNAP_STEP) * SNAP_STEP : dz;
  if (m.groupName) {
    const d = m.startDelta || [0, 0, 0];
    setGroupTrackValue(m.groupName, 'pos', 'op', Math.round(state.time), [d[0] + sdx, d[1] + sdy, d[2] + sdz]);
  } else {
    for (const [id, orig] of m.origins) {
      editBaseValue([id], 'pos', [orig[0] + sdx, orig[1] + sdy, orig[2] + sdz]);
    }
    rebuildPoints();
  }
}

function updateScale(clientX) {
  const m = modal;
  if (!m || m.type !== 'scale') return;
  const factor = Math.max(0.02, 1 + (clientX - m.startClient.x) * 0.01);
  for (const [id, orig] of m.origins) editBaseValue([id], 'scl', [orig * factor]);
  rebuildPoints();
}

function updateRotate(clientX, clientY) {
  const m = modal;
  if (!m || (m.type !== 'rotate' && m.type !== 'group-rotate')) return;
  const p1 = rayOnAxisPlane(clientX, clientY, m.axis, m.centroid);
  if (!p1) return;
  let angle = angleInBasis(p1, m.centroid, m.u, m.v) - m.startAngle;
  if (shiftHeld) angle = Math.round(angle / ROT_SNAP) * ROT_SNAP;
  if (m.type === 'group-rotate') {
    const newRot = m.startRot.slice();
    newRot[m.axisIndex] = m.startRot[m.axisIndex] + angle;
    setGroupTrackValue(m.gname, 'rot', 'op', Math.round(state.time), newRot);
    return;
  }
  const c = m.centroid;
  for (const [id, orig] of m.origins) {
    const rel = [orig[0] - c[0], orig[1] - c[1], orig[2] - c[2]];
    const r = rotateVector(rel, m.axis, angle);
    editBaseValue([id], 'pos', [c[0] + r[0], c[1] + r[1], c[2] + r[2]]);
  }
  rebuildPoints();
}

function deleteSelected() {
  if (state.selected.size === 0) return;
  pushUndo();
  for (const id of state.selected) {
    const idx = state.particles.findIndex(p => p.id === id);
    if (idx >= 0) state.particles.splice(idx, 1);
    state.tracks = state.tracks.filter(tr => !tr.ids.includes(id));
  }
  for (const g in state.groups) {
    state.groups[g] = state.groups[g].filter(id => state.particles.some(p => p.id === id));
    if (state.groups[g].length === 0) removeGroupAndTracks(g);
  }
  state.selected.clear();
  state.selectedGroup = null;
  rebuildPoints();
  refreshParticleTree();
}

function selectAll() {
  if (state.selected.size === state.particles.length && state.particles.length > 0) state.selected.clear();
  else state.selected = new Set(state.particles.map(p => p.id));
  state.selectedGroup = null;
  rebuildPoints();
}

let clipboard = null;
function copySelected() {
  const gname = selectedGroupName();
  if (gname) {
    const members = (state.groups[gname] || []).map(getParticle).filter(Boolean);
    if (members.length === 0) return;
    const memberIds = new Set(members.map(m => m.id));
    const items = members.map(p => ({
      id: p.id, style: p.style, color: p.color.slice(), scale: p.scale, glow: p.glow,
      lightLevel: p.lightLevel, pos: currentVisual(p).pos.slice(), vel: (p.vel || [0, 0, 0]).slice(),
    }));
    const tracks = state.tracks
      .filter(tr => tr.ids.some(id => id === 'g:' + gname || memberIds.has(id)))
      .map(tr => ({ pr: tr.pr, m: tr.m, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]) }));
    clipboard = { type: 'group', groupName: gname, items, tracks };
    return;
  }
  const sel = currentSelected();
  if (sel.length === 0) return;
  clipboard = {
    type: 'particles',
    items: sel.map(p => ({
      id: p.id, style: p.style, color: p.color.slice(), scale: p.scale, glow: p.glow,
      lightLevel: p.lightLevel, pos: currentVisual(p).pos.slice(), vel: (p.vel || [0, 0, 0]).slice(),
    })),
  };
}
function pasteClipboard() {
  if (!clipboard || !clipboard.items || clipboard.items.length === 0) return;
  pushUndo();
  const idMap = {};
  const newIds = [];
  for (const item of clipboard.items) {
    const p = addParticle({
      style: item.style, color: item.color.slice(), scale: item.scale, glow: item.glow,
      lightLevel: item.lightLevel, pos: [item.pos[0] + 1, item.pos[1], item.pos[2] + 1], vel: item.vel.slice(),
    });
    idMap[item.id] = p.id;
    newIds.push(p.id);
  }
  if (clipboard.type === 'group') {
    const newGroupName = nextGroupName();
    state.groups[newGroupName] = newIds.slice();
    for (const tr of clipboard.tracks) {
      state.tracks.push({
        pr: tr.pr, m: tr.m,
        ids: tr.ids.map(id => id.startsWith('g:') ? 'g:' + newGroupName : (idMap[id] || id)),
        kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]),
      });
    }
    state.selectedGroup = newGroupName;
  } else {
    state.selectedGroup = null;
  }
  state.selected = new Set(newIds);
  rebuildPoints();
  refreshParticleTree();
}

function hitGizmoAxis(clientX, clientY) {
  const c = selectionCentroid();
  if (!c) return null;
  const rect = renderer.domElement.getBoundingClientRect();
  const px = clientX - rect.left, py = clientY - rect.top;
  const scale = gizmoGroup.scale.x || 1;
  for (const [axis, v] of Object.entries(AXIS_VECTORS)) {
    const s = projectToScreen(c[0], c[1], c[2]);
    const e = projectToScreen(c[0] + v[0] * 1.5 * scale, c[1] + v[1] * 1.5 * scale, c[2] + v[2] * 1.5 * scale);
    if (distToSegment(px, py, s.x, s.y, e.x, e.y) < 10) return axis;
  }
  return null;
}

function hitGizmoRotate(clientX, clientY) {
  if (state.selected.size === 0) return null;
  screenToNdc(clientX, clientY);
  raycaster.setFromCamera(pointer, camera);
  const hits = raycaster.intersectObjects(Object.values(gizmoRings));
  if (hits.length === 0) return null;
  for (const [axis, ring] of Object.entries(gizmoRings)) {
    if (hits[0].object === ring) return axis;
  }
  return null;
}

const AXIS_COLORS = { X: 0xff5555, Y: 0x55ff55, Z: 0x5588ff };
function setGizmoHover(axis) {
  if (!gizmoGroup.visible) return;
  for (const [ax, ring] of Object.entries(gizmoRings)) {
    ring.material.color.set(axis === ax ? 0xffffff : AXIS_COLORS[ax]);
  }
  for (const [ax, a] of Object.entries(gizmoArrows)) {
    const c = ax === axis ? 0xffffff : AXIS_COLORS[ax];
    a.shaft.material.color.set(c);
    a.head.material.color.set(c);
  }
}

renderer.domElement.addEventListener('pointerdown', (ev) => {
  lastMouse.x = ev.clientX; lastMouse.y = ev.clientY;
  if (ev.button === 1 || ev.button === 2) { renderer.domElement.style.cursor = 'grabbing'; return; }
  if (ev.button !== 0) return;
  if (modal) { confirmModal(); return; }

  if (state.tool === 'select') {
    const axis = hitGizmoAxis(ev.clientX, ev.clientY);
    if (axis) {
      pushUndo();
      const origins = new Map();
      for (const p of currentSelected()) origins.set(p.id, currentVisual(p).pos.slice());
      const c = selectionCentroid();
      const pt = planePointAt(ev.clientX, ev.clientY);
      const gname = selectedGroupName();
      modal = { type: 'grab', groupName: gname, startDelta: gname ? groupPosDeltaAt(gname, Math.round(state.time)) : null, origins, axis, startWorld: pt ? { x: pt.x, z: pt.z } : null, startClient: { x: ev.clientX, y: ev.clientY }, y: c ? c[1] : 0 };
      controls.enabled = false;
      return;
    }
    const rotAxis = hitGizmoRotate(ev.clientX, ev.clientY);
    if (rotAxis) {
      enterRotate(ev.clientX, ev.clientY, rotAxis);
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
      pushUndo();
      const [u, v] = worldToUV(pt);
      const [x, y, z] = PLANES[state.drawPlane].toWorld(snapValue(u), snapValue(v), planeInfo().off);
      addParticle({ pos: [x, y, z] });
      rebuildPoints(); refreshParticleTree();
    }
    return;
  }
  if (state.tool === 'erase') {
    const idx = pickParticleAt(ev.clientX, ev.clientY);
    const p = particleAt(idx);
    if (p) {
      pushUndo();
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
    drag = { mode: state.tool, start: { u, v }, off: planeInfo().off, last: { u, v }, startIndex: state.particles.length };
    if (state.tool === 'freehand') {
      pushUndo();
      const [x, y, z] = PLANES[state.drawPlane].toWorld(snapValue(u), snapValue(v), drag.off);
      addParticle({ pos: [x, y, z] });
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
    else if (modal.type === 'rotate' || modal.type === 'group-rotate') updateRotate(ev.clientX, ev.clientY);
    return;
  }
  if (boxSel) { boxSel.x1 = ev.clientX; boxSel.y1 = ev.clientY; updateBoxOverlay(); return; }
  if (!drag) {
    // 悬停高亮方向轴控制器
    if (state.tool === 'select' && state.selected.size > 0) {
      setGizmoHover(hitGizmoAxis(ev.clientX, ev.clientY) || hitGizmoRotate(ev.clientX, ev.clientY));
    } else {
      setGizmoHover(null);
    }
    return;
  }

  if (drag.mode === 'freehand') {
    const pt = planePointAt(ev.clientX, ev.clientY);
    if (!pt) return;
    const [u, v] = worldToUV(pt);
    const d = Math.hypot(u - drag.last.u, v - drag.last.v);
    if (d >= 0.25) {
      const [x, y, z] = PLANES[state.drawPlane].toWorld(snapValue(u), snapValue(v), drag.off);
      addParticle({ pos: [x, y, z] });
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
      pushUndo();
      const startIndex = state.particles.length;
      for (const pos of positions) addParticle({ pos });
      autoGroup(state.particles.slice(startIndex).map(p => p.id));
    }
  } else if (drag.mode === 'freehand') {
    autoGroup(state.particles.slice(drag.startIndex).map(p => p.id));
  }
  drag = null;
  controls.enabled = true;
  clearPreview();
  rebuildPoints();
  refreshParticleTree();
});

renderer.domElement.addEventListener('contextmenu', (ev) => { ev.preventDefault(); if (modal) cancelModal(); });
window.addEventListener('pointerup', () => { renderer.domElement.style.cursor = ''; });

window.addEventListener('keydown', (ev) => {
  const k = ev.key.toLowerCase();
  const isTextInput = ev.target && ev.target.matches && ev.target.matches('input[type="text"], textarea, input[type="search"], .rename-input');
  // 全局快捷键：无论焦点在何处都生效
  if (ev.ctrlKey && k === 'z') { ev.preventDefault(); if (ev.shiftKey) redo(); else undo(); return; }
  if (ev.ctrlKey && k === 'y') { ev.preventDefault(); redo(); return; }
  if (ev.ctrlKey && k === 'n') { ev.preventDefault(); newFile(); return; }
  if (ev.ctrlKey && k === 's') { ev.preventDefault(); saveFile(); return; }
  if (ev.ctrlKey && k === 'o') { ev.preventDefault(); openFile(); return; }
  if (ev.ctrlKey && k === 'g') { ev.preventDefault(); createGroup(); return; }
  if (ev.ctrlKey && k === 'a') { ev.preventDefault(); selectAll(); return; }
  if (ev.ctrlKey && k === 'd') { ev.preventDefault(); state.selected.clear(); state.selectedGroup = null; rebuildPoints(); return; }
  if (ev.ctrlKey && k === 'c') { ev.preventDefault(); copySelected(); return; }
  if (ev.ctrlKey && k === 'v') { ev.preventDefault(); pasteClipboard(); return; }
  if (k === ' ') { ev.preventDefault(); togglePlay(); return; }
  if (isTextInput) return;
  if (modal) {
    if (k === 'escape') cancelModal();
    else if (k === 'enter') confirmModal();
    else if (modal.type === 'grab' && (k === 'x' || k === 'y' || k === 'z')) modal.axis = modal.axis === k.toUpperCase() ? null : k.toUpperCase();
    return;
  }
  if (k === 's') enterScale(lastMouse.x);
  else if (k === 'delete' || k === 'backspace') deleteSelected();
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
const GIZMO_SIZE = 128;
gizmoCanvas.width = GIZMO_SIZE; gizmoCanvas.height = GIZMO_SIZE;
const GIZMO_CENTER = GIZMO_SIZE / 2;
const GIZMO_RING_R = 42;
let gizmoDrag = null;
let gizmoHoverAxis = null;
const gizmoRingPolys = { X: [], Y: [], Z: [] };

const GIZMO_RING_DEFS = [
  { key: 'X', axis: new THREE.Vector3(1, 0, 0), color: '#ff5555' },
  { key: 'Y', axis: new THREE.Vector3(0, 1, 0), color: '#55ff55' },
  { key: 'Z', axis: new THREE.Vector3(0, 0, 1), color: '#5588ff' },
];

// 采样垂直于某轴的世界圆环并转换到相机空间（z<0 为朝向相机的一侧）
function gizmoRingWorld(axisVec, inv, N) {
  const a = axisVec.clone().normalize();
  let u = new THREE.Vector3(1, 0, 0);
  if (Math.abs(a.dot(u)) > 0.9) u.set(0, 1, 0);
  u.crossVectors(a, u).normalize();
  const v = new THREE.Vector3().crossVectors(a, u).normalize();
  const pts = [];
  for (let i = 0; i <= N; i++) {
    const t = (i / N) * Math.PI * 2;
    const p = new THREE.Vector3().addScaledVector(u, Math.cos(t)).addScaledVector(v, Math.sin(t));
    p.applyQuaternion(inv);
    pts.push(p);
  }
  return pts;
}

function drawAxisGizmo() {
  const c = gizmoCtx;
  const cx = GIZMO_CENTER, cy = GIZMO_CENTER;
  c.clearRect(0, 0, GIZMO_SIZE, GIZMO_SIZE);
  const inv = new THREE.Quaternion().copy(camera.quaternion).invert();
  c.lineWidth = 2.5;
  for (const def of GIZMO_RING_DEFS) {
    const world = gizmoRingWorld(def.axis, inv, 72);
    const poly = world.map(p => [cx + p.x * GIZMO_RING_R, cy - p.y * GIZMO_RING_R]);
    gizmoRingPolys[def.key] = poly;
    for (let i = 0; i < poly.length - 1; i++) {
      const [ax, ay] = poly[i], [bx, by] = poly[i + 1];
      const front = (world[i].z + world[i + 1].z) / 2 < 0;
      c.strokeStyle = def.key === gizmoHoverAxis ? '#ffffff' : def.color;
      c.globalAlpha = def.key === gizmoHoverAxis ? 1 : (front ? 1 : 0.32);
      c.beginPath(); c.moveTo(ax, ay); c.lineTo(bx, by); c.stroke();
    }
  }
  c.globalAlpha = 1;
  // 中心：自由旋转原点
  c.fillStyle = '#dfe5f0';
  c.beginPath(); c.arc(cx, cy, 5, 0, Math.PI * 2); c.fill();
  c.strokeStyle = '#12141a'; c.lineWidth = 1.5; c.stroke();
}

function hitAxisRing(px, py) {
  let best = null, bestD = 11;
  for (const [key, poly] of Object.entries(gizmoRingPolys)) {
    for (let i = 0; i < poly.length - 1; i++) {
      const d = distToSegment(px, py, poly[i][0], poly[i][1], poly[i + 1][0], poly[i + 1][1]);
      if (d < bestD) { bestD = d; best = key; }
    }
  }
  return best;
}

function orbitCamera(dx, dy) {
  const offset = camera.position.clone().sub(controls.target);
  const radius = Math.max(0.5, offset.length());
  let theta = Math.atan2(offset.x, offset.z);
  let phi = Math.acos(Math.max(-1, Math.min(1, offset.y / radius)));
  theta -= dx * 0.01;
  phi = Math.max(0.05, Math.min(Math.PI - 0.05, phi - dy * 0.01));
  const sp = Math.sin(phi);
  camera.position.set(
    controls.target.x + radius * sp * Math.sin(theta),
    controls.target.y + radius * Math.cos(phi),
    controls.target.z + radius * sp * Math.cos(theta)
  );
  camera.lookAt(controls.target);
  controls.update();
}

function orbitAroundAxis(axisKey, angle) {
  const ax = GIZMO_RING_DEFS.find(d => d.key === axisKey);
  if (!ax) return;
  const off = camera.position.clone().sub(controls.target);
  off.applyAxisAngle(ax.axis, angle);
  camera.position.copy(controls.target).add(off);
  camera.lookAt(controls.target);
  controls.update();
}

gizmoCanvas.addEventListener('pointerdown', (ev) => {
  const rect = gizmoCanvas.getBoundingClientRect();
  const px = (ev.clientX - rect.left) * (GIZMO_SIZE / rect.width);
  const py = (ev.clientY - rect.top) * (GIZMO_SIZE / rect.height);
  gizmoCanvas.setPointerCapture(ev.pointerId);
  gizmoCanvas.style.cursor = 'grabbing';
  const ring = hitAxisRing(px, py);
  if (ring) {
    gizmoDrag = { mode: 'axis', axis: ring, startAngle: Math.atan2(py - GIZMO_CENTER, px - GIZMO_CENTER), moved: false };
  } else {
    gizmoDrag = { mode: 'free', x: ev.clientX, y: ev.clientY, moved: false };
  }
});

gizmoCanvas.addEventListener('pointermove', (ev) => {
  const rect = gizmoCanvas.getBoundingClientRect();
  const px = (ev.clientX - rect.left) * (GIZMO_SIZE / rect.width);
  const py = (ev.clientY - rect.top) * (GIZMO_SIZE / rect.height);
  if (!gizmoDrag) {
    gizmoHoverAxis = hitAxisRing(px, py);
    gizmoCanvas.style.cursor = gizmoHoverAxis ? 'pointer' : 'default';
    return;
  }
  if (gizmoDrag.mode === 'free') {
    const dx = ev.clientX - gizmoDrag.x, dy = ev.clientY - gizmoDrag.y;
    gizmoDrag.x = ev.clientX; gizmoDrag.y = ev.clientY;
    if (Math.abs(dx) + Math.abs(dy) > 2) gizmoDrag.moved = true;
    orbitCamera(dx, dy);
  } else {
    const ang = Math.atan2(py - GIZMO_CENTER, px - GIZMO_CENTER);
    let d = ang - gizmoDrag.startAngle;
    if (d > Math.PI) d -= 2 * Math.PI; else if (d < -Math.PI) d += 2 * Math.PI;
    if (Math.abs(d) > 0.02) gizmoDrag.moved = true;
    orbitAroundAxis(gizmoDrag.axis, d);
    gizmoDrag.startAngle = ang;
  }
});
gizmoCanvas.addEventListener('pointerleave', () => { gizmoHoverAxis = null; });
gizmoCanvas.addEventListener('pointerup', () => {
  if (!gizmoDrag) return;
  const info = gizmoDrag;
  gizmoDrag = null;
  gizmoCanvas.style.cursor = '';
  if (info.mode === 'axis' && !info.moved) {
    const def = GIZMO_RING_DEFS.find(d => d.key === info.axis);
    if (def) orientToAxis(def.axis);
  }
});

function slerp(a, b, t) {
  const dot = Math.min(1, Math.max(-1, a.dot(b)));
  const theta = Math.acos(dot);
  if (theta < 1e-6) return a.clone();
  const sinTheta = Math.sin(theta);
  const wa = Math.sin((1 - t) * theta) / sinTheta;
  const wb = Math.sin(t * theta) / sinTheta;
  return a.clone().multiplyScalar(wa).addScaledVector(b, wb);
}

function orientToAxis(dir) {
  const dist = camera.position.distanceTo(controls.target);
  const target = controls.target.clone();
  const startDir = camera.position.clone().sub(target).normalize();
  const endPos = target.clone().sub(dir.clone().normalize().multiplyScalar(dist));
  const endDir = endPos.clone().sub(target).normalize();
  const endUp = Math.abs(dir.y) > 0.9 ? new THREE.Vector3(0, 0, 1) : new THREE.Vector3(0, 1, 0);
  camTransition = { startDir, endDir, startUp: camera.up.clone(), endUp, target, dist, t0: performance.now(), dur: 320 };
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
  pushUndo();
  const name = nextGroupName();
  const idSet = new Set(state.selected);
  for (const g in state.groups) {
    state.groups[g] = state.groups[g].filter(id => !idSet.has(id));
    if (state.groups[g].length === 0) delete state.groups[g];
  }
  state.groups[name] = [...state.selected];
  state.selectedGroup = name;
  refreshParticleTree();
}

function deleteGroup(name) {
  pushUndo();
  removeGroupAndTracks(name);
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 粒子列表（树状时间轴）
 * ======================================================================= */

function groupedIds() {
  const ids = new Set();
  for (const members of Object.values(state.groups)) for (const id of members) ids.add(id);
  return ids;
}

function refreshParticleTree() {
  const box = document.getElementById('particle-tree');
  if (!box) return;
  box.innerHTML = '';
  if (state.particles.length === 0 && Object.keys(state.groups).length === 0) {
    box.innerHTML = '<div class="hint" style="padding:8px">暂无粒子，请使用右侧工具绘制</div>';
    return;
  }
  const grouped = groupedIds();
  for (const p of state.particles) {
    if (grouped.has(p.id)) continue; // 组内粒子折叠进组中
    box.appendChild(renderParticleNode(p));
  }
  for (const name of Object.keys(state.groups)) box.appendChild(renderGroupNode(name));
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
  head.draggable = true;
  head.addEventListener('dragstart', (e) => {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', p.id);
    dragIds = state.selected.has(p.id) ? [...state.selected] : [p.id];
  });
  head.addEventListener('dragend', () => { dragIds = null; });
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
  pid.title = '双击重命名';
  pid.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startRename(pid, (v) => renameParticle(p.id, v), () => refreshParticleTree());
  });
  const style = document.createElement('span');
  style.className = 'pstyle'; style.textContent = p.style;
  head.appendChild(arrow); head.appendChild(pid); head.appendChild(style);
  const trackCount = state.tracks.filter(tr => tr.ids.length === 1 && tr.ids[0] === p.id).length;
  if (trackCount > 0) {
    const cnt = document.createElement('span');
    cnt.className = 'ptree-track-count';
    cnt.textContent = trackCount + ' 个时间轴';
    head.appendChild(cnt);
  }
  head.onclick = () => {
    if (evShift()) {
      const anchorIdx = treeAnchorId ? state.particles.findIndex(x => x.id === treeAnchorId) : -1;
      const idx = state.particles.findIndex(x => x.id === p.id);
      if (anchorIdx >= 0 && idx >= 0 && anchorIdx !== idx) {
        const a = Math.min(anchorIdx, idx), b = Math.max(anchorIdx, idx);
        state.selected.clear();
        for (let i = a; i <= b; i++) state.selected.add(state.particles[i].id);
      } else {
        state.selected.add(p.id);
      }
    } else {
      state.selected.clear();
      state.selected.add(p.id);
      treeAnchorId = p.id;
    }
    state.selectedGroup = null;
    rebuildPoints();
  };
  head.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除粒子', danger: true, action: () => { state.selected = new Set([p.id]); deleteSelected(); } },
    ]);
  });
  root.appendChild(head);

  if (expanded) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of PARTICLE_TRACK_DEFS) props.appendChild(renderParticleTrackNode(p, def));
    root.appendChild(props);
  }
  return root;
}

function startRename(el, onCommit, onCancel) {
  const input = document.createElement('input');
  input.type = 'text';
  input.value = el.textContent;
  input.className = 'rename-input';
  el.replaceWith(input);
  input.focus();
  input.select();
  let done = false;
  const commit = () => { if (done) return; done = true; onCommit(input.value); };
  const cancel = () => { if (done) return; done = true; onCancel(); };
  input.addEventListener('blur', commit);
  input.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') commit();
    else if (e.key === 'Escape') cancel();
  });
}

/* 右键菜单 */
function closeContextMenu() {
  const m = document.getElementById('context-menu');
  if (m) m.remove();
}
function showContextMenu(x, y, items) {
  closeContextMenu();
  const menu = document.createElement('div');
  menu.id = 'context-menu';
  menu.className = 'context-menu';
  for (const item of items) {
    if (item === null) { const sep = document.createElement('div'); sep.className = 'cm-sep'; menu.appendChild(sep); continue; }
    const btn = document.createElement('button');
    btn.className = 'cm-item' + (item.danger ? ' danger' : '');
    btn.textContent = item.label;
    btn.onclick = () => { closeContextMenu(); item.action(); };
    menu.appendChild(btn);
  }
  document.body.appendChild(menu);
  menu.style.left = Math.min(x, window.innerWidth - 160) + 'px';
  menu.style.top = Math.min(y, window.innerHeight - items.length * 32 - 12) + 'px';
}
window.addEventListener('pointerdown', (e) => { if (!e.target.closest('#context-menu')) closeContextMenu(); });

let evShift = () => false; // 占位，稍后在 initUI 中通过 window 事件设置
let treeAnchorId = null; // 树状列表 Shift 范围选择的锚点

function makeColorSwatch(kf, onCommit) {
  const inp = document.createElement('input');
  inp.type = 'color';
  inp.className = 'kf-color';
  inp.value = rgbToHex(kf[1][0], kf[1][1], kf[1][2]);
  inp.title = '取色';
  inp.addEventListener('input', (e) => {
    beginContinuous();
    const [r, g, b] = hexToRgb(e.target.value);
    onCommit(r, g, b);
  });
  inp.addEventListener('change', () => {
    endContinuous();
    refreshParticleTree();
  });
  return inp;
}

function renderParticleTrackNode(p, def) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = p.id + '|' + def.key;
  const expanded = state.expandedProps.has(key);
  const tr = findTrack(def.key, p.id);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  head.innerHTML = `<span class="arrow">${expanded ? '▾' : '▸'}</span><span class="plabel">${def.label}</span><span class="pval">${tr ? tr.kf.length + ' 节点' : '—'}</span>`;
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) for (const kf of tr.kf) kfs.appendChild(renderParticleKfRow(p, def, kf));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => {
      pushUndo();
      let tr = findTrack(def.key, p.id);
      if (!tr) {
        tr = { pr: def.key, m: 'set', ids: [p.id], kf: [[0, baseValue(p, def.key).slice(), state.defaultEasing]] };
        state.tracks.push(tr);
      }
      const t = nextFreeTime(tr, Math.round(state.time));
      tr.kf.push([t, particleValueAt(p, def.key, t).slice(), state.defaultEasing]);
      tr.kf.sort((a, b) => a[0] - b[0]);
      rebuildPoints(); refreshParticleTree();
    };
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function renderParticleKfRow(p, def, kf) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.title = '时间 (tick)';
  tIn.onchange = () => updateKeyframeTime(p.id, def.key, kf[0], parseInt(tIn.value) || 0);
  row.appendChild(tIn);
  if (def.key === 'col') {
    row.appendChild(makeColorSwatch(kf, (r, g, b) => {
      kf[1][0] = r; kf[1][1] = g; kf[1][2] = b;
      if (kf[0] === 0) applyBaseValue(p, def.key, kf[1]);
      rebuildPoints();
    }));
  }
  for (let i = 0; i < def.labels.length; i++) {
    const vIn = document.createElement('input');
    vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][i]);
    vIn.title = def.labels[i] + ' 值';
    vIn.onchange = () => setComponentValue(p.id, { track: def.key, index: i }, kf[0], parseFloat(vIn.value) || 0);
    row.appendChild(vIn);
  }
  const easeBtn = makeEasingBtn(kf[2], (easing) => updateKeyframeEasing(p.id, def.key, kf[0], easing));
  row.appendChild(easeBtn);
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除节点', danger: true, action: () => removeKeyframe(p.id, def.key, kf[0]) },
    ]);
  });
  return row;
}

function renderGroupNode(name) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const members = state.groups[name] || [];
  const expanded = state.expandedParticles.has('g:' + name);
  const head = document.createElement('div');
  head.className = 'ptree-head group';
  head.addEventListener('dragover', (e) => {
    if (dragIds) { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; head.classList.add('drop-hint'); }
  });
  head.addEventListener('dragleave', () => head.classList.remove('drop-hint'));
  head.addEventListener('drop', (e) => {
    e.preventDefault();
    e.stopPropagation();
    head.classList.remove('drop-hint');
    if (dragIds) moveParticlesToGroup(dragIds, name);
    dragIds = null;
  });
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete('g:' + name); else state.expandedParticles.add('g:' + name);
    refreshParticleTree();
  };
  const label = document.createElement('span');
  label.className = 'pid';
  label.textContent = name;
  label.title = '双击重命名';
  label.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startRename(label, (v) => renameGroup(name, v), () => refreshParticleTree());
  });
  const count = document.createElement('span');
  count.className = 'pstyle';
  count.textContent = members.length + ' 成员';
  head.appendChild(arrow); head.appendChild(label); head.appendChild(count);
  head.onclick = () => {
    state.selected = new Set(members.filter(id => state.particles.some(p => p.id === id)));
    state.selectedGroup = name;
    rebuildPoints();
  };
  head.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除组及其粒子', danger: true, action: () => deleteGroup(name) },
    ]);
  });
  root.appendChild(head);
  if (expanded) {
    const section = document.createElement('div');
    section.className = 'ptree-sub';
    section.appendChild(renderGroupPropsNode(name));
    section.appendChild(renderGroupMembersNode(name, members));
    root.appendChild(section);
  }
  return root;
}

function renderGroupPropsNode(name) {
  const wrap = document.createElement('div');
  const key = 'g:' + name + '|@props';
  // 「属性」默认展开：记录 key 表示已收起
  const collapsed = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = collapsed ? '▸' : '▾';
  const label = document.createElement('span');
  label.textContent = '属性';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (collapsed) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (!collapsed) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of GROUP_PROP_DEFS) props.appendChild(renderGroupPropNode(name, def));
    wrap.appendChild(props);
  }
  return wrap;
}

function renderGroupMembersNode(name, members) {
  const wrap = document.createElement('div');
  const key = 'g:' + name + '|@members';
  const expanded = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const label = document.createElement('span');
  label.textContent = '粒子列表 (' + members.length + ')';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const list = document.createElement('div');
    list.className = 'ptree-members';
    for (const id of members) {
      const p = getParticle(id);
      if (!p) continue;
      list.appendChild(renderParticleNode(p));
    }
    wrap.appendChild(list);
  }
  return wrap;
}

function renderGroupPropNode(name, def) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = 'g:' + name + '|' + def.key;
  const expanded = state.expandedProps.has(key);
  const tr = findGroupTrack(def.key, name);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const plabel = document.createElement('span');
  plabel.className = 'plabel';
  plabel.textContent = def.label;
  head.appendChild(arrow); head.appendChild(plabel);
  const modeSel = document.createElement('select');
  modeSel.className = 'mode-sel';
  const mSet = document.createElement('option'); mSet.value = 'set'; mSet.textContent = '设置';
  const mOp = document.createElement('option'); mOp.value = 'op'; mOp.textContent = '操作';
  modeSel.appendChild(mSet); modeSel.appendChild(mOp);
  const defaultMode = def.key === 'col' ? 'set' : 'op';
  modeSel.value = tr ? tr.m : defaultMode;
  modeSel.onchange = () => setGroupTrackMode(name, def.key, modeSel.value);
  modeSel.onclick = (e) => e.stopPropagation();
  head.appendChild(modeSel);
  const pval = document.createElement('span');
  pval.className = 'pval';
  pval.textContent = tr ? tr.kf.length + ' 节点' : '—';
  head.appendChild(pval);
  head.onclick = (e) => {
    if (e.target.tagName === 'SELECT') return;
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) for (const kf of tr.kf) kfs.appendChild(renderGroupKfRow(name, def, tr.m, kf));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => {
      pushUndo();
      const mode = modeSel.value;
      let track = findGroupTrack(def.key, name);
      if (!track) {
        const base = mode === 'op' ? zeroArray(def.key).slice() : groupCentroidValue(name, def.key);
        track = { pr: def.key, m: mode, ids: ['g:' + name], kf: [[0, base, state.defaultEasing]] };
        state.tracks.push(track);
      } else {
        track.m = mode;
      }
      const t = nextFreeTime(track, Math.round(state.time));
      const cur = mode === 'op' ? zeroArray(def.key).slice() : groupCentroidValue(name, def.key);
      track.kf.push([t, cur, state.defaultEasing]);
      track.kf.sort((a, b) => a[0] - b[0]);
      rebuildPoints(); refreshParticleTree();
    };
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function groupCentroidValue(name, prop) {
  if (prop === 'rot') return [0, 0, 0];
  const members = (state.groups[name] || []).map(getParticle).filter(Boolean);
  if (members.length === 0) return zeroArray(prop);
  const sum = zeroArray(prop);
  for (const m of members) {
    const v = baseValue(m, prop);
    for (let i = 0; i < sum.length; i++) sum[i] += v[i];
  }
  return sum.map(v => r3(v / members.length));
}

// 组当前视觉值的质心（含动画）
function groupCurrentCentroid(name, prop) {
  const members = (state.groups[name] || []).map(getParticle).filter(Boolean);
  if (members.length === 0) return zeroArray(prop);
  const sum = zeroArray(prop);
  for (const m of members) {
    const v = particleValueAt(m, prop, state.time);
    for (let i = 0; i < sum.length; i++) sum[i] += v[i];
  }
  return sum.map(v => r3(v / members.length));
}

// 捕获关键帧：优先写入组轨道，仅对非组粒子写逐粒子关键帧
function captureKeyframes() {
  const ids = [...state.selected];
  if (ids.length === 0) { alert('请先选择粒子'); return; }
  pushUndo();
  const t = Math.round(state.time);
  const gname = selectedGroupName();
  if (gname) {
    const basePos = groupCentroidValue(gname, 'pos');
    const curPos = groupCurrentCentroid(gname, 'pos');
    const delta = [curPos[0] - basePos[0], curPos[1] - basePos[1], curPos[2] - basePos[2]];
    if (delta.some(d => Math.abs(d) > 1e-9)) setGroupTrackValue(gname, 'pos', 'op', t, delta);
    const rot = groupRotationValueAt(gname, t);
    if (rot.some(r => Math.abs(r) > 1e-9)) setGroupTrackValue(gname, 'rot', 'op', t, rot);
    setGroupTrackValue(gname, 'col', 'set', t, groupCurrentCentroid(gname, 'col'));
    setGroupTrackValue(gname, 'scl', 'set', t, groupCurrentCentroid(gname, 'scl'));
  } else {
    for (const id of ids) {
      const p = getParticle(id);
      if (!p) continue;
      const v = currentVisual(p);
      setValueAtTime([id], 'pos', v.pos);
      setValueAtTime([id], 'col', v.color);
      setValueAtTime([id], 'scl', [v.scale]);
      setValueAtTime([id], 'vel', p.vel || [0, 0, 0]);
    }
  }
}

function renderGroupKfRow(name, def, mode, kf) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.title = '时间 (tick)';
  tIn.onchange = () => updateKeyframeTime('g:' + name, def.key, kf[0], parseInt(tIn.value) || 0);
  row.appendChild(tIn);
  if (def.key === 'col' && mode !== 'op') {
    row.appendChild(makeColorSwatch(kf, (r, g, b) => {
      kf[1][0] = r; kf[1][1] = g; kf[1][2] = b;
      rebuildPoints();
    }));
  }
  for (let i = 0; i < def.size; i++) {
    const vIn = document.createElement('input');
    vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][i]);
    vIn.title = def.labels
      ? (def.key === 'rot' ? def.labels[i] + ' (弧度)' : def.labels[i] + (mode === 'op' ? ' 增量' : ' 值'))
      : def.key;
    vIn.onchange = () => {
      const v = kf[1].slice();
      v[i] = parseFloat(vIn.value) || 0;
      pushUndo();
      setGroupTrackValue(name, def.key, mode, kf[0], v);
    };
    row.appendChild(vIn);
  }
  const easeBtn = makeEasingBtn(kf[2], (easing) => updateKeyframeEasing('g:' + name, def.key, kf[0], easing));
  row.appendChild(easeBtn);
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除节点', danger: true, action: () => removeKeyframe('g:' + name, def.key, kf[0]) },
    ]);
  });
  return row;
}

/* =========================================================================
 * 缓动函数编辑器
 * ======================================================================= */

function easingToBezier(easing) {
  if (Array.isArray(easing)) return easing.slice(0, 4);
  const p = EASINGS[easing] || EASINGS[0];
  return [p[1], p[2], p[3], p[4]];
}

function easingCurveSVG(easing) {
  const w = 28, h = 16;
  let d = '';
  for (let i = 0; i <= 24; i++) {
    const t = i / 24;
    const y = easeVal(t, easing);
    const x = t * w, yy = h - 1 - y * (h - 2);
    d += (i === 0 ? 'M' : 'L') + x.toFixed(1) + ',' + yy.toFixed(1);
  }
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><path d="${d}" fill="none" stroke="#5b9dff" stroke-width="1.6"/></svg>`;
}

function makeEasingBtn(easing, applyFn) {
  const btn = document.createElement('button');
  btn.className = 'ease-btn';
  btn.innerHTML = easingCurveSVG(easing);
  btn.title = '编辑缓动函数';
  btn.onclick = (e) => { e.stopPropagation(); openEasingEditor(easing, applyFn, btn); };
  return btn;
}

let easingEditor = null;

function openEasingEditor(easing, applyFn, anchor) {
  closeEasingEditor();
  const bezier = easingToBezier(easing);
  easingEditor = { bezier, apply: applyFn, anchor, dragging: -1, inputs: {} };
  const pop = document.createElement('div');
  pop.id = 'easing-editor';
  pop.className = 'easing-editor';
  const title = document.createElement('div');
  title.className = 'ee-title';
  title.textContent = '缓动函数编辑器';
  pop.appendChild(title);

  const inputs = document.createElement('div');
  inputs.className = 'ee-inputs';
  const mkRow = (label, idxX, idxY) => {
    const row = document.createElement('div');
    row.className = 'ee-input-row';
    const lab = document.createElement('span');
    lab.className = 'ee-input-label';
    lab.textContent = label;
    row.appendChild(lab);
    const mkInp = (idx) => {
      const inp = document.createElement('input');
      inp.type = 'number';
      inp.step = '0.01';
      inp.min = '0'; inp.max = '1';
      inp.value = bezier[idx].toFixed(3);
      inp.addEventListener('input', () => {
        const v = Math.min(1, Math.max(0, parseFloat(inp.value) || 0));
        easingEditor.bezier[idx] = v;
        easingEditor.apply(easingEditor.bezier.slice());
        drawEasingEditor();
      });
      easingEditor.inputs[idx] = inp;
      row.appendChild(inp);
    };
    mkInp(idxX); mkInp(idxY);
    return row;
  };
  inputs.appendChild(mkRow('P1', 0, 1));
  inputs.appendChild(mkRow('P2', 2, 3));
  pop.appendChild(inputs);

  const canvas = document.createElement('canvas');
  canvas.className = 'ee-canvas';
  canvas.width = 196; canvas.height = 132;
  pop.appendChild(canvas);
  const presetSel = document.createElement('select');
  presetSel.className = 'ee-presets';
  const opt0 = document.createElement('option');
  opt0.value = ''; opt0.textContent = '预设…';
  presetSel.appendChild(opt0);
  for (let i = 0; i < EASINGS.length; i++) {
    const o = document.createElement('option');
    o.value = i; o.textContent = EASINGS[i][0];
    presetSel.appendChild(o);
  }
  presetSel.onchange = () => {
    if (presetSel.value === '') return;
    easingEditor.bezier = easingToBezier(parseInt(presetSel.value));
    easingEditor.apply(parseInt(presetSel.value));
    syncEasingInputs();
    drawEasingEditor();
  };
  pop.appendChild(presetSel);
  document.body.appendChild(pop);
  const r = anchor.getBoundingClientRect();
  pop.style.left = Math.min(r.left, window.innerWidth - 236) + 'px';
  const popH = pop.offsetHeight || 260;
  let top = r.bottom + 6;
  if (top + popH > window.innerHeight - 8) top = Math.max(8, r.top - popH - 6);
  pop.style.top = top + 'px';
  drawEasingEditor();
  canvas.addEventListener('pointerdown', onEasingPointerDown);
  canvas.addEventListener('pointermove', onEasingPointerMove);
  canvas.addEventListener('pointerup', () => { if (easingEditor) { easingEditor.dragging = -1; refreshParticleTree(); } });
  setTimeout(() => document.addEventListener('pointerdown', onEasingDocPointerDown), 0);
}

function syncEasingInputs() {
  if (!easingEditor) return;
  for (let i = 0; i < 4; i++) {
    const inp = easingEditor.inputs[i];
    if (inp && document.activeElement !== inp) inp.value = easingEditor.bezier[i].toFixed(3);
  }
}

function onEasingDocPointerDown(e) {
  if (easingEditor && !e.target.closest('#easing-editor')) { closeEasingEditor(); refreshParticleTree(); }
}

function closeEasingEditor() {
  easingEditor = null;
  const pop = document.getElementById('easing-editor');
  if (pop) pop.remove();
  document.removeEventListener('pointerdown', onEasingDocPointerDown);
}

function cubicBezierX(t, x1, x2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  return ((ax * t + bx) * t + cx) * t;
}

function drawEasingEditor() {
  const pop = document.getElementById('easing-editor');
  if (!pop || !easingEditor) return;
  const [x1, y1, x2, y2] = easingEditor.bezier;
  syncEasingInputs();
  const canvas = pop.querySelector('.ee-canvas');
  const ctx = canvas.getContext('2d');
  const w = canvas.width, h = canvas.height, pad = 12;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = '#171a20';
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = '#323848';
  ctx.strokeRect(pad, pad, w - 2 * pad, h - 2 * pad);
  const px = (x) => pad + Math.min(1, Math.max(0, x)) * (w - 2 * pad);
  const py = (y) => pad + (1 - Math.min(1, Math.max(0, y))) * (h - 2 * pad);
  ctx.strokeStyle = '#5b9dff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let i = 0; i <= 48; i++) {
    const t = i / 48;
    const bx = pad + cubicBezierX(t, x1, x2) * (w - 2 * pad);
    const by = pad + (1 - cubicBezier(t, x1, y1, x2, y2)) * (h - 2 * pad);
    if (i === 0) ctx.moveTo(bx, by); else ctx.lineTo(bx, by);
  }
  ctx.stroke();
  ctx.strokeStyle = '#4a5568';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(pad, h - pad); ctx.lineTo(px(x1), py(y1));
  ctx.moveTo(w - pad, pad); ctx.lineTo(px(x2), py(y2));
  ctx.stroke();
  drawPoint(ctx, px(x1), py(y1), '#ff6b6b');
  drawPoint(ctx, px(x2), py(y2), '#6ba7ff');
}

function drawPoint(ctx, x, y, color) {
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, 6, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 1.5;
  ctx.stroke();
}

function onEasingPointerDown(e) {
  if (!easingEditor) return;
  const canvas = document.getElementById('easing-editor').querySelector('.ee-canvas');
  const rect = canvas.getBoundingClientRect();
  const w = canvas.width, h = canvas.height, pad = 12;
  const mx = (e.clientX - rect.left - pad) / (w - 2 * pad);
  const my = 1 - (e.clientY - rect.top - pad) / (h - 2 * pad);
  const [x1, y1, x2, y2] = easingEditor.bezier;
  const d1 = Math.hypot(mx - x1, my - y1);
  const d2 = Math.hypot(mx - x2, my - y2);
  easingEditor.dragging = d1 < d2 ? 0 : 1;
  canvas.setPointerCapture(e.pointerId);
}

function onEasingPointerMove(e) {
  if (!easingEditor || easingEditor.dragging < 0) return;
  const canvas = document.getElementById('easing-editor').querySelector('.ee-canvas');
  const rect = canvas.getBoundingClientRect();
  const w = canvas.width, h = canvas.height, pad = 12;
  let mx = (e.clientX - rect.left - pad) / (w - 2 * pad);
  let my = 1 - (e.clientY - rect.top - pad) / (h - 2 * pad);
  mx = Math.min(1, Math.max(0, mx));
  my = Math.min(1, Math.max(0, my));
  const b = easingEditor.bezier;
  if (easingEditor.dragging === 0) { b[0] = mx; b[1] = my; }
  else { b[2] = mx; b[3] = my; }
  drawEasingEditor();
  easingEditor.apply(b.slice());
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
    pushUndo();
    const startIndex = state.particles.length;
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
        if (posKf.length) state.tracks.push({ pr: 'pos', m: 'set', ids: [p.id], kf: [[0, pos.slice(), state.defaultEasing], ...posKf] });
        if (colKf.length) state.tracks.push({ pr: 'col', m: 'set', ids: [p.id], kf: [[0, color.slice(), state.defaultEasing], ...colKf] });
        if (sclKf.length) state.tracks.push({ pr: 'scl', m: 'set', ids: [p.id], kf: [[0, [scale], state.defaultEasing], ...sclKf] });
      }
    }
  } catch (e) { alert('表达式错误：' + e.message); return; }
  autoGroup(state.particles.slice(startIndex).map(p => p.id));
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
  pushUndo();
  const startIndex = state.particles.length;
  for (let i = 0; i < count; i++) {
    const t = (i / count) * (Math.PI * 2 / omega);
    const val = { X: constant, Y: constant, Z: constant };
    for (const axis of plane.axes) val[axis] = evalFourier(axis, omega, t);
    addParticle({ pos: [val.X, val.Y, val.Z] });
  }
  autoGroup(state.particles.slice(startIndex).map(p => p.id));
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 属性面板
 * ======================================================================= */

function updatePropPanel() {
  const sel = currentSelected();
  if (sel.length === 0) return;
  const first = sel[0];
  const same = (fn) => sel.every(q => fn(q) === fn(first));
  const styleSel = document.getElementById('prop-style');
  const styleSame = same(q => q.style);
  let mixedOpt = styleSel.querySelector('option[value="__mixed__"]');
  if (!styleSame) {
    if (!mixedOpt) { mixedOpt = document.createElement('option'); mixedOpt.value = '__mixed__'; mixedOpt.textContent = '-'; styleSel.appendChild(mixedOpt); }
    styleSel.value = '__mixed__';
  } else {
    styleSel.value = first.style;
  }

  const colorSame = same(q => q.color[0] + ',' + q.color[1] + ',' + q.color[2]);
  document.getElementById('prop-color').value = colorSame ? rgbToHex(first.color[0], first.color[1], first.color[2]) : '#808080';

  const aSame = same(q => q.color[3]);
  const aInput = document.getElementById('prop-alpha');
  if (aSame) { aInput.value = first.color[3]; document.getElementById('alpha-val').textContent = first.color[3].toFixed(2); }
  else { aInput.value = 0.5; document.getElementById('alpha-val').textContent = '-'; }

  const sSame = same(q => q.scale);
  const sInput = document.getElementById('prop-scale');
  sInput.value = sSame ? first.scale : '';
  sInput.placeholder = sSame ? '' : '-';

  const gSame = same(q => q.glow);
  const gInput = document.getElementById('prop-glow');
  gInput.checked = gSame ? first.glow : false;
  gInput.indeterminate = !gSame;

  const lSame = same(q => q.lightLevel);
  const lInput = document.getElementById('prop-light');
  if (lSame) { lInput.value = first.lightLevel; document.getElementById('light-val').textContent = first.lightLevel; }
  else { lInput.value = 0; document.getElementById('light-val').textContent = '-'; }

  const pos = currentVisual(first).pos;
  const setPos = (id, val, sameVal) => { const el = document.getElementById(id); el.value = sameVal ? val : ''; el.placeholder = sameVal ? '' : '-'; };
  const xSame = same(q => currentVisual(q).pos[0].toFixed(2) === pos[0].toFixed(2));
  const ySame = same(q => currentVisual(q).pos[1].toFixed(2) === pos[1].toFixed(2));
  const zSame = same(q => currentVisual(q).pos[2].toFixed(2) === pos[2].toFixed(2));
  setPos('prop-posx', pos[0].toFixed(2), xSame);
  setPos('prop-posy', pos[1].toFixed(2), ySame);
  setPos('prop-posz', pos[2].toFixed(2), zSame);
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

const TL_PX_PER_TICK = 4;
let timelineViewStart = -25;

function drawTimeline() {
  const canvas = document.getElementById('timeline');
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const pxPerTick = TL_PX_PER_TICK;
  const viewEnd = timelineViewStart + w / pxPerTick;
  ctx.fillStyle = '#1f222a'; ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke();
  const step = niceStep(viewEnd - timelineViewStart);
  ctx.fillStyle = '#9aa0ad'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top';
  for (let t = Math.floor(timelineViewStart / step) * step; t <= viewEnd; t += step) {
    if (t < 0) continue;
    const x = (t - timelineViewStart) * pxPerTick;
    ctx.fillText(Math.round(t), x + 2, 2);
    ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(x, h / 2 - 6); ctx.lineTo(x, h / 2 + 6); ctx.stroke();
  }
  const phx = (state.time - timelineViewStart) * pxPerTick;
  ctx.strokeStyle = '#ffcc55'; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(phx, 0); ctx.lineTo(phx, h); ctx.stroke();
  ctx.fillStyle = '#ffcc55'; ctx.beginPath(); ctx.moveTo(phx - 5, 0); ctx.lineTo(phx + 5, 0); ctx.lineTo(phx, 8); ctx.closePath(); ctx.fill();
}

function niceStep(range) {
  const rough = Math.max(1, range / 10);
  const pow = Math.pow(10, Math.floor(Math.log10(rough)));
  const norm = rough / pow;
  return (norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10) * pow;
}

function timelineXToTick(clientX) {
  const canvas = document.getElementById('timeline');
  const rect = canvas.getBoundingClientRect();
  return timelineViewStart + (clientX - rect.left) / TL_PX_PER_TICK;
}

/* =========================================================================
 * 导出 / 导入 / 文件
 * ======================================================================= */

const r3 = x => Math.round(x * 1000) / 1000;
const roundArr = a => a.map(r3);
function encodeEasing(e) { return Array.isArray(e) ? e.map(r3) : e; }

function exportJSON() {
  const p = state.particles.map(pt => ({ id: pt.id, s: pt.style, c: roundArr(pt.color), sc: r3(pt.scale), g: pt.glow ? 1 : 0, l: pt.lightLevel, pos: roundArr(pt.pos), vel: roundArr(pt.vel || [0, 0, 0]) }));
  const t = state.tracks.map(tr => {
    const o = { pr: tr.pr, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], roundArr(k[1]), encodeEasing(k[2])]) };
    if (tr.m === 'op') o.m = 'op';
    return o;
  });
  const g = {};
  for (const [name, members] of Object.entries(state.groups)) if (members.length) g[name] = members.slice();
  return { v: 1, loop: state.loop, g, p, t };
}

function importJSON(obj) {
  pushUndo();
  state.particles = (obj.p || []).map(pt => ({
    id: pt.id || nextId(), style: STYLES.includes(pt.s) ? pt.s : 'DOT',
    color: (pt.c || [1, 1, 1, 1]).slice(0, 4), scale: pt.sc != null ? pt.sc : 1,
    glow: !!pt.g, lightLevel: pt.l || 0, pos: (pt.pos || [0, 0, 0]).slice(0, 3),
    vel: (pt.vel || [0, 0, 0]).slice(0, 3),
  }));
  state.groups = {};
  for (const [name, members] of Object.entries(obj.g || {})) state.groups[name] = members.slice();
  state.tracks = (obj.t || []).map(tr => ({
    pr: ['pos', 'rot', 'vel', 'col', 'scl'].includes(tr.pr) ? tr.pr : 'pos',
    m: tr.m === 'op' ? 'op' : 'set',
    ids: (tr.ids || []).slice(),
    kf: (tr.kf || []).map(k => [k[0], k[1].slice(), Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
  }));
  state.loop = !!obj.loop;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  state.selected.clear(); state.selectedGroup = null; state.time = 0;
  state.expandedParticles.clear(); state.expandedProps.clear();
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  state.dirty = false;
}

function download(json, filename) {
  const blob = new Blob([json], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

// 从 File 对象载入动画（用于文件选择与拖拽打开）
async function loadFile(file) {
  const text = await file.text();
  importJSON(JSON.parse(text));
  state.name = file.name.replace(/\.json$/i, '');
  state.dirty = false;
  updateTopbarTitle();
}

function updateTopbarTitle() {
  const el = document.getElementById('topbar-title');
  if (el) el.textContent = state.name + '.json';
}

async function openFile() {
  if (!confirmDiscardChanges()) return;
  if (window.showOpenFilePicker) {
    try {
      const [h] = await window.showOpenFilePicker({ types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      await loadFile(await h.getFile());
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  document.getElementById('file-import').click();
}

async function saveFile() {
  if (!state.fileHandle || !state.fileHandle.createWritable) {
    await saveFileAs();
    return;
  }
  const json = JSON.stringify(exportJSON());
  try {
    const w = await state.fileHandle.createWritable();
    await w.write(json); await w.close();
    state.dirty = false;
  } catch (e) {
    await saveFileAs();
  }
}

async function saveFileAs() {
  const json = JSON.stringify(exportJSON());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.json', types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      const w = await h.createWritable();
      await w.write(json); await w.close();
      state.dirty = false;
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  download(json, (state.name || 'my_animation') + '.json');
  state.dirty = false;
}

// 新建空白动画
function newFile() {
  if (!confirmDiscardChanges()) return;
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {};
  state.selected.clear(); state.selectedGroup = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0;
  state.name = 'my_animation';
  state.fileHandle = null;
  state.loop = true;
  document.getElementById('tl-loop').checked = true;
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  state.dirty = false;
  updateTopbarTitle();
}

// 若有未保存更改，弹出保存确认。返回是否继续操作。
function confirmDiscardChanges() {
  if (!state.dirty) return true;
  const r = confirm('有未保存的更改，是否保存？\n\n「确定」= 保存后继续\n「取消」= 不保存直接继续');
  if (r) saveFile();
  return true;
}

/* =========================================================================
 * UI 初始化与主循环
 * ======================================================================= */

function updateTimeUI() {
  document.getElementById('tl-time').value = Math.round(state.time);
  document.getElementById('tl-max').textContent = maxTick();
}

function updateLoopIndicator() {
  const el = document.getElementById('loop-indicator');
  if (el) el.style.opacity = state.loop ? '1' : '0.25';
}

function togglePlay() {
  state.playing = !state.playing;
  document.getElementById('btn-play').textContent = state.playing ? '⏸ 暂停' : '▶ 播放';
}

let shiftHeld = false;
window.addEventListener('keydown', (e) => { if (e.key === 'Shift') shiftHeld = true; });
window.addEventListener('keyup', (e) => { if (e.key === 'Shift') shiftHeld = false; });
evShift = () => shiftHeld;

function initUI() {
  const styleSel = document.getElementById('prop-style');
  STYLES.forEach(s => { const o = document.createElement('option'); o.value = s; o.textContent = s; styleSel.appendChild(o); });
  const tlEase = document.getElementById('tl-easing');
  tlEase.innerHTML = easingCurveSVG(state.defaultEasing);
  tlEase.onclick = () => openEasingEditor(state.defaultEasing, (e) => {
    state.defaultEasing = e;
    tlEase.innerHTML = easingCurveSVG(e);
  }, tlEase);

  // 菜单（悬停展开）
  document.querySelectorAll('.menu').forEach(menu => {
    menu.addEventListener('mouseenter', () => {
      document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
      menu.classList.add('open');
    });
    menu.addEventListener('mouseleave', () => menu.classList.remove('open'));
  });
  const closeMenus = () => document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
  document.getElementById('btn-new').addEventListener('click', () => { closeMenus(); newFile(); });
  document.getElementById('btn-open').addEventListener('click', () => { closeMenus(); openFile(); });
  document.getElementById('btn-save').addEventListener('click', () => { closeMenus(); saveFile(); });
  document.getElementById('btn-saveas').addEventListener('click', () => { closeMenus(); saveFileAs(); });
  document.getElementById('btn-clear').addEventListener('click', () => { closeMenus(); clearAll(); });
  document.getElementById('btn-undo').addEventListener('click', () => { closeMenus(); undo(); });
  document.getElementById('btn-redo').addEventListener('click', () => { closeMenus(); redo(); });
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
  document.getElementById('draw-plane').addEventListener('change', (ev) => { state.drawPlane = ev.target.value; triggerDrawPlanePulse(); });

  // 函数 / 傅里叶 / 变量
  document.getElementById('btn-var-add').addEventListener('click', () => addVarRow());
  document.getElementById('btn-fn').addEventListener('click', generateFunction);
  document.getElementById('btn-four').addEventListener('click', generateFourier);
  document.getElementById('four-plane').addEventListener('change', renderFourierInputs);

  // 属性
  document.getElementById('prop-style').addEventListener('change', (ev) => { if (ev.target.value === '__mixed__') return; pushUndo(); currentSelected().forEach(p => { p.style = ev.target.value; }); rebuildPoints(); });
  document.getElementById('prop-glow').addEventListener('change', (ev) => { pushUndo(); currentSelected().forEach(p => { p.glow = ev.target.checked; }); rebuildPoints(); });
  document.getElementById('prop-light').addEventListener('input', (ev) => { beginContinuous(); document.getElementById('light-val').textContent = ev.target.value; currentSelected().forEach(p => { p.lightLevel = parseInt(ev.target.value); }); rebuildPoints(); });
  document.getElementById('prop-light').addEventListener('change', endContinuous);
  document.getElementById('prop-alpha').addEventListener('input', (ev) => { beginContinuous(); document.getElementById('alpha-val').textContent = parseFloat(ev.target.value).toFixed(2); applyColorFromInputs(); });
  document.getElementById('prop-alpha').addEventListener('change', endContinuous);
  document.getElementById('prop-color').addEventListener('input', (ev) => { beginContinuous(); applyColorFromInputs(); });
  document.getElementById('prop-color').addEventListener('change', endContinuous);
  document.getElementById('prop-scale').addEventListener('input', (ev) => { beginContinuous(); editBaseValue([...state.selected], 'scl', [parseFloat(ev.target.value) || 1]); rebuildPoints(); });
  document.getElementById('prop-scale').addEventListener('change', endContinuous);
  ['prop-posx', 'prop-posy', 'prop-posz'].forEach(id => {
    document.getElementById(id).addEventListener('input', (ev) => { beginContinuous(); applyPositionFromInputs(); });
    document.getElementById(id).addEventListener('change', endContinuous);
  });

  // 时间轴
  document.getElementById('btn-play').addEventListener('click', togglePlay);
  document.getElementById('tl-speed').addEventListener('change', (ev) => { state.playSpeed = Math.max(0.1, parseFloat(ev.target.value) || 1); });
  document.getElementById('tl-time').addEventListener('input', (ev) => { state.time = parseFloat(ev.target.value) || 0; updateTimeUI(); rebuildPoints(); });
  document.getElementById('tl-loop').addEventListener('change', (ev) => { state.loop = ev.target.checked; updateLoopIndicator(); });
  document.getElementById('btn-capture').addEventListener('click', captureKeyframes);

  // 文件导入
  document.getElementById('file-import').addEventListener('change', (ev) => {
    const f = ev.target.files[0];
    if (!f) return;
    state.fileHandle = null;
    loadFile(f);
    ev.target.value = '';
  });

  // 时间轴点击/拖动
  const tlCanvas = document.getElementById('timeline');
  let tlDrag = null; // { mode: 'scrub' | 'pan', lastX }
  tlCanvas.addEventListener('pointerdown', (ev) => {
    if (ev.button !== 0 && ev.button !== 1) return;
    ev.preventDefault();
    tlCanvas.setPointerCapture(ev.pointerId);
    if (ev.button === 1) { // 中键：平移视图
      tlDrag = { mode: 'pan', lastX: ev.clientX };
    } else {
      tlDrag = { mode: 'scrub', lastX: ev.clientX };
      state.time = Math.max(0, timelineXToTick(ev.clientX));
      updateTimeUI();
      rebuildPoints();
    }
  });
  tlCanvas.addEventListener('pointermove', (ev) => {
    if (!tlDrag) return;
    if (tlDrag.mode === 'pan') {
      timelineViewStart -= (ev.clientX - tlDrag.lastX) / TL_PX_PER_TICK;
      timelineViewStart = Math.max(-25, timelineViewStart);
    } else {
      state.time = Math.max(0, timelineXToTick(ev.clientX));
      updateTimeUI();
    }
    tlDrag.lastX = ev.clientX;
    drawTimeline();
    if (tlDrag.mode === 'scrub') rebuildPoints();
  });
  tlCanvas.addEventListener('pointerup', () => { tlDrag = null; });
  tlCanvas.addEventListener('pointerleave', () => { tlDrag = null; });
  tlCanvas.addEventListener('wheel', (ev) => {
    ev.preventDefault();
    timelineViewStart += ev.deltaY / TL_PX_PER_TICK;
    timelineViewStart = Math.max(-25, timelineViewStart);
    drawTimeline();
  }, { passive: false });

  renderFourierInputs();
  addVarRow('speed', '0.2');
  rebuildPoints();
  refreshParticleTree();
}

function clearAll() {
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {};
  state.selected.clear(); state.selectedGroup = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0;
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
}

function applyColorFromInputs() {
  const rgb = hexToRgb(document.getElementById('prop-color').value);
  const a = parseFloat(document.getElementById('prop-alpha').value);
  editBaseValue([...state.selected], 'col', [rgb[0], rgb[1], rgb[2], a]);
  rebuildPoints();
}

function applyPositionFromInputs() {
  const x = parseFloat(document.getElementById('prop-posx').value);
  const y = parseFloat(document.getElementById('prop-posy').value);
  const z = parseFloat(document.getElementById('prop-posz').value);
  if ([x, y, z].some(isNaN)) return;
  editBaseValue([...state.selected], 'pos', [x, y, z]);
  rebuildPoints();
}

/* 左侧面板拖拽缩放 + 拖拽移出组 */
(function setupPanelResizeAndDrop() {
  const tree = document.getElementById('particle-tree');
  tree.addEventListener('dragover', (e) => { if (dragIds) { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; } });
  tree.addEventListener('drop', (e) => {
    if (dragIds && !e.target.closest('.ptree-head.group')) {
      e.preventDefault();
      removeParticlesFromGroups(dragIds);
    }
    dragIds = null;
  });
  tree.addEventListener('contextmenu', (e) => {
    if (e.target.closest('.ptree-head')) return;
    e.preventDefault();
    showContextMenu(e.clientX, e.clientY, [
      { label: '添加粒子', action: () => { pushUndo(); addParticle({}); rebuildPoints(); refreshParticleTree(); } },
    ]);
  });

  const handle = document.getElementById('resize-handle');
  let resizing = false;
  handle.addEventListener('pointerdown', (e) => {
    resizing = true;
    handle.classList.add('dragging');
    handle.setPointerCapture(e.pointerId);
  });
  handle.addEventListener('pointermove', (e) => {
    if (!resizing) return;
    const layout = document.querySelector('.layout');
    const rect = layout.getBoundingClientRect();
    let w = e.clientX - rect.left;
    w = Math.max(220, Math.min(600, w));
    layout.style.setProperty('--left-w', w + 'px');
    resize();
  });
  handle.addEventListener('pointerup', () => { resizing = false; handle.classList.remove('dragging'); });
})();

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
    const dir = slerp(camTransition.startDir, camTransition.endDir, e);
    camera.position.copy(camTransition.target).addScaledVector(dir, camTransition.dist);
    camera.up.lerpVectors(camTransition.startUp, camTransition.endUp, e).normalize();
    camera.lookAt(camTransition.target);
    if (t >= 1) camTransition = null;
    controls.update();
  }

  if (planePulse) {
    const t = (now - planePulse.t0) / planePulse.dur;
    if (t >= 1) {
      scene.remove(planePulse.mesh);
      planePulse.mesh.geometry.dispose();
      planePulse.mesh.material.dispose();
      planePulse = null;
    } else {
      planePulse.mesh.scale.setScalar(0.5 + t * 40);
      planePulse.mesh.material.opacity = 0.55 * (1 - t);
    }
  }

  if (state.playing) {
    state.time += dt * 20 * state.playSpeed;
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

// 关闭页面前若未保存则提示
window.addEventListener('beforeunload', (ev) => {
  if (state.dirty) {
    ev.preventDefault();
    ev.returnValue = '';
  }
});

// 拖拽文件到窗口即可打开
(function setupDragDrop() {
  window.addEventListener('dragover', (ev) => { ev.preventDefault(); });
  window.addEventListener('drop', (ev) => {
    ev.preventDefault();
    const file = ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files[0];
    if (!file) return;
    if (!confirmDiscardChanges()) return;
    state.fileHandle = null;
    loadFile(file);
  });
})();
