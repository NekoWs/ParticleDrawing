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
const DEG2RAD = Math.PI / 180;
const RAD2DEG = 180 / Math.PI;
const ROT_SNAP = 45; // 按住 Shift 时旋转吸附的步长（角度）
const PARTICLE_SIZE_FACTOR = 0.5; // 编辑器渲染缩放（与游戏内 quad 的可见点大小一致）

// 组的属性（轨道级）
const GROUP_PROP_DEFS = [
  { key: 'pos', label: '位置', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'rot', label: '旋转', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'vel', label: '速度', size: 3, labels: ['X', 'Y', 'Z'] },
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
  functions: [],
  selectedFunction: null,
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
  captureKeyframes: false,
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
function nextFunctionId() {
  let n = 0;
  while (state.functions.some(f => f.id === 'fx' + n)) n++;
  return 'fx' + n;
}
function getFunction(id) { return state.functions.find(f => f.id === id); }
// 粒子是否由函数对象派生（基础属性只读）
function isDerivedParticle(p) { return p != null && !!p.fx; }
function getParticle(id) { return state.particles.find(p => p.id === id); }
function findTrack(prop, id) { return state.tracks.find(tr => tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === id); }

function nextFreeTime(tr, startTime) {
  let t = Math.max(0, Math.round(startTime));
  while (tr.kf.some(k => k[0] === t)) t += 5;
  return t;
}

/* =========================================================================
 * 函数对象：预设形状模板（参数面板 + 公式视图）
 * 内置变量：i=粒子序号、n=采样数、t=时间（vars 内不可重名）
 * ======================================================================= */

const FUNCTION_PRESETS = {
  sphere: {
    label: '球体',
    params: [
      { key: 'rad', label: '半径', def: 3 },
    ],
    build: p => ({
      count: 200,
      vars: { rad: { expr: String(p.rad), kf: [] } },
      code: 'th = acos(1-2*(i+0.5)/n);\nph = i*pi*(3-sqrt(5));\n[x,y,z] = [rad*sin(th)*cos(ph), rad*cos(th), rad*sin(th)*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.3;\nglow = 1;\nlight = 12',
    }),
  },
  cube: {
    label: '立方体',
    params: [
      { key: 'edge', label: '边长', def: 4 },
    ],
    build: p => ({
      count: 512,
      vars: { edge: { expr: String(p.edge), kf: [] } },
      code: '[x,y,z] = [((floor(i/(n*n)))/(n-1)-0.5)*edge, ((floor((i%(n*n))/n))/(n-1)-0.5)*edge, ((i%(n*n)%n)/(n-1)-0.5)*edge];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.25;\nglow = 0;\nlight = 0',
    }),
  },
  torus: {
    label: '圆环/环面',
    countVars: ['m', 'k'],
    params: [
      { key: 'major', label: '大半径', def: 3 },
      { key: 'minor', label: '管半径', def: 1 },
    ],
    build: p => ({
      count: 288,
      vars: { major: { expr: String(p.major), kf: [] }, minor: { expr: String(p.minor), kf: [] }, m: { expr: '24', kf: [] }, k: { expr: '12', kf: [] } },
      code: 'th = (i%k)/k*2*pi;\nph = floor(i/k)/m*2*pi;\n[x,y,z] = [(major+minor*cos(th))*cos(ph), minor*sin(th), (major+minor*cos(th))*sin(ph)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.2;\nglow = 1;\nlight = 10',
    }),
  },
  cylinder: {
    label: '圆柱',
    countVars: ['m', 'k'],
    params: [
      { key: 'rad', label: '半径', def: 2 },
      { key: 'h', label: '高度', def: 4 },
    ],
    build: p => ({
      count: 256,
      vars: { rad: { expr: String(p.rad), kf: [] }, h: { expr: String(p.h), kf: [] }, m: { expr: '32', kf: [] }, k: { expr: '8', kf: [] } },
      code: 'aa = (i%m)/m*2*pi;\nyy = floor(i/m)/(k-1);\n[x,y,z] = [rad*cos(aa), (yy-0.5)*h, rad*sin(aa)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.25;\nglow = 0;\nlight = 0',
    }),
  },
  cone: {
    label: '圆锥',
    countVars: ['m', 'k'],
    params: [
      { key: 'rad', label: '底面半径', def: 2 },
      { key: 'h', label: '高度', def: 4 },
    ],
    build: p => ({
      count: 256,
      vars: { rad: { expr: String(p.rad), kf: [] }, h: { expr: String(p.h), kf: [] }, m: { expr: '32', kf: [] }, k: { expr: '8', kf: [] } },
      code: 'aa = (i%m)/m*2*pi;\nyy = floor(i/m)/(k-1);\n[x,y,z] = [rad*(1-yy)*cos(aa), (yy-0.5)*h, rad*(1-yy)*sin(aa)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.25;\nglow = 0;\nlight = 0',
    }),
  },
  helix: {
    label: '螺旋线',
    countVars: ['turns', 'ppr'],
    params: [
      { key: 'rad', label: '半径', def: 2 },
      { key: 'h', label: '总高度', def: 6 },
    ],
    build: p => ({
      count: 120,
      vars: { rad: { expr: String(p.rad), kf: [] }, h: { expr: String(p.h), kf: [] }, turns: { expr: '3', kf: [] }, ppr: { expr: '40', kf: [] } },
      code: 'aa = i/ppr*2*pi;\n[x,y,z] = [rad*cos(aa), (i/n-0.5)*h, rad*sin(aa)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.3;\nglow = 1;\nlight = 8',
    }),
  },
  plane: {
    label: '平面网格',
    countVars: ['cols', 'rows'],
    params: [
      { key: 'w', label: '宽', def: 8 },
      { key: 'd', label: '深', def: 8 },
    ],
    build: p => ({
      count: 256,
      vars: { w: { expr: String(p.w), kf: [] }, d: { expr: String(p.d), kf: [] }, cols: { expr: '16', kf: [] }, rows: { expr: '16', kf: [] } },
      code: '[x,y,z] = [((i%cols)/(cols-1)-0.5)*w, 0, (floor(i/cols)/(rows-1)-0.5)*d];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.2;\nglow = 0;\nlight = 0',
    }),
  },
  circle: {
    label: '圆/圆环(2D)',
    params: [
      { key: 'outer', label: '外半径', def: 4 },
      { key: 'inner', label: '内半径(0=实心)', def: 0 },
    ],
    build: p => ({
      count: 200,
      vars: { outer: { expr: String(p.outer), kf: [] }, inner: { expr: String(p.inner), kf: [] } },
      code: 'aa = i/n*2*pi;\nrr = sqrt(inner^2 + (outer^2-inner^2)*i/n);\n[x,y,z] = [rr*cos(aa), 0, rr*sin(aa)];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.3;\nglow = 1;\nlight = 12',
    }),
  },
};
