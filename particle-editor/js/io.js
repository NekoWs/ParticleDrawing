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

// UV 参数序列化 / 解析（外部 PNG 贴图只存名，像素存 textures/<name>.png）
function serializeUV(uv) {
  if (!uv) return undefined;
  return {
    texture: uv.texture || null,
    mode: uv.mode || 'static',
    texSize: (uv.texSize || [16, 16]).slice(0, 2),
    uvStart: (uv.uvStart || [0, 0]).slice(0, 2),
    uvSize: (uv.uvSize || [16, 16]).slice(0, 2),
    uvStep: (uv.uvStep || [16, 0]).slice(0, 2),
    fps: uv.fps != null ? uv.fps : 1,
    maxFrame: uv.maxFrame != null ? uv.maxFrame : 1,
    loop: uv.loop != null ? !!uv.loop : true,
  };
}
function parseUV(o) {
  if (!o) return undefined;
  const w = (o.texSize && o.texSize[0]) || 16, h = (o.texSize && o.texSize[1]) || 16;
  return {
    texture: o.texture || null,
    mode: UV_MODES[o.mode] ? o.mode : 'static',
    texSize: (o.texSize || [w, h]).slice(0, 2),
    uvStart: (o.uvStart || [0, 0]).slice(0, 2),
    uvSize: (o.uvSize || [0, 0]).slice(0, 2),
    uvStep: (o.uvStep || [0, 0]).slice(0, 2),
    fps: o.fps != null ? o.fps : 1,
    maxFrame: o.maxFrame != null ? o.maxFrame : 1,
    loop: o.loop != null ? !!o.loop : true,
  };
}

