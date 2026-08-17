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

// ---- 求值索引缓存（rebuildPoints 每次重建，避免 O(N) / O(N·M) 线性扫描） ----
let trackIndexCache = null;        // Map: pr -> Map(id -> track)
let opTracksCache = null;          // Array<track>（op 模式轨道）
let groupSetCache = null;          // Map: gname -> Set<id>
let groupMemberIndexCache = null;  // Map: particleId -> Set<gname>
let groupCentroidPosCache = null;  // Map: gname -> [x,y,z]

function buildParticleIndex() {
  const map = new Map();
  for (const p of state.particles) map.set(p.id, p);
  particleIndexCache = map;
}

function buildTrackIndex() {
  const map = new Map();
  const opTracks = [];
  for (const tr of state.tracks) {
    if (tr.ids.length === 1) {
      let byId = map.get(tr.pr);
      if (!byId) { byId = new Map(); map.set(tr.pr, byId); }
      byId.set(tr.ids[0], tr);
    }
    if (tr.m === 'op') opTracks.push(tr);
  }
  trackIndexCache = map;
  opTracksCache = opTracks;
}

// 组成员索引（particleId -> 所属组集合）+ 组 Set + 组质心缓存
function buildGroupIndex() {
  const memberIdx = new Map();
  const sets = new Map();
  const centroids = new Map();
  for (const [gname, members] of Object.entries(state.groups)) {
    const s = new Set(members);
    sets.set(gname, s);
    for (const id of members) {
      let gs = memberIdx.get(id);
      if (!gs) { gs = new Set(); memberIdx.set(id, gs); }
      gs.add(gname);
    }
    let sx = 0, sy = 0, sz = 0, n = 0;
    for (const id of members) {
      const m = getParticle(id);
      if (!m) continue;
      sx += m.pos[0]; sy += m.pos[1]; sz += m.pos[2]; n++;
    }
    if (n > 0) centroids.set(gname, [sx / n, sy / n, sz / n]);
  }
  groupMemberIndexCache = memberIdx;
  groupSetCache = sets;
  groupCentroidPosCache = centroids;
}

// 按 pr + id 精确查找轨道（O(1)）
function findTrackByPr(pr, id) {
  if (trackIndexCache) {
    const byId = trackIndexCache.get(pr);
    return byId ? (byId.get(id) || null) : null;
  }
  return state.tracks.find(tr => tr.pr === pr && tr.ids.length === 1 && tr.ids[0] === id) || null;
}

