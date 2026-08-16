/* =========================================================================
 * 导出 / 导入 / 文件
 * ======================================================================= */

const r3 = x => Math.round(x * 1000) / 1000;
const roundArr = a => a.map(r3);
function encodeEasing(e) { return Array.isArray(e) ? e.map(r3) : e; }

// 颜色是否为默认白色（省略导出）
function isDefaultColor(c) {
  return !!c && Math.abs(c[0] - 1) < 1e-9 && Math.abs(c[1] - 1) < 1e-9 && Math.abs(c[2] - 1) < 1e-9 && Math.abs(c[3] - 1) < 1e-9;
}

// 粒子序列化：省略等于默认值的字段，减小工程文件体积（解析侧均有默认回退）
function serializeParticle(pt) {
  const o = { id: pt.id, pos: roundArr(pt.pos) };
  if (pt.style !== 'DOT') o.s = pt.style;
  if (!isDefaultColor(pt.color)) o.c = roundArr(pt.color);
  if (pt.scale !== 1) o.sc = r3(pt.scale);
  if (pt.glow) o.g = 1;
  if (pt.lightLevel) o.l = pt.lightLevel;
  const v = pt.vel || [0, 0, 0];
  if (v[0] || v[1] || v[2]) o.vel = roundArr(v);
  return o;
}

/* —— 函数对象 序列化 —— */
function serializeVars(vars) {
  const o = {};
  for (const [name, v] of Object.entries(vars || {})) {
    o[name] = { expr: v.expr != null ? String(v.expr) : '0', kf: (v.kf || []).map(k => [k[0], k[1], k[2]]) };
  }
  return o;
}
function parseVars(vars) {
  const o = {};
  for (const [name, v] of Object.entries(vars || {})) {
    if (typeof v === 'string') o[name] = { expr: v, kf: [] }; // 兼容旧格式（纯字符串）
    else o[name] = {
      expr: v.expr != null ? String(v.expr) : '0',
      kf: (v.kf || []).map(k => [k[0], k[1], Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
    };
  }
  return o;
}
// 兼容上一版（分散字段）工程文件
function legacyCode(o) {
  const parts = [];
  if (o.x || o.y || o.z) parts.push('[x,y,z] = [' + (o.x || 0) + ', ' + (o.y || 0) + ', ' + (o.z || 0) + ']');
  if (o.col) parts.push('[r,g,b,a] = ' + o.col);
  if (o.scl != null) parts.push('sc = ' + o.scl);
  if (o.glow != null) parts.push('glow = ' + o.glow);
  if (o.light != null) parts.push('light = ' + o.light);
  return parts.join('; ');
}
function serializeFunction(fx) {
  const o = {
    id: fx.id, name: fx.name, center: fx.center.slice(), count: fx.count,
    style: fx.style, code: fx.code || '',
    vars: serializeVars(fx.vars),
    duration: fx.duration, step: fx.step,
  };
  if (fx.preset) o.preset = fx.preset;
  if (fx.params) o.params = { ...fx.params };
  if (fx.ui) o.ui = JSON.parse(JSON.stringify(fx.ui));
  return o;
}
function parseFunction(o) {
  return {
    id: o.id, name: o.name || '函数对象', center: (o.center || [0, 0, 0]).slice(0, 3), count: o.count || 30,
    style: STYLES.includes(o.style) ? o.style : 'GLOW',
    code: o.code != null ? String(o.code) : legacyCode(o),
    vars: parseVars(o.vars),
    duration: o.duration || 0, step: o.step || 5,
    preset: o.preset || null, params: o.params ? { ...o.params } : null,
    ui: o.ui || null,
  };
}

// 导出工程（.pdraw）：独立粒子 + 非派生轨道 + 函数对象定义
function exportProject() {
  const p = state.particles.filter(pt => !pt.fx).map(serializeParticle);
  const t = state.tracks.filter(tr => !tr.fx).map(tr => {
    const o = { pr: tr.pr, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], r3(k[1]), encodeEasing(k[2])]) };
    if (tr.m === 'op') o.m = 'op';
    return o;
  });
  // 组：剔除函数对象派生粒子成员（id 形如 fxId:p<i>，由客户端播放时实时生成，不烘焙进工程文件）
  const fxPrefixes = state.functions.map(fx => fx.id + ':p');
  const isDerived = id => fxPrefixes.some(pre => id.startsWith(pre));
  const g = {};
  for (const [name, members] of Object.entries(state.groups)) {
    const kept = members.filter(id => !isDerived(id));
    if (kept.length) g[name] = kept;
  }
  const f = state.functions.map(serializeFunction);
  return { v: 2, loop: state.loop, g, p, t, f };
}