// 粒子序列化：省略等于默认值的字段，减小工程文件体积（解析侧均有默认回退）
function serializeParticle(pt) {
  const o = { id: pt.id, pos: roundArr(pt.pos) };
  if (!isDefaultColor(pt.color)) o.c = roundArr(pt.color);
  const s = pt.scale || [1, 1, 1];
  if (s[0] !== 1 || s[1] !== 1 || s[2] !== 1) o.sc = roundArr([s[0], s[1], s[2]]);
  if (pt.glow) o.g = 1;
  if (pt.lightLevel) o.l = pt.lightLevel;
  const v = pt.vel || [0, 0, 0];
  if (v[0] || v[1] || v[2]) o.vel = roundArr(v);
  if (pt.uv && pt.uv.texture) o.uv = serializeUV(pt.uv);
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
    code: fx.code || '',
    vars: serializeVars(fx.vars),
    duration: fx.duration, step: fx.step,
  };
  if (fx.preset) o.preset = fx.preset;
  if (fx.params) o.params = { ...fx.params };
  if (fx.ui) o.ui = JSON.parse(JSON.stringify(fx.ui));
  if (fx.uv && fx.uv.texture) o.uv = serializeUV(fx.uv);
  return o;
}
function parseFunction(o) {
  return {
    id: o.id, name: o.name || '函数对象', center: (o.center || [0, 0, 0]).slice(0, 3), count: o.count || 30,
    code: o.code != null ? String(o.code) : legacyCode(o),
    vars: parseVars(o.vars),
    duration: o.duration || 0, step: o.step || 5,
    preset: o.preset || null, params: o.params ? { ...o.params } : null,
    ui: o.ui || null,
    uv: parseUV(o.uv),
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
  const tex = Object.keys(state.textures);
  const guv = {};
  for (const [name, uv] of Object.entries(state.groupUV || {})) if (uv && uv.texture) guv[name] = serializeUV(uv);
  return { v: 3, loop: state.loop, g, p, t, f, tex, guv };
}

function parseParticlesTracks(obj) {
  state.particles = (obj.p || []).map(pt => {
    let sc;
    if (Array.isArray(pt.sc)) sc = pt.sc.slice(0, 3);
    else { const v = pt.sc != null ? pt.sc : 1; sc = [v, v, v]; }
    return {
      id: pt.id || nextId(),
      color: (pt.c || [1, 1, 1, 1]).slice(0, 4), scale: sc,
      glow: !!pt.g, lightLevel: pt.l || 0, pos: (pt.pos || [0, 0, 0]).slice(0, 3),
      vel: (pt.vel || [0, 0, 0]).slice(0, 3),
      uv: parseUV(pt.uv),
    };
  });
  state.groups = {};
  for (const [name, members] of Object.entries(obj.g || {})) state.groups[name] = members.slice();
  state.groupUV = {};
  for (const [name, uv] of Object.entries(obj.guv || {})) state.groupUV[name] = parseUV(uv);
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
  state.textures = {};
  state.currentTexture = null;
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
  state.textures = {};
  state.currentTexture = null;
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
  if (file.name.toLowerCase().endsWith('.pdraw') || obj.v >= 2 || obj.f) importProject(obj);
  else importJSON(obj);
  state.name = file.name.replace(/\.(json|pdraw)$/i, '');
  setDirty(false);
}

function updateTopbarTitle() {
  const el = document.getElementById('topbar-title');
  if (el) el.textContent = state.name + '.pdraw' + (state.dirty ? ' *' : '');
}

async function openFile() {
  if (!(await confirmDiscardChanges())) return;
  if (window.showDirectoryPicker) {
    try {
      const dir = await window.showDirectoryPicker({ mode: 'readwrite' });
      const pdraws = [];
      for await (const [name, handle] of dir.entries()) {
        if (handle.kind === 'file' && name.toLowerCase().endsWith('.pdraw')) pdraws.push({ name, handle });
      }
      if (pdraws.length === 0) { await modalAlert('提示', '该文件夹没有 .pdraw 文件'); return; }
      let h = pdraws[0].handle;
      if (pdraws.length > 1) h = await choosePdraw(pdraws);
      if (!h) return;
      state.directoryHandle = dir;
      state.fileHandle = h;
      await loadFile(await h.getFile());
      await loadTextures();
      // 贴图文件已从 textures/ 子目录加载：重建图集并让粒子立即按 UV 引用的贴图渲染
      if (typeof markTextureChanged === 'function') markTextureChanged();
      if (typeof refreshTexturePanel === 'function') refreshTexturePanel();
    } catch (e) { /* 取消则忽略 */ }
    return;
  }
  document.getElementById('file-import').click();
}

// 多 pdraw 时弹出选择列表
function choosePdraw(pdraws) {
  return new Promise((resolve) => {
    const box = document.createElement('div');
    box.className = 'pdraw-picker';
    box.innerHTML = '<div class="pp-title">选择要打开的动画</div>';
    const list = document.createElement('div');
    list.className = 'pp-list';
    for (const p of pdraws) {
      const b = document.createElement('button');
      b.className = 'mini';
      b.textContent = p.name;
      b.onclick = () => { box.remove(); resolve(p.handle); };
      list.appendChild(b);
    }
    const cancel = document.createElement('button');
    cancel.className = 'mini'; cancel.textContent = '取消';
    cancel.onclick = () => { box.remove(); resolve(null); };
    list.appendChild(cancel);
    box.appendChild(list);
    document.body.appendChild(box);
    box.style.left = (window.innerWidth / 2 - 120) + 'px';
    box.style.top = (window.innerHeight / 2 - 80) + 'px';
  });
}

// 从项目文件夹 textures/ 子目录加载全部贴图 PNG
async function loadTextures() {
  state.textures = {};
  state.currentTexture = null;
  if (!state.directoryHandle) return;
  let texDir;
  try { texDir = await state.directoryHandle.getDirectoryHandle('textures'); }
  catch (e) { return; }
  for await (const [name, handle] of texDir.entries()) {
    if (handle.kind !== 'file' || !name.toLowerCase().endsWith('.png')) continue;
    try {
      const file = await handle.getFile();
      if (file.size > 32 * 1024 * 1024) continue;
      const bmp = await createImageBitmap(file);
      const w = bmp.width, h = bmp.height;
      const cnv = document.createElement('canvas'); cnv.width = w; cnv.height = h;
      const ctx = cnv.getContext('2d'); ctx.drawImage(bmp, 0, 0);
      const data = new Uint8ClampedArray(ctx.getImageData(0, 0, w, h).data);
      const tn = name.replace(/\.png$/i, '');
      state.textures[tn] = { name: tn, width: w, height: h, data };
    } catch (e) { /* 跳过无法解码的贴图 */ }
  }
}

async function saveFile() {
  if (!state.fileHandle || !state.fileHandle.createWritable) {
    await saveFileAs();
    return;
  }
  const json = JSON.stringify(exportProject());
  await writeProjectText(state.fileHandle, json);
}

async function writeProjectText(handle, json) {
  try {
    const w = await handle.createWritable();
    await w.write(json); await w.close();
    setDirty(false);
  } catch (e) {
    await saveFileAs();
  }
}

async function saveFileAs() {
  const json = JSON.stringify(exportProject());
  // 项目文件夹模式：在文件夹内新建 .pdraw
  if (state.directoryHandle && state.directoryHandle.getFileHandle) {
    try {
      const fh = await state.directoryHandle.getFileHandle(state.name + '.pdraw', { create: true });
      state.fileHandle = fh;
      await writeProjectText(fh, json);
      return;
    } catch (e) { /* 失败则回退下载 */ }
  }
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

// 导出动画（.pdraw 供模组 /pdraw play 播放），不改变当前工程 fileHandle
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
async function newFile() {
  if (!(await confirmDiscardChanges())) return;
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {}; state.functions = [];
  state.textures = {}; state.currentTexture = null; state.groupUV = {};
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
async function confirmDiscardChanges() {
  if (!state.dirty) return true;
  const r = await modalConfirm('未保存的更改', '是否保存？\n\n确定 = 保存后继续\n取消 = 不保存直接继续');
  if (r) await saveFile();
  return true;
}
