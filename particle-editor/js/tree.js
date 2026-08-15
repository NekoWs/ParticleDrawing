/* =========================================================================
 * 组
 * ======================================================================= */

function createGroup() {
  if (state.selected.size < 1) { alert('请先选中粒子'); return; }
  pushUndo();
  const name = nextGroupName();
  // 派生粒子由函数对象管理，不进入普通组
  const idSet = new Set([...state.selected].filter(id => !isDerivedParticle(getParticle(id))));
  if (idSet.size < 1) { popUndo(); alert('派生粒子不可建立普通组'); return; }
  for (const g in state.groups) {
    state.groups[g] = state.groups[g].filter(id => !idSet.has(id));
    if (state.groups[g].length === 0) delete state.groups[g];
  }
  state.groups[name] = [...state.selected];
  state.selectedGroup = name;
  refreshParticleTree();
}

function deleteGroup(name) {
  pushUndo();
  removeGroupAndTracks(name);
  rebuildPoints();
  refreshParticleTree();
}

/* =========================================================================
 * 粒子列表（树状时间轴）
 * ======================================================================= */

function groupedIds() {
  const ids = new Set();
  for (const members of Object.values(state.groups)) for (const id of members) ids.add(id);
  return ids;
}

function refreshParticleTree() {
  const box = document.getElementById('particle-tree');
  if (!box) return;
  box.innerHTML = '';
  if (state.particles.length === 0 && Object.keys(state.groups).length === 0 && state.functions.length === 0) {
    box.innerHTML = '<div class="hint" style="padding:8px">暂无粒子，请使用右侧工具绘制</div>';
    return;
  }
  const grouped = groupedIds();
  for (const p of state.particles) {
    if (grouped.has(p.id) || p.fx) continue; // 组内粒子折叠进组；派生粒子折叠进函数对象
    box.appendChild(renderParticleNode(p));
  }
  for (const name of Object.keys(state.groups)) box.appendChild(renderGroupNode(name));
  for (const fx of state.functions) box.appendChild(renderFunctionNode(fx));
}

function refreshTreeSelection() {
  document.querySelectorAll('.ptree-head').forEach(head => {
    head.classList.toggle('selected', state.selected.has(head.dataset.pid));
  });
}

function renderParticleNode(p) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const expanded = state.expandedParticles.has(p.id);
  const head = document.createElement('div');
  head.className = 'ptree-head' + (state.selected.has(p.id) ? ' selected' : '');
  head.dataset.pid = p.id;
  head.draggable = true;
  head.addEventListener('dragstart', (e) => {
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', p.id);
    dragIds = state.selected.has(p.id) ? [...state.selected] : [p.id];
  });
  head.addEventListener('dragend', () => { dragIds = null; });
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete(p.id); else state.expandedParticles.add(p.id);
    refreshParticleTree();
  };
  const pid = document.createElement('span');
  pid.className = 'pid'; pid.textContent = p.id;
  pid.title = '双击重命名';
  pid.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startRename(pid, (v) => renameParticle(p.id, v), () => refreshParticleTree());
  });
  const style = document.createElement('span');
  style.className = 'pstyle'; style.textContent = p.style;
  head.appendChild(arrow); head.appendChild(pid); head.appendChild(style);
  const trackCount = state.tracks.filter(tr => tr.ids.length === 1 && tr.ids[0] === p.id).length;
  if (trackCount > 0) {
    const cnt = document.createElement('span');
    cnt.className = 'ptree-track-count';
    cnt.textContent = trackCount + ' 个时间轴';
    head.appendChild(cnt);
  }
  head.onclick = () => {
    if (evShift()) {
      const anchorIdx = treeAnchorId ? state.particles.findIndex(x => x.id === treeAnchorId) : -1;
      const idx = state.particles.findIndex(x => x.id === p.id);
      if (anchorIdx >= 0 && idx >= 0 && anchorIdx !== idx) {
        const a = Math.min(anchorIdx, idx), b = Math.max(anchorIdx, idx);
        state.selected.clear();
        for (let i = a; i <= b; i++) state.selected.add(state.particles[i].id);
      } else {
        state.selected.add(p.id);
      }
    } else {
      state.selected.clear();
      state.selected.add(p.id);
      treeAnchorId = p.id;
    }
    state.selectedGroup = null;
    rebuildPoints();
  };
  head.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除粒子', danger: true, action: () => { state.selected = new Set([p.id]); deleteSelected(); } },
    ]);
  });
  root.appendChild(head);

  if (expanded) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of PARTICLE_TRACK_DEFS) props.appendChild(renderParticleTrackNode(p, def));
    root.appendChild(props);
  }
  return root;
}