// 某 id 在某分量的 set 轨道（优先级：自身 > 组 > 函数对象）
function findSetTrackFor(id, prop, comp) {
  const pr = compPr(prop, comp);
  const own = findTrackByPr(pr, id);
  if (own && own.m !== 'op') return own;
  const gs = groupMemberIndexCache && groupMemberIndexCache.get(id);
  if (gs) {
    for (const gname of gs) {
      const tr = findTrackByPr(pr, 'g:' + gname);
      if (tr && tr.m !== 'op') return tr;
    }
  }
  const p = getParticle(id);
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
  for (const tr of opTracksCache) {
    if (tr.pr !== pr || tr.kf.length === 0) continue;
    for (const id of tr.ids) {
      if (id.startsWith('g:')) {
        const members = groupSetCache.get(id.slice(2));
        if (members && members.has(p.id)) delta += trackValueAt(tr, T, 0);
      } else if (id.startsWith('f:') && p.fx === id.slice(2)) {
        delta += trackValueAt(tr, T, 0);
      }
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
  const gs = groupMemberIndexCache && groupMemberIndexCache.get(p.id);
  if (gs) {
    for (const gname of gs) {
      const rot = rotVectorAt('g:' + gname, T);
      if (rot[0] === 0 && rot[1] === 0 && rot[2] === 0) continue;
      const pivot = (groupCentroidPosCache && groupCentroidPosCache.get(gname)) || groupCentroidValue(gname, 'pos');
      return { rot, pivot };
    }
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
  if (p.fx) return currentVisualDerived(p, state.time);
  return {
    pos: particleValueAt(p, 'pos', state.time),
    color: particleValueAt(p, 'col', state.time),
    scale: particleValueAt(p, 'scl', state.time)[0],
  };
}

// 派生粒子活源求值：每帧执行公式代码块（random 每帧变化，实现星光闪闪预览），
// 再叠加函数对象整体旋转（rot）与位移增量（op），与游戏端活源语义一致。
function currentVisualDerived(p, T) {
  const fx = getFunction(p.fx);
  if (!fx) return { pos: [0, 0, 0], color: [1, 1, 1, 1], scale: 1 };
  const i = parseInt(p.id.slice(fx.id.length + 2), 10);
  const n = fx.count;
  const r = evaluateParticleAt(fx, i, n, T);
  let pos = applyGroupRotation(p, r.pos.slice(), T);
  pos = pos.map((v, ci) => v + compOpDelta(p, 'pos', ['x', 'y', 'z'][ci], T));
  const sclTr = findTrackByPr('scl', 'f:' + fx.id);
  const scale = (sclTr && sclTr.kf.length > 0) ? trackValueAt(sclTr, T, r.scale) : r.scale;
  return { pos, color: r.color, scale };
}

function maxTick() {
  let m = 0;
  for (const tr of state.tracks) for (const k of tr.kf) m = Math.max(m, k[0]);
  return m;
}

// 速度位移积分：按时间计算（任何时刻都生效，含非播放/拖动时间轴），渲染期叠加不改数据。
// 兼容旧调用：速度积分已改为按 time 计算，无需重置状态。
function resetVelOffsets() {}

// 轨道分段积分（线性近似，忽略缓动）：trackValueAt 的常数段 + 线性段面积
function trackIntegral(tr, time) {
  const kfs = tr.kf;
  if (!kfs || kfs.length === 0) return 0;
  const first = kfs[0], last = kfs[kfs.length - 1];
  if (time <= first[0]) return first[1] * time;
  let acc = first[1] * Math.max(0, first[0]); // [0, first.tick] 常数段
  for (let i = 0; i < kfs.length - 1; i++) {
    const a = kfs[i], b = kfs[i + 1];
    if (a[0] >= time) break;
    const ts = Math.max(a[0], 0);
    const te = Math.min(b[0], time);
    if (te <= ts) continue;
    const dur = b[0] - a[0];
    if (dur <= 0) continue;
    const f0 = (ts - a[0]) / dur;
    const f1 = (te - a[0]) / dur;
    const v0 = a[1] + (b[1] - a[1]) * f0;
    const v1 = a[1] + (b[1] - a[1]) * f1;
    acc += (v0 + v1) * 0.5 * (te - ts);
  }
  if (time > last[0]) acc += last[1] * (time - last[0]);
  return acc;
}

// 速度从 0 到 time 的位移积分（恒定速度解析，轨道分段线性近似）
function velOffsetAt(p, time) {
  if (time <= 0) return [0, 0, 0];
  if (p.fx) {
    // 派生粒子：活源初速（t=0 的 p.vel）恒定积分（散开等恒定速度效果精确）
    return ['x', 'y', 'z'].map(c => baseComponent(p, 'vel', c) * time);
  }
  return ['x', 'y', 'z'].map(c => {
    const tr = findSetTrackFor(p.id, 'vel', c);
    if (!tr || tr.kf.length === 0) return baseComponent(p, 'vel', c) * time;
    return trackIntegral(tr, time);
  });
}

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
function rebuildPoints(full) {
  buildParticleIndex();
  buildTrackIndex();
  buildGroupIndex();
  const n = state.particles.length;
  if (!rpPos || rpPos.length !== n * 3) rpPos = new Float32Array(n * 3);
  if (!rpCol || rpCol.length !== n * 4) rpCol = new Float32Array(n * 4);
  if (!rpSize || rpSize.length !== n) rpSize = new Float32Array(n);
  const positions = rpPos, colors = rpCol, sizes = rpSize;
  for (let i = 0; i < n; i++) {
    const p = state.particles[i];
    const v = currentVisual(p);
    const off = velOffsetAt(p, state.time);
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
    const off = velOffsetAt(sel[i], state.time);
    spos[i * 3] = v.pos[0] + off[0]; spos[i * 3 + 1] = v.pos[1] + off[1]; spos[i * 3 + 2] = v.pos[2] + off[2];
    ssiz[i] = Math.max(0.02, v.scale * PARTICLE_SIZE_FACTOR);
  }
  setPointsGeometry(selectedPoints, spos, rpSelCol, ssiz);

  updateGizmo();
  drawTimeline();
  if (full !== false) {
    updatePropPanel();
    refreshTreeSelection();
    if (typeof refreshCompTimelines === 'function') refreshCompTimelines();
  }
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
