/* =========================================================================
 * 导出 / 导入 / 文件
 * ======================================================================= */

const r3 = x => Math.round(x * 1000) / 1000;
const roundArr = a => a.map(r3);
function encodeEasing(e) { return Array.isArray(e) ? e.map(r3) : e; }

function exportJSON() {
  const p = state.particles.map(pt => ({ id: pt.id, s: pt.style, c: roundArr(pt.color), sc: r3(pt.scale), g: pt.glow ? 1 : 0, l: pt.lightLevel, pos: roundArr(pt.pos), vel: roundArr(pt.vel || [0, 0, 0]) }));
  const t = state.tracks.map(tr => {
    const o = { pr: tr.pr, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], roundArr(k[1]), encodeEasing(k[2])]) };
    if (tr.m === 'op') o.m = 'op';
    return o;
  });
  const g = {};
  for (const [name, members] of Object.entries(state.groups)) if (members.length) g[name] = members.slice();
  return { v: 1, loop: state.loop, g, p, t };
}

function importJSON(obj) {
  pushUndo();
  state.particles = (obj.p || []).map(pt => ({
    id: pt.id || nextId(), style: STYLES.includes(pt.s) ? pt.s : 'DOT',
    color: (pt.c || [1, 1, 1, 1]).slice(0, 4), scale: pt.sc != null ? pt.sc : 1,
    glow: !!pt.g, lightLevel: pt.l || 0, pos: (pt.pos || [0, 0, 0]).slice(0, 3),
    vel: (pt.vel || [0, 0, 0]).slice(0, 3),
  }));
  state.groups = {};
  for (const [name, members] of Object.entries(obj.g || {})) state.groups[name] = members.slice();
  state.tracks = (obj.t || []).map(tr => ({
    pr: ['pos', 'rot', 'vel', 'col', 'scl'].includes(tr.pr) ? tr.pr : 'pos',
    m: tr.m === 'op' ? 'op' : 'set',
    ids: (tr.ids || []).slice(),
    kf: (tr.kf || []).map(k => [k[0], k[1].slice(), Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
  }));
  state.loop = !!obj.loop;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  state.selected.clear(); state.selectedGroup = null; state.time = 0;
  state.expandedParticles.clear(); state.expandedProps.clear();
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  state.dirty = false;
}

function download(json, filename) {
  const blob = new Blob([json], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

// 从 File 对象载入动画（用于文件选择与拖拽打开）
async function loadFile(file) {
  const text = await file.text();
  importJSON(JSON.parse(text));
  state.name = file.name.replace(/\.json$/i, '');
  state.dirty = false;
  updateTopbarTitle();
}

function updateTopbarTitle() {
  const el = document.getElementById('topbar-title');
  if (el) el.textContent = state.name + '.json';
}

async function openFile() {
  if (!confirmDiscardChanges()) return;
  if (window.showOpenFilePicker) {
    try {
      const [h] = await window.showOpenFilePicker({ types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      await loadFile(await h.getFile());
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  document.getElementById('file-import').click();
}

async function saveFile() {
  if (!state.fileHandle || !state.fileHandle.createWritable) {
    await saveFileAs();
    return;
  }
  const json = JSON.stringify(exportJSON());
  try {
    const w = await state.fileHandle.createWritable();
    await w.write(json); await w.close();
    state.dirty = false;
  } catch (e) {
    await saveFileAs();
  }
}

async function saveFileAs() {
  const json = JSON.stringify(exportJSON());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.json', types: [{ description: 'JSON', accept: { 'application/json': ['.json'] } }] });
      state.fileHandle = h;
      const w = await h.createWritable();
      await w.write(json); await w.close();
      state.dirty = false;
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  download(json, (state.name || 'my_animation') + '.json');
  state.dirty = false;
}

// 新建空白动画
function newFile() {
  if (!confirmDiscardChanges()) return;
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {};
  state.selected.clear(); state.selectedGroup = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0;
  state.name = 'my_animation';
  state.fileHandle = null;
  state.loop = true;
  document.getElementById('tl-loop').checked = true;
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  state.dirty = false;
  updateTopbarTitle();
}

// 若有未保存更改，弹出保存确认。返回是否继续操作。
function confirmDiscardChanges() {
  if (!state.dirty) return true;
  const r = confirm('有未保存的更改，是否保存？\n\n「确定」= 保存后继续\n「取消」= 不保存直接继续');
  if (r) saveFile();
  return true;
}