function startRename(el, onCommit, onCancel) {
  const input = document.createElement('input');
  input.type = 'text';
  input.value = el.textContent;
  input.className = 'rename-input';
  el.replaceWith(input);
  input.focus();
  input.select();
  let done = false;
  const commit = () => { if (done) return; done = true; onCommit(input.value); };
  const cancel = () => { if (done) return; done = true; onCancel(); };
  input.addEventListener('blur', commit);
  input.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') commit();
    else if (e.key === 'Escape') cancel();
  });
}

/* 右键菜单 */
function closeContextMenu() {
  const m = document.getElementById('context-menu');
  if (m) m.remove();
}
function showContextMenu(x, y, items) {
  closeContextMenu();
  const menu = document.createElement('div');
  menu.id = 'context-menu';
  menu.className = 'context-menu';
  for (const item of items) {
    if (item === null) { const sep = document.createElement('div'); sep.className = 'cm-sep'; menu.appendChild(sep); continue; }
    const btn = document.createElement('button');
    btn.className = 'cm-item' + (item.danger ? ' danger' : '');
    btn.textContent = item.label;
    btn.onclick = () => { closeContextMenu(); item.action(); };
    menu.appendChild(btn);
  }
  document.body.appendChild(menu);
  menu.style.left = Math.min(x, window.innerWidth - 160) + 'px';
  menu.style.top = Math.min(y, window.innerHeight - items.length * 32 - 12) + 'px';
}
window.addEventListener('pointerdown', (e) => { if (!e.target.closest('#context-menu')) closeContextMenu(); });

let evShift = () => false; // 占位，稍后在 initUI 中通过 window 事件设置
let treeAnchorId = null; // 树状列表 Shift 范围选择的锚点

function makeColorSwatch(kf, onCommit) {
  const inp = document.createElement('input');
  inp.type = 'color';
  inp.className = 'kf-color';
  inp.value = rgbToHex(kf[1][0], kf[1][1], kf[1][2]);
  inp.title = '取色';
  inp.addEventListener('input', (e) => {
    beginContinuous();
    const [r, g, b] = hexToRgb(e.target.value);
    onCommit(r, g, b);
  });
  inp.addEventListener('change', () => {
    endContinuous();
    refreshParticleTree();
  });
  return inp;
}

function renderParticleTrackNode(p, def) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = p.id + '|' + def.key;
  const expanded = state.expandedProps.has(key);
  const tr = findTrack(def.key, p.id);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  head.innerHTML = `<span class="arrow">${expanded ? '▾' : '▸'}</span><span class="plabel">${def.label}</span><span class="pval">${tr ? tr.kf.length + ' 节点' : '—'}</span>`;
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) tr.kf.forEach((kf, idx) => kfs.appendChild(renderParticleKfRow(p, def, kf, idx === 0)));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => {
      pushUndo();
      let tr = findTrack(def.key, p.id);
      if (!tr) {
        tr = { pr: def.key, m: 'set', ids: [p.id], kf: [[0, baseValue(p, def.key).slice(), state.defaultEasing]] };
        state.tracks.push(tr);
      } else {
        const t = nextFreeTime(tr, Math.round(state.time));
        tr.kf.push([t, particleValueAt(p, def.key, t).slice(), state.defaultEasing]);
        tr.kf.sort((a, b) => a[0] - b[0]);
      }
      rebuildPoints(); refreshParticleTree();
    };
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function renderParticleKfRow(p, def, kf, isFirst) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.title = '时间 (tick)';
  tIn.onchange = () => updateKeyframeTime(p.id, def.key, kf[0], parseInt(tIn.value) || 0);
  tIn.addEventListener('click', (e) => { if (e.ctrlKey) { state.time = kf[0]; resetVelOffsets(); updateTimeUI(); rebuildPoints(); } });
  row.appendChild(tIn);
  if (def.key === 'col') {
    row.appendChild(makeColorSwatch(kf, (r, g, b) => {
      kf[1][0] = r; kf[1][1] = g; kf[1][2] = b;
      if (kf[0] === 0) applyBaseValue(p, def.key, kf[1]);
      rebuildPoints();
    }));
  }
  for (let i = 0; i < def.labels.length; i++) {
    const vIn = document.createElement('input');
    vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][i]);
    vIn.title = def.labels[i] + ' 值';
    vIn.onchange = () => setComponentValue(p.id, { track: def.key, index: i }, kf[0], parseFloat(vIn.value) || 0);
    row.appendChild(vIn);
  }
  if (!isFirst) {
    const easeBtn = makeEasingBtn(kf[2], (easing) => updateKeyframeEasing(p.id, def.key, kf[0], easing));
    row.appendChild(easeBtn);
  }
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除节点', danger: true, action: () => removeKeyframe(p.id, def.key, kf[0]) },
    ]);
  });
  return row;
}

