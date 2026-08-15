/* =========================================================================
 * 导出 / 导入 / 文件
 * ======================================================================= */

const r3 = x => Math.round(x * 1000) / 1000;
const roundArr = a => a.map(r3);
function encodeEasing(e) { return Array.isArray(e) ? e.map(r3) : e; }

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
  return {
    id: fx.id, name: fx.name, center: fx.center.slice(), count: fx.count,
    style: fx.style, code: fx.code || '',
    vars: serializeVars(fx.vars),
    duration: fx.duration, step: fx.step, preset: fx.preset, params: fx.params ? { ...fx.params } : null,
  };
}
function parseFunction(o) {
  return {
    id: o.id, name: o.name || '函数对象', center: (o.center || [0, 0, 0]).slice(0, 3), count: o.count || 30,
    style: STYLES.includes(o.style) ? o.style : 'GLOW',
    code: o.code != null ? String(o.code) : legacyCode(o),
    vars: parseVars(o.vars),
    duration: o.duration || 0, step: o.step || 5,
    preset: o.preset || null, params: o.params ? { ...o.params } : null,
  };
}

// 编辑器缓动语义：kf[k].easing 控制 k-1→k；模组语义：kf[i].easing 控制 i→i+1。
// 导出时 easing 左移一位，使模组段 i→i+1 用编辑器 kf[i+1].easing，保持一致。
function convertKfForExport(kf) {
  return kf.map((k, i) => {
    const easing = (i + 1 < kf.length) ? kf[i + 1][2] : kf[i][2];
    return [k[0], roundArr(k[1]), encodeEasing(easing)];
  });
}

// 采样点简化：移除「两端 LINEAR 插值即可近似」的中间采样点，减少导出体积与逐 tick 更新负载
function simplifyKf(kf, tol) {
  if (kf.length <= 2) return kf;
  const t = tol != null ? tol : 0.002;
  const out = [kf[0]];
  for (let i = 1; i < kf.length - 1; i++) {
    const a = out[out.length - 1];
    const b = kf[i + 1];
    const span = b[0] - a[0];
    if (span <= 0) { out.push(kf[i]); continue; }
    const f = (kf[i][0] - a[0]) / span;
    const maxErr = Math.max(...kf[i][1].map((v, j) => Math.abs(v - (a[1][j] + (b[1][j] - a[1][j]) * f))));
    if (maxErr > t) out.push(kf[i]);
  }
  out.push(kf[kf.length - 1]);
  return out;
}

// 导出动画（模组可播）：函数对象 pos op 增量烘焙进派生粒子 pos 轨道；rot/scl 转隐式组轨道
function exportJSON() {
  // 先收集函数对象 pos op 轨道（用于烘焙 t=0 增量与派生 pos 轨道）
  const fxPosOp = new Map();
  for (const tr of state.tracks) {
    const fId = tr.ids.find(id => id.startsWith('f:'));
    if (fId && tr.pr === 'pos' && tr.m === 'op') fxPosOp.set(fId.slice(2), tr);
  }
  // 派生粒子 spawn 位置 = 基础 pos + pos op 的 t=0 增量（避免首帧闪烁）
  const p = state.particles.map(pt => {
    const o = { id: pt.id, s: pt.style, c: roundArr(pt.color), sc: r3(pt.scale), g: pt.glow ? 1 : 0, l: pt.lightLevel, pos: roundArr(pt.pos), vel: roundArr(pt.vel || [0, 0, 0]) };
    if (pt.fx) {
      const opTr = fxPosOp.get(pt.fx);
      if (opTr) {
        const d = trackValueAt(opTr, 0, [0, 0, 0]);
        o.pos = roundArr([pt.pos[0] + d[0], pt.pos[1] + d[1], pt.pos[2] + d[2]]);
      }
    }
    return o;
  });
  const t = [];
  const g = {};
  for (const [name, members] of Object.entries(state.groups)) if (members.length) g[name] = members.slice();
  // 函数对象派生粒子加入隐式组（组名 = fx.id）
  for (const fx of state.functions) {
    const members = state.particles.filter(pt => pt.fx === fx.id).map(pt => pt.id);
    if (members.length) g[fx.id] = members;
  }
  for (const tr of state.tracks) {
    const fId = tr.ids.find(id => id.startsWith('f:'));
    if (fId) {
      const fxId = fId.slice(2);
      if (tr.pr === 'pos' && tr.m === 'op') continue; // 已烘焙进派生轨道，不单独输出
      const o = { pr: tr.pr, ids: ['g:' + fxId], kf: convertKfForExport(tr.kf) };
      if (tr.m === 'op') o.m = 'op';
      t.push(o);
    } else if (tr.fx) {
      const opTr = fxPosOp.get(tr.fx);
      if (tr.pr === 'pos' && opTr) {
        // 派生 pos 轨道：叠加 pos op 增量（烘焙完整位置）；easing 左移为模组语义后合并共线点
        const kf = tr.kf.map((k, i) => {
          const d = trackValueAt(opTr, k[0], [0, 0, 0]);
          const easing = (i + 1 < tr.kf.length) ? tr.kf[i + 1][2] : tr.kf[i][2];
          return [k[0], roundArr([k[1][0] + d[0], k[1][1] + d[1], k[1][2] + d[2]]), encodeEasing(easing)];
        });
        t.push({ pr: 'pos', ids: [tr.ids[0]], kf: simplifyKf(kf) });
      } else {
        const o = { pr: tr.pr, ids: tr.ids.slice(), kf: convertKfForExport(tr.kf) };
        if (tr.m === 'op') o.m = 'op';
        t.push(o);
      }
    } else {
      const o = { pr: tr.pr, ids: tr.ids.slice(), kf: convertKfForExport(tr.kf) };
      if (tr.m === 'op') o.m = 'op';
      t.push(o);
    }
  }
  return { v: 1, loop: state.loop, g, p, t };
}

