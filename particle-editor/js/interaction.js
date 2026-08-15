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