function renderGroupNode(name) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const members = state.groups[name] || [];
  const expanded = state.expandedParticles.has('g:' + name);
  const head = document.createElement('div');
  head.className = 'ptree-head group';
  head.addEventListener('dragover', (e) => {
    if (dragIds) { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; head.classList.add('drop-hint'); }
  });
  head.addEventListener('dragleave', () => head.classList.remove('drop-hint'));
  head.addEventListener('drop', (e) => {
    e.preventDefault();
    e.stopPropagation();
    head.classList.remove('drop-hint');
    if (dragIds) moveParticlesToGroup(dragIds, name);
    dragIds = null;
  });
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete('g:' + name); else state.expandedParticles.add('g:' + name);
    refreshParticleTree();
  };
  const label = document.createElement('span');
  label.className = 'pid';
  label.textContent = name;
  label.title = '双击重命名';
  label.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startRename(label, (v) => renameGroup(name, v), () => refreshParticleTree());
  });
  const count = document.createElement('span');
  count.className = 'pstyle';
  count.textContent = members.length + ' 成员';
  head.appendChild(arrow); head.appendChild(label); head.appendChild(count);
  head.onclick = () => {
    state.selected = new Set(members.filter(id => state.particles.some(p => p.id === id)));
    state.selectedGroup = name;
    rebuildPoints();
  };
  head.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除组及其粒子', danger: true, action: () => deleteGroup(name) },
    ]);
  });
  root.appendChild(head);
  if (expanded) {
    const section = document.createElement('div');
    section.className = 'ptree-sub';
    section.appendChild(renderGroupPropsNode(name));
    section.appendChild(renderGroupMembersNode(name, members));
    root.appendChild(section);
  }
  return root;
}

function renderGroupPropsNode(name) {
  const wrap = document.createElement('div');
  const key = 'g:' + name + '|@props';
  // 「属性」默认展开：记录 key 表示已收起
  const collapsed = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = collapsed ? '▸' : '▾';
  const label = document.createElement('span');
  label.textContent = '属性';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (collapsed) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (!collapsed) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of GROUP_PROP_DEFS) props.appendChild(renderGroupPropNode(name, def));
    wrap.appendChild(props);
  }
  return wrap;
}

function renderGroupMembersNode(name, members) {
  const wrap = document.createElement('div');
  const key = 'g:' + name + '|@members';
  const expanded = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const label = document.createElement('span');
  label.textContent = '粒子列表 (' + members.length + ')';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const list = document.createElement('div');
    list.className = 'ptree-members';
    for (const id of members) {
      const p = getParticle(id);
      if (!p) continue;
      list.appendChild(renderParticleNode(p));
    }
    wrap.appendChild(list);
  }
  return wrap;
}

