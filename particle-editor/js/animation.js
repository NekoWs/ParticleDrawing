/* =========================================================================
 * 动画状态查询（分量级数据模型）
 * 轨道 pr：pos.x / pos.y / ... / col.a / scl（单分量 scl 无后缀），kf 值为标量。
 * ======================================================================= */

// 粒子基础分量值（无轨道）
function baseComponent(p, prop, comp) {
  if (prop === 'pos') return p.pos[COMP_INDEX[comp]];
  if (prop === 'col') return p.color[COMP_INDEX[comp]];
  if (prop === 'vel') return (p.vel || [0, 0, 0])[COMP_INDEX[comp]];
  if (prop === 'scl') return p.scale;
  return 0;
}

// 粒子完整基础向量
function baseValue(p, prop) {
  if (prop === 'pos') return p.pos.slice(0, 3);
  if (prop === 'col') return p.color.slice(0, 4);
  if (prop === 'vel') return (p.vel || [0, 0, 0]).slice(0, 3);
  if (prop === 'rot') return [0, 0, 0];
  return [p.scale];
}

// 零向量（按属性）
function zeroArray(prop) {
  if (prop === 'pos' || prop === 'rot' || prop === 'vel') return [0, 0, 0];
  if (prop === 'col') return [0, 0, 0, 0];
  return [0];
}

// 轨道插值（标量）：b[2] 缓动语义（后一关键帧控制前一段）
function trackValueAt(tr, T, fallback) {
  const kfs = tr.kf;
  if (!kfs || kfs.length === 0) return fallback;
  if (T <= kfs[0][0]) return kfs[0][1];
  if (T >= kfs[kfs.length - 1][0]) return kfs[kfs.length - 1][1];
  for (let i = 0; i < kfs.length - 1; i++) {
    const a = kfs[i], b = kfs[i + 1];
    if (T >= a[0] && T <= b[0]) {
      const dur = b[0] - a[0];
      const e = easeVal(dur === 0 ? 1 : (T - a[0]) / dur, b[2]);
      return a[1] + (b[1] - a[1]) * e;
    }
  }
  return fallback;
}

// 轨道索引缓存：大批量求值（rebuildPoints）前 buildTrackIndex 建立，O(1) 查找
let trackIndexCache = null;
let trackIndexLen = -1;
let opTracksCache = null;
function buildTrackIndex() {
  const map = new Map();
  const opTracks = [];
  for (const tr of state.tracks) {
    if (tr.ids.length === 1) map.set(tr.pr + '\u0000' + tr.ids[0], tr);
    if (tr.m === 'op') opTracks.push(tr);
  }
  trackIndexCache = map;
  trackIndexLen = state.tracks.length;
  opTracksCache = opTracks;
}

// 按 pr + id 精确查找轨道（有新鲜索引时 O(1)，否则线性搜索）
function findTrackByPr(pr, id) {
  if (trackIndexCache && trackIndexLen === state.tracks.length) {
    const tr = trackIndexCache.get(pr + '\u0000' + id);
    if (tr) return tr;
    return null;
  }
  return state.tracks.find(tr => tr.pr === pr && tr.ids.length === 1 && tr.ids[0] === id) || null;
}

// 某 id 在某分量的 set 轨道（优先级：自身 > 组 > 函数对象）
function findSetTrackFor(id, prop, comp) {
  const pr = compPr(prop, comp);
  const own = findTrackByPr(pr, id);
  if (own && own.m !== 'op') return own;
  const p = getParticle(id);
  for (const [gname, members] of Object.entries(state.groups)) {
    if (!members.includes(id)) continue;
    const tr = findTrackByPr(pr, 'g:' + gname);
    if (tr && tr.m !== 'op') return tr;
  }
  if (p && p.fx) {
    const tr = findTrackByPr(pr, 'f:' + p.fx);
    if (tr && tr.m !== 'op') return tr;
  }
  return null;
}

