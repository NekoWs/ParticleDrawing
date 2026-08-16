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
const axesHelper = new THREE.AxesHelper(4);
scene.add(axesHelper);
// 轴线发光脉冲：备份原始颜色 + 各轴顶点索引（AxesHelper 顶点顺序固定：X=0,1 / Y=2,3 / Z=4,5）
const axesColorAttr = axesHelper.geometry.attributes.color;
const axesBaseColors = axesColorAttr.array.slice();
const AXIS_VERTEX_INDEX = { X: [0, 1], Y: [2, 3], Z: [4, 5] };

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
      new THREE.TorusGeometry(0.5, 0.012, 32, 128),
      new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: false, transparent: true, side: THREE.DoubleSide })
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
  const offset = 0.3; // 箭头起点距中心的距离
  const shaftLen = 0.9, shaftR = 0.008, headH = 0.16, headR = 0.05;
  for (const [axis, [x, y, z, color]] of Object.entries(defs)) {
    const dir = new THREE.Vector3(x, y, z);
    const group = new THREE.Group();
    group.name = axis;
    const shaft = new THREE.Mesh(new THREE.CylinderGeometry(shaftR, shaftR, shaftLen, 10), new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: false, transparent: true }));
    shaft.position.y = offset + shaftLen / 2;
    const head = new THREE.Mesh(new THREE.ConeGeometry(headR, headH, 14), new THREE.MeshBasicMaterial({ color, depthWrite: false, depthTest: false, transparent: true }));
    head.position.y = offset + shaftLen + headH / 2;
    group.add(shaft); group.add(head);
    group.quaternion.setFromUnitVectors(up, dir);
    shaft.renderOrder = GIZMO_ARROW_RENDER_ORDER;
    head.renderOrder = GIZMO_ARROW_RENDER_ORDER;
    gizmoArrows[axis] = { group, shaft, head };
    gizmoGroup.add(group);
  }
})();

let camTransition = null;
let planePulse = null; // 绘制平面切换时的轴线发光动画 { axis, t0, dur }