function renderGroupPropNode(name, def) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = 'g:' + name + '|' + def.key;
  const expanded = state.expandedProps.has(key);
  const tr = findGroupTrack(def.key, name);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const plabel = document.createElement('span');
  plabel.className = 'plabel';
  plabel.textContent = def.label;
  head.appendChild(arrow); head.appendChild(plabel);
  const modeSel = document.createElement('select');
  modeSel.className = 'mode-sel';
  const mSet = document.createElement('option'); mSet.value = 'set'; mSet.textContent = '设置';
  modeSel.appendChild(mSet);
  if (def.key !== 'rot') {
    const mOp = document.createElement('option'); mOp.value = 'op'; mOp.textContent = '操作';
    modeSel.appendChild(mOp);
  }
  const defaultMode = (def.key === 'rot' || def.key === 'col' || def.key === 'vel') ? 'set' : 'op';
  modeSel.value = tr ? tr.m : defaultMode;
  modeSel.onchange = () => setGroupTrackMode(name, def.key, modeSel.value);
  modeSel.onclick = (e) => e.stopPropagation();
  head.appendChild(modeSel);
  const pval = document.createElement('span');
  pval.className = 'pval';
  pval.textContent = tr ? tr.kf.length + ' 节点' : '—';
  head.appendChild(pval);
  head.onclick = (e) => {
    if (e.target.tagName === 'SELECT') return;
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) tr.kf.forEach((kf, idx) => kfs.appendChild(renderGroupKfRow(name, def, tr.m, kf, idx === 0)));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => {
      pushUndo();
      const mode = modeSel.value;
      let track = findGroupTrack(def.key, name);
      if (!track) {
        const base = mode === 'op' ? zeroArray(def.key).slice() : groupCentroidValue(name, def.key);
        track = { pr: def.key, m: mode, ids: ['g:' + name], kf: [[0, base, state.defaultEasing]] };
        state.tracks.push(track);
      } else {
        track.m = mode;
        const t = nextFreeTime(track, Math.round(state.time));
        const cur = mode === 'op' ? zeroArray(def.key).slice() : groupCentroidValue(name, def.key);
        track.kf.push([t, cur, state.defaultEasing]);
        track.kf.sort((a, b) => a[0] - b[0]);
      }
      rebuildPoints(); refreshParticleTree();
    };
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function groupCentroidValue(name, prop) {
  if (prop === 'rot') return [0, 0, 0];
  const members = (state.groups[name] || []).map(getParticle).filter(Boolean);
  if (members.length === 0) return zeroArray(prop);
  const sum = zeroArray(prop);
  for (const m of members) {
    const v = baseValue(m, prop);
    for (let i = 0; i < sum.length; i++) sum[i] += v[i];
  }
  return sum.map(v => r3(v / members.length));
}

// 组当前视觉值的质心（含动画）
function groupCurrentCentroid(name, prop) {
  const members = (state.groups[name] || []).map(getParticle).filter(Boolean);
  if (members.length === 0) return zeroArray(prop);
  const sum = zeroArray(prop);
  for (const m of members) {
    const v = particleValueAt(m, prop, state.time);
    for (let i = 0; i < sum.length; i++) sum[i] += v[i];
  }
  return sum.map(v => r3(v / members.length));
}

function renderGroupKfRow(name, def, mode, kf, isFirst) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.title = '时间 (tick)';
  tIn.onchange = () => updateKeyframeTime('g:' + name, def.key, kf[0], parseInt(tIn.value) || 0);
  tIn.addEventListener('click', (e) => { if (e.ctrlKey) { state.time = kf[0]; resetVelOffsets(); updateTimeUI(); rebuildPoints(); } });
  row.appendChild(tIn);
  if (def.key === 'col' && mode !== 'op') {
    row.appendChild(makeColorSwatch(kf, (r, g, b) => {
      kf[1][0] = r; kf[1][1] = g; kf[1][2] = b;
      rebuildPoints();
    }));
  }
  for (let i = 0; i < def.size; i++) {
    const vIn = document.createElement('input');
    vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][i]);
    vIn.title = def.labels
      ? (def.key === 'rot' ? def.labels[i] + ' (角度)' : def.labels[i] + (mode === 'op' ? ' 增量' : ' 值'))
      : def.key;
    vIn.onchange = () => {
      const v = kf[1].slice();
      v[i] = parseFloat(vIn.value) || 0;
      pushUndo();
      setGroupTrackValue(name, def.key, mode, kf[0], v);
    };
    row.appendChild(vIn);
  }
  if (!isFirst) {
    const easeBtn = makeEasingBtn(kf[2], (easing) => updateKeyframeEasing('g:' + name, def.key, kf[0], easing));
    row.appendChild(easeBtn);
  }
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除节点', danger: true, action: () => removeKeyframe('g:' + name, def.key, kf[0]) },
    ]);
  });
  return row;
}

/* =========================================================================
 * 函数对象：树节点
 * ======================================================================= */

// 函数对象整体变换轨道定义（pos/rot/scl）
const FUNCTION_PROP_DEFS = [
  { key: 'pos', label: '位置', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'rot', label: '旋转', size: 3, labels: ['X', 'Y', 'Z'] },
  { key: 'scl', label: '缩放', size: 1, labels: ['缩放'] },
];

function renderFunctionPropsNode(fx) {
  const wrap = document.createElement('div');
  const key = 'f:' + fx.id + '|@props';
  const collapsed = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = collapsed ? '▸' : '▾';
  const label = document.createElement('span');
  label.textContent = '属性';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (collapsed) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (!collapsed) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of FUNCTION_PROP_DEFS) props.appendChild(renderFunctionPropNode(fx, def));
    wrap.appendChild(props);
  }
  return wrap;
}