function parseParticlesTracks(obj) {
  state.particles = (obj.p || []).map(pt => ({
    id: pt.id || nextId(), style: STYLES.includes(pt.s) ? pt.s : 'DOT',
    color: (pt.c || [1, 1, 1, 1]).slice(0, 4), scale: pt.sc != null ? pt.sc : 1,
    glow: !!pt.g, lightLevel: pt.l || 0, pos: (pt.pos || [0, 0, 0]).slice(0, 3),
    vel: (pt.vel || [0, 0, 0]).slice(0, 3),
  }));
  state.groups = {};
  for (const [name, members] of Object.entries(obj.g || {})) state.groups[name] = members.slice();
  state.tracks = (obj.t || []).map(tr => {
    const [prop] = splitCompPr(tr.pr);
    return {
      pr: PROP_LABELS[prop] ? tr.pr : 'pos.x',
      m: tr.m === 'op' ? 'op' : 'set',
      ids: (tr.ids || []).slice(),
      kf: (tr.kf || []).map(k => [k[0], k[1], Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
    };
  });
  state.loop = !!obj.loop;
}

function importJSON(obj) {
  pushUndo();
  parseParticlesTracks(obj);
  state.functions = [];
  state.selectedFunction = null;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  state.selected.clear(); state.selectedGroup = null; state.time = 0;
  state.expandedParticles.clear(); state.expandedProps.clear();
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  setDirty(false);
}

function importProject(obj) {
  pushUndo();
  parseParticlesTracks(obj);
  state.functions = (obj.f || []).map(parseFunction);
  state.selectedFunction = null;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  state.selected.clear(); state.selectedGroup = null; state.time = 0;
  state.expandedParticles.clear(); state.expandedProps.clear();
  for (const fx of state.functions) {
    try { rebuildFunctionObject(fx); } catch (e) { console.warn('函数对象求值失败：' + fx.id + ' ' + e.message); }
  }
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  setDirty(false);
}

function download(json, filename) {
  const blob = new Blob([json], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  a.click();
  URL.revokeObjectURL(a.href);
}

// 从 File 对象载入（文件选择与拖拽打开）
async function loadFile(file) {
  const text = await file.text();
  const obj = JSON.parse(text);
  if (file.name.toLowerCase().endsWith('.pdraw') || obj.v === 2 || obj.f) importProject(obj);
  else importJSON(obj);
  state.name = file.name.replace(/\.(json|pdraw)$/i, '');
  setDirty(false);
}

function updateTopbarTitle() {
  const el = document.getElementById('topbar-title');
  if (el) el.textContent = state.name + '.pdraw' + (state.dirty ? ' *' : '');
}

async function openFile() {
  if (!confirmDiscardChanges()) return;
  if (window.showOpenFilePicker) {
    try {
      const [h] = await window.showOpenFilePicker({ types: [{ description: '工程/动画', accept: { 'application/json': ['.pdraw', '.json'] } }] });
      state.fileHandle = h;
      await loadFile(await h.getFile());
    } catch (e) { /* 取消则忽略 */ }
    return;
  }
  document.getElementById('file-import').click();
}

async function saveFile() {
  if (!state.fileHandle || !state.fileHandle.createWritable) {
    await saveFileAs();
    return;
  }
  const json = JSON.stringify(exportProject());
  try {
    const w = await state.fileHandle.createWritable();
    await w.write(json); await w.close();
    setDirty(false);
  } catch (e) {
    await saveFileAs();
  }
}

async function saveFileAs() {
  const json = JSON.stringify(exportProject());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.pdraw', types: [{ description: '工程文件', accept: { 'application/json': ['.pdraw'] } }] });
      state.fileHandle = h;
      const w = await h.createWritable();
      await w.write(json); await w.close();
      setDirty(false);
      return;
    } catch (e) {
      return; // 用户取消选择器 → 取消保存，不下载
    }
  }
  // 无 File System Access API 时回退下载
  download(json, (state.name || 'my_animation') + '.pdraw');
  setDirty(false);
}

// 导出动画（.pdraw 供模组 /test play 播放），不改变当前工程 fileHandle
async function exportAnimation() {
  const json = JSON.stringify(exportProject());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.pdraw', types: [{ description: '工程文件', accept: { 'application/json': ['.pdraw'] } }] });
      const w = await h.createWritable();
      await w.write(json); await w.close();
      return;
    } catch (e) {
      return; // 用户取消选择器 → 取消导出，不下载
    }
  }
  // 无 File System Access API 时回退下载
  download(json, (state.name || 'my_animation') + '.pdraw');
}

// 新建空白动画
function newFile() {
  if (!confirmDiscardChanges()) return;
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {}; state.functions = [];
  state.selected.clear(); state.selectedGroup = null; state.selectedFunction = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0;
  state.name = 'my_animation';
  state.fileHandle = null;
  state.loop = true;
  document.getElementById('tl-loop').checked = true;
  updateTimeUI(); rebuildPoints(); refreshParticleTree();
  setDirty(false);
}

// 若有未保存更改，弹出保存确认。返回是否继续操作。
function confirmDiscardChanges() {
  if (!state.dirty) return true;
  const r = confirm('有未保存的更改，是否保存？\n\n「确定」= 保存后继续\n「取消」= 不保存直接继续');
  if (r) saveFile();
  return true;
}