// 组/函数对象在某分量的 op 增量（标量累加）
function compOpDelta(p, prop, comp, T) {
  const pr = compPr(prop, comp);
  let delta = 0;
  for (const tr of (opTracksCache || state.tracks)) {
    if (tr.pr !== pr || tr.m !== 'op' || tr.kf.length === 0) continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const members = state.groups[id.slice(2)];
        if (members && members.includes(p.id)) delta += trackValueAt(tr, T, 0);
      }
      if (id.startsWith('f:') && p.fx === id.slice(2)) delta += trackValueAt(tr, T, 0);
    }
  }
  return delta;
}

// 某 id（'g:name' 或 'f:fxId'）的 rot 向量（三个分量）
function rotVectorAt(id, T) {
  return ['x', 'y', 'z'].map(c => {
    const tr = findTrackByPr('rot.' + c, id);
    return tr ? trackValueAt(tr, T, 0) : 0;
  });
}

// 组旋转信息（组件/函数对象的 rot + pivot）
function groupRotationInfo(p, T) {
  for (const [gname, members] of Object.entries(state.groups)) {
    if (!members.includes(p.id)) continue;
    const rot = rotVectorAt('g:' + gname, T);
    if (rot[0] === 0 && rot[1] === 0 && rot[2] === 0) return null;
    return { rot, pivot: groupCentroidValue(gname, 'pos') };
  }
  if (p.fx) {
    const rot = rotVectorAt('f:' + p.fx, T);
    if (rot[0] === 0 && rot[1] === 0 && rot[2] === 0) return null;
    const fx = getFunction(p.fx);
    return { rot, pivot: fx ? fx.center.slice() : [0, 0, 0] };
  }
  return null;
}

function applyGroupRotation(p, value, T) {
  const info = groupRotationInfo(p, T);
  if (!info) return value;
  const rot = info.rot;
  const pivot = info.pivot;
  let r = [value[0] - pivot[0], value[1] - pivot[1], value[2] - pivot[2]];
  r = rotateVector(r, [1, 0, 0], rot[0] * DEG2RAD);
  r = rotateVector(r, [0, 1, 0], rot[1] * DEG2RAD);
  r = rotateVector(r, [0, 0, 1], rot[2] * DEG2RAD);
  return [pivot[0] + r[0], pivot[1] + r[1], pivot[2] + r[2]];
}

// 粒子位置：set 覆盖 → 组旋转 → op 增量
function particlePosition(p, T) {
  let pos = ['x', 'y', 'z'].map(c => {
    let v = baseComponent(p, 'pos', c);
    const tr = findSetTrackFor(p.id, 'pos', c);
    if (tr && tr.kf.length > 0) v = trackValueAt(tr, T, v);
    return v;
  });
  pos = applyGroupRotation(p, pos, T);
  pos = pos.map((v, i) => v + compOpDelta(p, 'pos', ['x', 'y', 'z'][i], T));
  return pos;
}

// 粒子某分量值：基础 → set 覆盖 → op 增量
function componentValueAt(p, prop, comp, T) {
  let v = baseComponent(p, prop, comp);
  const tr = findSetTrackFor(p.id, prop, comp);
  if (tr && tr.kf.length > 0) v = trackValueAt(tr, T, v);
  v += compOpDelta(p, prop, comp, T);
  return v;
}