function renderFunctionPropNode(fx, def) {
  const wrap = document.createElement('div');
  wrap.className = 'ptree-prop';
  const key = 'f:' + fx.id + '|' + def.key;
  const expanded = state.expandedProps.has(key);
  const tr = findFunctionTrack(def.key, fx.id);
  const head = document.createElement('div');
  head.className = 'ptree-prop-head';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const plabel = document.createElement('span');
  plabel.className = 'plabel';
  plabel.textContent = def.label;
  head.appendChild(arrow); head.appendChild(plabel);
  const modeSel = document.createElement('select');
  modeSel.className = 'mode-sel';
  const mSet = document.createElement('option'); mSet.value = 'set'; mSet.textContent = '设置';
  modeSel.appendChild(mSet);
  if (def.key === 'pos') {
    const mOp = document.createElement('option'); mOp.value = 'op'; mOp.textContent = '操作';
    modeSel.appendChild(mOp);
  }
  const defaultMode = def.key === 'pos' ? 'op' : 'set';
  modeSel.value = tr ? tr.m : defaultMode;
  modeSel.onchange = () => setFunctionTrackMode(fx.id, def.key, modeSel.value);
  modeSel.onclick = (e) => e.stopPropagation();
  head.appendChild(modeSel);
  const pval = document.createElement('span');
  pval.className = 'pval';
  pval.textContent = tr ? tr.kf.length + ' 节点' : '—';
  head.appendChild(pval);
  head.onclick = (e) => {
    if (e.target.tagName === 'SELECT') return;
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const kfs = document.createElement('div');
    kfs.className = 'ptree-kfs';
    if (tr) tr.kf.forEach((kf, idx) => kfs.appendChild(renderFunctionKfRow(fx, def, tr.m, kf, idx === 0)));
    const add = document.createElement('button');
    add.className = 'kf-add';
    add.textContent = '+ 添加节点';
    add.onclick = () => {
      pushUndo();
      const mode = modeSel.value;
      let track = findFunctionTrack(def.key, fx.id);
      if (!track) {
        const base = mode === 'op' ? zeroArray(def.key).slice() : functionBaseValue(def.key);
        track = { pr: def.key, m: mode, ids: ['f:' + fx.id], kf: [[0, base, state.defaultEasing]] };
        state.tracks.push(track);
      } else {
        track.m = mode;
        const t = nextFreeTime(track, Math.round(state.time));
        const cur = mode === 'op' ? zeroArray(def.key).slice() : functionBaseValue(def.key);
        track.kf.push([t, cur, state.defaultEasing]);
        track.kf.sort((a, b) => a[0] - b[0]);
      }
      rebuildPoints(); refreshParticleTree();
    };
    kfs.appendChild(add);
    wrap.appendChild(kfs);
  }
  return wrap;
}

function renderFunctionKfRow(fx, def, mode, kf, isFirst) {
  const row = document.createElement('div');
  row.className = 'kf-row';
  const tIn = document.createElement('input');
  tIn.className = 'kf-t'; tIn.type = 'number'; tIn.value = kf[0];
  tIn.title = '时间 (tick)';
  tIn.onchange = () => updateKeyframeTime('f:' + fx.id, def.key, kf[0], parseInt(tIn.value) || 0);
  tIn.addEventListener('click', (e) => { if (e.ctrlKey) { state.time = kf[0]; resetVelOffsets(); updateTimeUI(); rebuildPoints(); } });
  row.appendChild(tIn);
  for (let i = 0; i < def.size; i++) {
    const vIn = document.createElement('input');
    vIn.className = 'kf-v'; vIn.type = 'number'; vIn.step = '0.01'; vIn.value = r3(kf[1][i]);
    vIn.title = def.labels
      ? (def.key === 'rot' ? def.labels[i] + ' (角度)' : def.labels[i] + (mode === 'op' ? ' 增量' : ' 值'))
      : def.key;
    vIn.onchange = () => {
      const v = kf[1].slice();
      v[i] = parseFloat(vIn.value) || 0;
      pushUndo();
      setFunctionTrackValue(fx.id, def.key, mode, kf[0], v);
    };
    row.appendChild(vIn);
  }
  if (!isFirst) {
    const easeBtn = makeEasingBtn(kf[2], (easing) => updateKeyframeEasing('f:' + fx.id, def.key, kf[0], easing));
    row.appendChild(easeBtn);
  }
  row.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除节点', danger: true, action: () => removeKeyframe('f:' + fx.id, def.key, kf[0]) },
    ]);
  });
  return row;
}

