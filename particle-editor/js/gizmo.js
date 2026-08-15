/* =========================================================================
 * 吸附
 * ======================================================================= */

function snapValue(v) {
  return Math.round(v / SNAP_STEP) * SNAP_STEP;
}

function snapGrid(v) {
  return shiftHeld ? snapValue(v) : v;
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
  setGizmoHover(null, null);
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

// 绘制平面切换时：让该平面内包含的两条轴线颜色发光一下
function triggerDrawPlanePulse() {
  if (planePulse) restoreAxisColors();
  const def = PLANES[state.drawPlane] || PLANES.XZ;
  planePulse = { axes: def.axes, t0: performance.now(), dur: 400 };
}

function setAxisGlow(axes, glow) {
  const arr = axesColorAttr.array;
  for (const axis of axes) {
    for (const vi of AXIS_VERTEX_INDEX[axis]) {
      const i = vi * 3;
      arr[i]     = axesBaseColors[i]     + (1 - axesBaseColors[i])     * glow;
      arr[i + 1] = axesBaseColors[i + 1] + (1 - axesBaseColors[i + 1]) * glow;
      arr[i + 2] = axesBaseColors[i + 2] + (1 - axesBaseColors[i + 2]) * glow;
    }
  }
  axesColorAttr.needsUpdate = true;
}

function restoreAxisColors() {
  axesColorAttr.array.set(axesBaseColors);
  axesColorAttr.needsUpdate = true;
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
  const su0 = shiftHeld ? snapValue(u0) : u0;
  const sv0 = shiftHeld ? snapValue(v0) : v0;
  const sU0 = su0, sV0 = sv0;
  const sU1 = u1 + (su0 - u0), sV1 = v1 + (sv0 - v0);
  const push = (u, v) => { const [x, y, z] = toWorld(u, v, off); out.push([x, y, z]); };
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