// 粒子某属性完整向量（分量级拼装）
function particleValueAt(p, prop, T) {
  if (prop === 'pos') return particlePosition(p, T);
  if (prop === 'col') return ['r', 'g', 'b', 'a'].map(c => componentValueAt(p, 'col', c, T));
  if (prop === 'vel') return ['x', 'y', 'z'].map(c => componentValueAt(p, 'vel', c, T));
  if (prop === 'rot') return [0, 0, 0]; // 粒子无自身 rot
  return [componentValueAt(p, 'scl', 's', T)];
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

// 播放时的速度累积位移（渲染期叠加，不改数据）
const velOffsets = new Map();
function resetVelOffsets() { velOffsets.clear(); }

/* =========================================================================
 * 渲染
 * ======================================================================= */

// 复用几何体与缓冲：仅顶点数量变化时重建，否则只更新数组内容（避免每帧 new/dispose 造成 GC 卡顿）
function setPointsGeometry(pts, positions, colors, sizes) {
  let geo = pts.geometry;
  const posAttr = geo && geo.getAttribute('position');
  if (!geo || !posAttr || posAttr.array.length !== positions.length) {
    geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(positions.length), 3));
    geo.setAttribute('aColor', new THREE.BufferAttribute(new Float32Array(colors.length), 4));
    geo.setAttribute('aSize', new THREE.BufferAttribute(new Float32Array(sizes.length), 1));
    const old = pts.geometry;
    pts.geometry = geo;
    if (old) old.dispose();
  }
  geo.getAttribute('position').array.set(positions);
  geo.getAttribute('aColor').array.set(colors);
  geo.getAttribute('aSize').array.set(sizes);
  geo.getAttribute('position').needsUpdate = true;
  geo.getAttribute('aColor').needsUpdate = true;
  geo.getAttribute('aSize').needsUpdate = true;
  geo.setDrawRange(0, positions.length / 3);
}

let rpPos = null, rpCol = null, rpSize = null, rpSelPos = null, rpSelCol = null, rpSelSize = null;
function rebuildPoints() {
  buildTrackIndex();
  const n = state.particles.length;
  if (!rpPos || rpPos.length !== n * 3) rpPos = new Float32Array(n * 3);
  if (!rpCol || rpCol.length !== n * 4) rpCol = new Float32Array(n * 4);
  if (!rpSize || rpSize.length !== n) rpSize = new Float32Array(n);
  const positions = rpPos, colors = rpCol, sizes = rpSize;
  for (let i = 0; i < n; i++) {
    const p = state.particles[i];
    const v = currentVisual(p);
    const off = velOffsets.get(p.id) || [0, 0, 0];
    positions[i * 3] = v.pos[0] + off[0]; positions[i * 3 + 1] = v.pos[1] + off[1]; positions[i * 3 + 2] = v.pos[2] + off[2];
    colors[i * 4] = v.color[0]; colors[i * 4 + 1] = v.color[1]; colors[i * 4 + 2] = v.color[2]; colors[i * 4 + 3] = v.color[3];
    sizes[i] = Math.max(0.02, v.scale * PARTICLE_SIZE_FACTOR);
  }
  setPointsGeometry(points, positions, colors, sizes);

  const sel = state.particles.filter(p => state.selected.has(p.id));
  if (!rpSelPos || rpSelPos.length !== sel.length * 3) rpSelPos = new Float32Array(sel.length * 3);
  if (!rpSelCol || rpSelCol.length !== sel.length * 4) rpSelCol = new Float32Array(sel.length * 4);
  if (!rpSelSize || rpSelSize.length !== sel.length) rpSelSize = new Float32Array(sel.length);
  const spos = rpSelPos, ssiz = rpSelSize;
  for (let i = 0; i < sel.length; i++) {
    const v = currentVisual(sel[i]);
    const off = velOffsets.get(sel[i].id) || [0, 0, 0];
    spos[i * 3] = v.pos[0] + off[0]; spos[i * 3 + 1] = v.pos[1] + off[1]; spos[i * 3 + 2] = v.pos[2] + off[2];
    ssiz[i] = Math.max(0.02, v.scale * PARTICLE_SIZE_FACTOR);
  }
  setPointsGeometry(selectedPoints, spos, rpSelCol, ssiz);

  updateGizmo();
  drawTimeline();
  updatePropPanel();
  refreshTreeSelection();
  if (typeof refreshCompTimelines === 'function') refreshCompTimelines();
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
