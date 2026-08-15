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
const gizmoRingWorlds = { X: [], Y: [], Z: [] };

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
    gizmoRingWorlds[def.key] = world;
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
  let best = null, bestD = 11, bestFront = false;
  for (const [key, poly] of Object.entries(gizmoRingPolys)) {
    for (let i = 0; i < poly.length - 1; i++) {
      const d = distToSegment(px, py, poly[i][0], poly[i][1], poly[i + 1][0], poly[i + 1][1]);
      if (d < bestD) {
        bestD = d; best = key;
        const world = gizmoRingWorlds[key];
        bestFront = world && (world[i].z + world[i + 1].z) / 2 < 0;
      }
    }
  }
  return best ? { key: best, front: bestFront } : null;
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
  const hit = hitAxisRing(px, py);
  if (hit) {
    gizmoDrag = { mode: 'axis', axis: hit.key, front: hit.front, lastX: px, lastY: py, moved: false };
  } else {
    gizmoDrag = { mode: 'free', x: ev.clientX, y: ev.clientY, moved: false };
  }
});

gizmoCanvas.addEventListener('pointermove', (ev) => {
  const rect = gizmoCanvas.getBoundingClientRect();
  const px = (ev.clientX - rect.left) * (GIZMO_SIZE / rect.width);
  const py = (ev.clientY - rect.top) * (GIZMO_SIZE / rect.height);
  if (!gizmoDrag) {
    const hit = hitAxisRing(px, py);
    gizmoHoverAxis = hit ? hit.key : null;
    gizmoCanvas.style.cursor = gizmoHoverAxis ? 'pointer' : 'default';
    return;
  }
  if (gizmoDrag.mode === 'free') {
    const dx = ev.clientX - gizmoDrag.x, dy = ev.clientY - gizmoDrag.y;
    gizmoDrag.x = ev.clientX; gizmoDrag.y = ev.clientY;
    if (Math.abs(dx) + Math.abs(dy) > 2) gizmoDrag.moved = true;
    orbitCamera(dx, dy);
  } else {
    const dx = px - gizmoDrag.lastX, dy = py - gizmoDrag.lastY;
    const rx = px - GIZMO_CENTER, ry = py - GIZMO_CENTER;
    const rlen = Math.hypot(rx, ry);
    if (rlen > 0.01) {
      const tx = -ry / rlen, ty = rx / rlen;
      const angle = (dx * tx + dy * ty) / rlen;
      if (Math.abs(dx) + Math.abs(dy) > 0.5) gizmoDrag.moved = true;
      orbitAroundAxis(gizmoDrag.axis, angle);
    }
    gizmoDrag.lastX = px; gizmoDrag.lastY = py;
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
    if (def) orientToAxis(info.front ? def.axis : def.axis.clone().negate());
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
