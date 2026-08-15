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
function getParticle(id) { return state.particles.find(p => p.id === id); }
function findTrack(prop, id) { return state.tracks.find(tr => tr.pr === prop && tr.ids.length === 1 && tr.ids[0] === id); }

function nextFreeTime(tr, startTime) {
  let t = Math.max(0, Math.round(startTime));
  while (tr.kf.some(k => k[0] === t)) t += 5;
  return t;
}
