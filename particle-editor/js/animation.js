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