function renameFunction(oldId, newName) {
  newName = (newName || '').trim();
  if (!newName) return false;
  pushUndo();
  const fx = getFunction(oldId);
  if (fx) fx.name = newName;
  refreshParticleTree();
  refreshFunctionPanel();
  return true;
}

function renderFunctionNode(fx) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const expanded = state.expandedParticles.has('f:' + fx.id);
  const head = document.createElement('div');
  head.className = 'ptree-head func';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete('f:' + fx.id); else state.expandedParticles.add('f:' + fx.id);
    refreshParticleTree();
  };
  const label = document.createElement('span');
  label.className = 'pid';
  label.textContent = fx.name;
  label.title = '双击重命名';
  label.addEventListener('dblclick', (e) => {
    e.stopPropagation();
    startRename(label, (v) => renameFunction(fx.id, v), () => refreshParticleTree());
  });
  const count = document.createElement('span');
  count.className = 'pstyle';
  count.textContent = fx.count + ' 粒子';
  head.appendChild(arrow); head.appendChild(label); head.appendChild(count);
  head.onclick = () => {
    const ids = state.particles.filter(p => p.fx === fx.id).map(p => p.id);
    state.selected = new Set(ids);
    state.selectedGroup = null;
    state.selectedFunction = fx.id;
    rebuildPoints();
    refreshFunctionPanel();
  };
  head.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    e.stopPropagation();
    showContextMenu(e.clientX, e.clientY, [
      { label: '删除函数对象', danger: true, action: () => deleteFunctionObject(fx.id) },
    ]);
  });
  root.appendChild(head);
  if (expanded) {
    const section = document.createElement('div');
    section.className = 'ptree-sub';
    section.appendChild(renderFunctionPropsNode(fx));
    section.appendChild(renderFunctionMembersNode(fx));
    root.appendChild(section);
  }
  return root;
}

function renderFunctionMembersNode(fx) {
  const wrap = document.createElement('div');
  const members = state.particles.filter(p => p.fx === fx.id);
  const key = 'f:' + fx.id + '|@members';
  const expanded = state.expandedProps.has(key);
  const head = document.createElement('div');
  head.className = 'ptree-subhead';
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  const label = document.createElement('span');
  label.textContent = '粒子列表 (' + members.length + ')';
  head.appendChild(arrow); head.appendChild(label);
  head.onclick = () => {
    if (expanded) state.expandedProps.delete(key); else state.expandedProps.add(key);
    refreshParticleTree();
  };
  wrap.appendChild(head);
  if (expanded) {
    const list = document.createElement('div');
    list.className = 'ptree-members';
    for (const p of members) list.appendChild(renderFunctionParticleNode(fx, p));
    wrap.appendChild(list);
  }
  return wrap;
}

function renderFunctionParticleNode(fx, p) {
  const root = document.createElement('div');
  root.className = 'ptree-particle';
  const expanded = state.expandedParticles.has(p.id);
  const head = document.createElement('div');
  head.className = 'ptree-head func-child' + (state.selected.has(p.id) ? ' selected' : '');
  head.dataset.pid = p.id;
  const arrow = document.createElement('span');
  arrow.className = 'arrow';
  arrow.textContent = expanded ? '▾' : '▸';
  arrow.onclick = (e) => {
    e.stopPropagation();
    if (expanded) state.expandedParticles.delete(p.id); else state.expandedParticles.add(p.id);
    refreshParticleTree();
  };
  const pid = document.createElement('span');
  pid.className = 'pid'; pid.textContent = p.id;
  pid.title = '派生粒子（基础属性由函数控制）';
  const style = document.createElement('span');
  style.className = 'pstyle'; style.textContent = p.style;
  head.appendChild(arrow); head.appendChild(pid); head.appendChild(style);
  head.onclick = () => {
    state.selected.clear();
    state.selected.add(p.id);
    state.selectedGroup = null;
    state.selectedFunction = null;
    rebuildPoints();
    refreshFunctionPanel();
  };
  root.appendChild(head);
  if (expanded) {
    const props = document.createElement('div');
    props.className = 'ptree-props';
    for (const def of PARTICLE_TRACK_DEFS) props.appendChild(renderParticleTrackNode(p, def));
    root.appendChild(props);
  }
  return root;
}
