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