// 导出工程（.pdraw）：独立粒子 + 非派生轨道 + 函数对象定义
function exportProject() {
  const p = state.particles.filter(pt => !pt.fx).map(pt => ({ id: pt.id, s: pt.style, c: roundArr(pt.color), sc: r3(pt.scale), g: pt.glow ? 1 : 0, l: pt.lightLevel, pos: roundArr(pt.pos), vel: roundArr(pt.vel || [0, 0, 0]) }));
  const t = state.tracks.filter(tr => !tr.fx).map(tr => {
    const o = { pr: tr.pr, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], roundArr(k[1]), encodeEasing(k[2])]) };
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
  state.tracks = (obj.t || []).map(tr => ({
    pr: ['pos', 'rot', 'vel', 'col', 'scl'].includes(tr.pr) ? tr.pr : 'pos',
    m: tr.m === 'op' ? 'op' : 'set',
    ids: (tr.ids || []).slice(),
    kf: (tr.kf || []).map(k => [k[0], k[1].slice(), Array.isArray(k[2]) ? k[2].slice() : (Number.isInteger(k[2]) ? k[2] : DEFAULT_EASING)]),
  }));
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
  state.dirty = false;
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

// 从 File 对象载入（文件选择与拖拽打开）
async function loadFile(file) {
  const text = await file.text();
  const obj = JSON.parse(text);
  if (file.name.toLowerCase().endsWith('.pdraw') || obj.v === 2 || obj.f) importProject(obj);
  else importJSON(obj);
  state.name = file.name.replace(/\.(json|pdraw)$/i, '');
  state.dirty = false;
  updateTopbarTitle();
}

function updateTopbarTitle() {
  const el = document.getElementById('topbar-title');
  if (el) el.textContent = state.name + '.pdraw';
}

async function openFile() {
  if (!confirmDiscardChanges()) return;
  if (window.showOpenFilePicker) {
    try {
      const [h] = await window.showOpenFilePicker({ types: [{ description: '工程/动画', accept: { 'application/json': ['.pdraw', '.json'] } }] });
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
  const json = JSON.stringify(exportProject());
  try {
    const w = await state.fileHandle.createWritable();
    await w.write(json); await w.close();
    state.dirty = false;
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
      state.dirty = false;
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  download(json, (state.name || 'my_animation') + '.pdraw');
  state.dirty = false;
}

// 导出动画（模组可播 .json），不改变当前工程 fileHandle
async function exportAnimation() {
  const json = JSON.stringify(exportJSON());
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: state.name + '.json', types: [{ description: '动画 JSON', accept: { 'application/json': ['.json'] } }] });
      const w = await h.createWritable();
      await w.write(json); await w.close();
      return;
    } catch (e) { /* 取消或失败则回退 */ }
  }
  download(json, (state.name || 'my_animation') + '.json');
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
