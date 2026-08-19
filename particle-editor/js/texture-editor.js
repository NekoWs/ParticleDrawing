/* =========================================================================
 * 贴图编辑器 + 取色板 + 贴图文件管理 + UV 面板
 * 贴图数据：state.textures = { name: { width, height, data(Uint8ClampedArray RGBA) } }
 * UV 参数：继承覆盖（函数对象 fx.uv > 组 state.groupUV[gname] > 粒子 p.uv）
 * ======================================================================= */

const TEX_UV_COLOR = '#5b9dff'; // UV 预览描边（实线，与选中态 --accent 一致）

const texState = {
  tool: 'select',           // select | pencil | eraser | bucket | picker
  color: [255, 255, 255, 255], // RGBA 0-255（当前颜色，含透明度）
  brushSize: 1,             // 铅笔/橡皮大小，1~128
  zoom: 8,                  // 显示缩放，0.1~32
  panX: 0, panY: 0,         // 平移（CSS px）
  selection: null,          // { x0, y0, x1, y1 } 像素，未归一化
  selecting: null,          // 正在拖选区的起始点
  hover: null,              // 当前悬停像素 { x, y }（铅笔/橡皮悬停描边用）
  undoStack: [], redoStack: [],
};

let texActive = false; // 鼠标是否在贴图编辑器区域内（用于 Ctrl+Z/Y 分流到贴图撤销）

// 实时时间（秒），驱动 flipbook 帧（与 shader uTime 一致）
function texAnimTime() { return performance.now() / 1000; }

/* =========================================================================
 * 贴图数据访问
 * ======================================================================= */

function getTexture(name) { return state.textures ? state.textures[name] : null; }
function getCurrentTexture() {
  return state.currentTexture ? getTexture(state.currentTexture) : null;
}

// 贴图数据变化时：重建 atlas 并刷新粒子渲染
function markTextureChanged() {
  if (typeof rebuildAtlas === 'function') rebuildAtlas();
  if (typeof rebuildPoints === 'function') rebuildPoints();
  setDirty(true);
}

// 当前空/新贴图尺寸（无贴图时 16×16）
function currentTexSize() {
  const t = getCurrentTexture();
  return t ? { w: t.width, h: t.height } : { w: 16, h: 16 };
}

// 规范化 UV 参数（贴图大小默认贴图分辨率，其余数值字段默认 0）
function normalizeUV(uv) {
  if (!uv) uv = {};
  const t = getTexture(uv.texture);
  const w = t ? t.width : ((uv.texSize && uv.texSize[0]) || 16);
  const h = t ? t.height : ((uv.texSize && uv.texSize[1]) || 16);
  const d = defaultUV(w, h);
  return {
    texture: uv.texture || null,
    mode: UV_MODES[uv.mode] ? uv.mode : d.mode,
    texSize: (uv.texSize || d.texSize).slice(0, 2),
    uvStart: (uv.uvStart || d.uvStart).slice(0, 2),
    uvSize: (uv.uvSize || d.uvSize).slice(0, 2),
    uvStep: (uv.uvStep || d.uvStep).slice(0, 2),
    fps: uv.fps != null ? uv.fps : d.fps,
    maxFrame: uv.maxFrame != null ? uv.maxFrame : d.maxFrame,
    loop: uv.loop != null ? !!uv.loop : d.loop,
  };
}

// UV 继承查询（f > g > p）；返回「生效的 UV 参数」与「来源」
function resolveUV(p) {
  if (p.uv && p.uv.texture) return { uv: normalizeUV(p.uv), src: p.id };
  const gs = groupMemberIndexCache && groupMemberIndexCache.get(p.id);
  if (gs) {
    for (const gname of gs) {
      const guv = state.groupUV && state.groupUV[gname];
      if (guv && guv.texture) return { uv: normalizeUV(guv), src: 'g:' + gname };
    }
  }
  if (p.fx) {
    const fx = getFunction(p.fx);
    if (fx && fx.uv && fx.uv.texture) return { uv: normalizeUV(fx.uv), src: 'f:' + fx.id };
  }
  return { uv: defaultUV(16, 16), src: null };
}

// 当前编辑作用域（函数对象 > 组 > 粒子多选）
function currentUVTarget() {
  if (state.selectedFunction) return { kind: 'fx', key: state.selectedFunction };
  const g = selectedGroupName();
  if (g) return { kind: 'group', key: g };
  if (state.selected.size > 0) return { kind: 'particle', key: [...state.selected] };
  return null;
}

function readTargetUV(t) {
  if (!t) return null;
  if (t.kind === 'fx') { const fx = getFunction(t.key); return fx ? normalizeUV(fx.uv) : null; }
  if (t.kind === 'group') { return state.groupUV[t.key] ? normalizeUV(state.groupUV[t.key]) : null; }
  const p = getParticle(t.key[0]); return p ? normalizeUV(p.uv) : null;
}

function writeTargetUV(t, uv) {
  if (!t) return;
  pushUndo();
  if (t.kind === 'fx') { const fx = getFunction(t.key); if (fx) fx.uv = uv; }
  else if (t.kind === 'group') { state.groupUV[t.key] = uv; }
  else { for (const id of t.key) { const p = getParticle(id); if (p && !isDerivedParticle(p)) p.uv = uv; } }
  setDirty(true);
  if (typeof rebuildPoints === 'function') rebuildPoints(false);
}

/* =========================================================================
 * 画布渲染
 * ======================================================================= */

const texCanvas = () => document.getElementById('tex-canvas');
const texCanvasWrap = () => document.getElementById('tex-canvas-wrap');

// 只在尺寸变化时设置 canvas 内尺寸（设置 width/height 会清空内容，避免无谓重置）
function ensureCanvasSize() {
  const c = texCanvas();
  const { w, h } = currentTexSize();
  if (c.width !== w || c.height !== h) { c.width = w; c.height = h; }
}

// 仅更新 CSS 尺寸/平移（不清空像素内容）
function applyTexView() {
  const c = texCanvas();
  const { w, h } = currentTexSize();
  c.style.width = Math.round(w * texState.zoom) + 'px';
  c.style.height = Math.round(h * texState.zoom) + 'px';
  c.style.transform = 'translate(' + texState.panX + 'px, ' + texState.panY + 'px)';
  // canvas 的视口位置变化后，预览描边 overlay 需重新定位对齐像素
  updateTexOverlay();
}

function renderTexCanvas() {
  const c = texCanvas();
  if (!c) return;
  ensureCanvasSize();
  applyTexView();
  const ctx = c.getContext('2d');
  const { w, h } = currentTexSize();
  ctx.clearRect(0, 0, w, h);
  const img = ctx.createImageData(w, h);
  const t = getCurrentTexture();
  if (t && t.data) img.data.set(t.data.slice(0, w * h * 4));
  ctx.putImageData(img, 0, 0);
  updateTexOverlay();
  updateTexMeta();
}

// 黑白对比描边颜色：采样矩形外圈像素亮度判断
function contrastColorAt(x0, y0, w, h) {
  const t = getCurrentTexture();
  if (!t) return '#ffffff';
  let sum = 0, n = 0;
  const sample = (px, py) => {
    if (px < 0 || py < 0 || px >= t.width || py >= t.height) return;
    const i = (py * t.width + px) * 4;
    sum += 0.299 * t.data[i] + 0.587 * t.data[i + 1] + 0.114 * t.data[i + 2];
    n++;
  };
  for (let px = x0; px < x0 + w; px++) { sample(px, y0 - 1); sample(px, y0 + h); }
  for (let py = y0; py < y0 + h; py++) { sample(x0 - 1, py); sample(x0 + w, py); }
  const avg = n ? sum / n : 128;
  return avg > 128 ? '#000000' : '#ffffff';
}

// UV 预览当前帧（动画模式按墙钟计算；与 shader uTime 一致）。
// 帧数 = 常量层共享的自动帧数（按 UV 步长+贴图尺寸）∩ 用户上限，与渲染 render 完全一致。
function currentUVFrame(uv) {
  if (!uv) return 0;
  const t = getTexture(uv.texture);
  const autoFrames = autoFramesFor(uv, t ? t.width : 16, t ? t.height : 16);
  const maxF = effMaxFrame(uv, autoFrames);
  const frame = Math.floor(texAnimTime() * (uv.fps || 1));
  return uv.loop ? ((frame % maxF) + maxF) % maxF : Math.min(frame, maxF - 1);
}

// 预览描边统一走 CSS overlay（.tex-overlay）层：
// 在贴图像素边缘绘制屏幕 1px 的细线，不写入像素数据，放大后依旧是细线
function updateTexOverlay() {
  const wrap = texCanvasWrap();
  const c = texCanvas();
  const frame = document.getElementById('tex-overlay-frame');
  const uv = document.getElementById('tex-overlay-uv');
  if (!wrap || !c || !frame || !uv) return;
  const wr = wrap.getBoundingClientRect();
  const cr = c.getBoundingClientRect();
  const ox = cr.left - wr.left, oy = cr.top - wr.top; // canvas 相对 wrap 的偏移（已含居中与 pan 平移）
  const z = texState.zoom;
  const setBox = (el, x, y, w, h, color, dashed) => {
    if (w <= 0 || h <= 0) { el.style.display = 'none'; return; }
    el.style.display = 'block';
    el.style.left = (ox + x * z) + 'px';
    el.style.top = (oy + y * z) + 'px';
    el.style.width = Math.max(1, Math.round(w * z)) + 'px';
    el.style.height = Math.max(1, Math.round(h * z)) + 'px';
    el.style.borderColor = color;
    el.style.borderStyle = dashed ? 'dashed' : 'solid';
  };

  // UV 预览框（浅蓝虚线；fill 全图；静态/动画显示当前采样区域）
  const t = currentUVTarget();
  if (t) {
    const u = readTargetUV(t);
    if (u && u.texture) {
      if (u.mode === 'fill') {
        const s = currentTexSize();
        setBox(uv, 0, 0, s.w, s.h, TEX_UV_COLOR, false);
      } else {
        let sx = u.uvStart[0], sy = u.uvStart[1], sw = u.uvSize[0], sh = u.uvSize[1];
        if (u.mode === 'animated') {
          const f = currentUVFrame(u);
          sx += u.uvStep[0] * f; sy += u.uvStep[1] * f;
        }
        setBox(uv, sx, sy, sw, sh, TEX_UV_COLOR, false);
      }
    } else setBox(uv, 0, 0, 0, 0, '', false);
  } else setBox(uv, 0, 0, 0, 0, '', false);

  // 悬停描边（铅笔/橡皮：显示将绘制/擦除的刷子范围）优先于选区描边
  const hov = texState.hover;
  if (hov && (texState.tool === 'pencil' || texState.tool === 'eraser')) {
    const r = Math.floor(texState.brushSize / 2);
    setBox(frame, hov.x - r, hov.y - r, texState.brushSize, texState.brushSize,
      contrastColorAt(hov.x - r, hov.y - r, texState.brushSize, texState.brushSize), false);
  } else if (texState.selection) {
    const s = texState.selection;
    const x = Math.floor(Math.min(s.x0, s.x1)), y = Math.floor(Math.min(s.y0, s.y1));
    const w = Math.floor(Math.abs(s.x1 - s.x0)), h = Math.floor(Math.abs(s.y1 - s.y0));
    setBox(frame, x, y, w, h, contrastColorAt(x, y, w, h), false);
  } else {
    setBox(frame, 0, 0, 0, 0, '', false);
  }
}

function updateTexMeta() {
  const el = document.getElementById('tex-meta');
  if (el) { const { w, h } = currentTexSize(); el.textContent = w + ' × ' + h; }
}

function texPixelAt(ev) {
  const c = texCanvas();
  const rect = c.getBoundingClientRect();
  return {
    x: Math.floor((ev.clientX - rect.left) / texState.zoom),
    y: Math.floor((ev.clientY - rect.top) / texState.zoom),
  };
}

// 在像素 (x,y) 画一块（含选区限制、画笔大小），返回是否修改
function paintArea(x, y, apply) {
  const { w, h } = currentTexSize();
  const r = Math.floor(texState.brushSize / 2);
  const x0 = Math.max(0, x - r), x1 = Math.min(w, x + r + 1);
  const y0 = Math.max(0, y - r), y1 = Math.min(h, y + r + 1);
  let changed = false;
  for (let py = y0; py < y1; py++) {
    for (let px = x0; px < x1; px++) {
      if (!pixelInSelection(px, py)) continue;
      if (apply(px, py)) changed = true;
    }
  }
  return changed;
}
function pixelInSelection(x, y) {
  const s = texState.selection;
  if (!s) return true;
  return x >= Math.min(s.x0, s.x1) && x < Math.max(s.x0, s.x1) && y >= Math.min(s.y0, s.y1) && y < Math.max(s.y0, s.y1);
}

function pushTexUndo() {
  const t = getCurrentTexture();
  const { w, h } = currentTexSize();
  const snap = t && t.data ? t.data.slice(0, w * h * 4) : null;
  texState.undoStack.push(snap);
  if (texState.undoStack.length > 100) texState.undoStack.shift();
  texState.redoStack.length = 0;
}
function texUndo() {
  const t = getCurrentTexture();
  if (!t || texState.undoStack.length === 0) return;
  texState.redoStack.push(t.data.slice());
  t.data = texState.undoStack.pop();
  renderTexCanvas();
  if (typeof rebuildAtlas === 'function') rebuildAtlas();
  setDirty(true);
}
function texRedo() {
  const t = getCurrentTexture();
  if (!t || texState.redoStack.length === 0) return;
  texState.undoStack.push(t.data.slice());
  t.data = texState.redoStack.pop();
  renderTexCanvas();
  if (typeof rebuildAtlas === 'function') rebuildAtlas();
  setDirty(true);
}

function texSetPixel(px, py) {
  const t = getCurrentTexture();
  if (!t) return;
  const i = (py * t.width + px) * 4;
  t.data[i] = texState.color[0];
  t.data[i + 1] = texState.color[1];
  t.data[i + 2] = texState.color[2];
  t.data[i + 3] = texState.color[3];
}
function texGetPixel(px, py) {
  const t = getCurrentTexture();
  if (!t || px < 0 || py < 0 || px >= t.width || py >= t.height) return null;
  const i = (py * t.width + px) * 4;
  return [t.data[i], t.data[i + 1], t.data[i + 2], t.data[i + 3]];
}
function texErasePixel(px, py) {
  const t = getCurrentTexture();
  if (!t) return;
  const i = (py * t.width + px) * 4;
  t.data[i] = 0; t.data[i + 1] = 0; t.data[i + 2] = 0; t.data[i + 3] = 0;
}
function floodFill(px, py, target, apply) {
  const { w, h } = currentTexSize();
  const key = (x, y) => y * w + x;
  const start = texGetPixel(px, py);
  if (!start) return 0;
  const eq = (a, b) => a[0] === b[0] && a[1] === b[1] && a[2] === b[2] && a[3] === b[3];
  if (eq(start, target)) return 0;
  const stack = [[px, py]];
  const seen = new Uint8Array(w * h);
  let n = 0;
  while (stack.length) {
    const [x, y] = stack.pop();
    if (x < 0 || y < 0 || x >= w || y >= h) continue;
    if (seen[key(x, y)]) continue;
    seen[key(x, y)] = 1;
    const cur = texGetPixel(x, y);
    if (!eq(cur, start)) continue;
    if (!pixelInSelection(x, y)) continue;
    apply(x, y);
    n++;
    stack.push([x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]);
  }
  return n;
}

/* =========================================================================
 * 编辑器交互（事件挂到 wrap，兼容灰色区域平移/缩放）
 * ======================================================================= */

let texDrag = null; // { mode: 'draw'|'pan'|'select'|'erase', last }

function initTextureEditor() {
  const wrap = texCanvasWrap();
  const c = texCanvas();
  if (!wrap || !c) return;
  refreshTexturePanel();

  wrap.addEventListener('pointerenter', () => { texActive = true; });
  wrap.addEventListener('pointerleave', () => { texActive = false; });

  wrap.addEventListener('pointerdown', (ev) => {
    ev.preventDefault();
    if (ev.button === 2) { // 右键拖动：平移（灰色区域亦有效）
      texDrag = { mode: 'pan', x: ev.clientX, y: ev.clientY, panX: texState.panX, panY: texState.panY };
      wrap.setPointerCapture(ev.pointerId);
      return;
    }
    if (ev.button !== 0) return;
    const p = texPixelAt(ev);
    if (ev.altKey) return;
    if (texState.tool === 'select') {
      texDrag = { mode: 'select', x0: p.x, y0: p.y };
      texState.selecting = { x0: p.x, y0: p.y };
      wrap.setPointerCapture(ev.pointerId);
      return;
    }
    const { w, h } = currentTexSize();
    if (p.x < 0 || p.y < 0 || p.x >= w || p.y >= h) return; // 画布外不绘制
    if (texState.tool === 'picker') {
      const col = texGetPixel(p.x, p.y);
      if (col) { texState.color = col.slice(); updateColorButton(); }
      return;
    }
    if (texState.tool === 'bucket') {
      pushTexUndo();
      if (!getCurrentTexture()) return;
      if (texState.selection) {
        paintArea(Math.min(texState.selection.x0, texState.selection.x1), Math.min(texState.selection.y0, texState.selection.y1), (x, y) => { texSetPixel(x, y); return true; });
      } else {
        floodFill(p.x, p.y, texState.color, texSetPixel);
      }
      renderTexCanvas();
      if (typeof rebuildAtlas === 'function') rebuildAtlas();
      setDirty(true);
      return;
    }
    texDrag = { mode: texState.tool, last: p };
    pushTexUndo();
    applyStroke(p, texDrag.mode);
  });

  wrap.addEventListener('pointermove', (ev) => {
    if (texDrag) {
      if (texDrag.mode === 'pan') {
        texState.panX = texDrag.panX + (ev.clientX - texDrag.x);
        texState.panY = texDrag.panY + (ev.clientY - texDrag.y);
        applyTexView();
        return;
      }
      if (texDrag.mode === 'select') {
        const p = texPixelAt(ev);
        texState.selection = { x0: texDrag.x0, y0: texDrag.y0, x1: p.x, y1: p.y };
        renderTexCanvas();
        return;
      }
      const p = texPixelAt(ev);
      applyStroke(p, texDrag.mode);
      return;
    }
    // 悬停描边（pencil/eraser）
    if (texState.tool === 'pencil' || texState.tool === 'eraser') {
      const p = texPixelAt(ev);
      if (!texState.hover || texState.hover.x !== p.x || texState.hover.y !== p.y) {
        texState.hover = p;
        renderTexCanvas();
      }
    }
  });

  wrap.addEventListener('pointerup', () => {
    if (texDrag && texDrag.mode === 'select') {
      const s = texState.selection;
      if (s && Math.abs(s.x1 - s.x0) < 1 && Math.abs(s.y1 - s.y0) < 1) texState.selection = null;
      renderTexCanvas();
    }
    texDrag = null;
    texState.selecting = null;
  });

  wrap.addEventListener('wheel', (ev) => {
    ev.preventDefault();
    if (ev.altKey) {
      const d = ev.deltaY < 0 ? 1 : -1;
      texState.brushSize = Math.max(1, Math.min(128, texState.brushSize + d));
      renderTexCanvas();
      return;
    }
    const factor = ev.deltaY < 0 ? 1.15 : 1 / 1.15;
    texState.zoom = Math.max(0.1, Math.min(32, texState.zoom * factor));
    applyTexView();
  }, { passive: false });

  wrap.addEventListener('contextmenu', (ev) => ev.preventDefault());

  // 工具切换
  document.getElementById('tex-tools').addEventListener('click', (ev) => {
    const btn = ev.target.closest('.tex-tool');
    if (!btn) return;
    if (btn.id === 'tex-undo') { texUndo(); return; }
    if (btn.id === 'tex-redo') { texRedo(); return; }
    texState.tool = btn.dataset.ttool;
    document.querySelectorAll('.tex-tool[data-ttool]').forEach(b => b.classList.toggle('active', b === btn));
    toggleColorButton(texState.tool === 'pencil' || texState.tool === 'bucket');
    if (texState.tool !== 'pencil' && texState.tool !== 'eraser') texState.hover = null;
    renderTexCanvas();
  });

  // 颜色按钮 → 取色板
  document.getElementById('tex-color-btn').addEventListener('click', (ev) => {
    openColorPicker(ev.clientX, ev.clientY, texState.color, (rgba) => {
      texState.color = rgba;
      updateColorButton();
    });
  });

  // 文件操作
  document.getElementById('btn-tex-upload').addEventListener('click', () => document.getElementById('tex-upload').click());
  document.getElementById('tex-upload').addEventListener('change', async (ev) => { const f = ev.target.files[0]; if (f) await uploadTextureFile(f); ev.target.value = ''; });
  document.getElementById('btn-tex-new').addEventListener('click', newTextureDialog);
  document.getElementById('btn-tex-save').addEventListener('click', saveTexture);

  updateColorButton();
}

function toggleColorButton(show) {
  document.getElementById('tex-color-btn').style.display = show ? 'block' : 'none';
}
function updateColorButton() {
  const btn = document.getElementById('tex-color-btn');
  if (btn) btn.style.background = rgbaToCss(texState.color);
}

// 铅笔/橡皮一笔（连线补间）
function applyStroke(p, mode) {
  if (!texDrag || !texDrag.last) { paintAt(p, mode); texDrag.last = p; return; }
  const a = texDrag.last, b = p;
  const steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.y - a.y));
  for (let i = 0; i <= steps; i++) {
    const x = Math.round(a.x + (b.x - a.x) * i / Math.max(1, steps));
    const y = Math.round(a.y + (b.y - a.y) * i / Math.max(1, steps));
    paintAt({ x, y }, mode);
  }
  texDrag.last = p;
}
function paintAt(p, mode) {
  const t = getCurrentTexture();
  if (!t) return;
  if (mode === 'eraser') paintArea(p.x, p.y, (x, y) => { texErasePixel(x, y); return true; });
  else paintArea(p.x, p.y, (x, y) => { texSetPixel(x, y); return true; });
  renderTexCanvas();
  if (typeof rebuildAtlas === 'function') rebuildAtlas();
  setDirty(true);
}

/* =========================================================================
 * 取色板（HSV + alpha）
 * ======================================================================= */

function rgbaToCss(rgba) {
  return 'rgba(' + rgba[0] + ',' + rgba[1] + ',' + rgba[2] + ',' + (rgba[3] / 255).toFixed(3) + ')';
}

function openColorPicker(x, y, rgba, onCommit) {
  closeColorPicker();
  const box = document.createElement('div');
  box.className = 'color-picker';
  box.id = 'color-picker-pop';

  const sv = document.createElement('div'); sv.className = 'cp-sv';
  const svThumb = document.createElement('div'); svThumb.className = 'cp-sv-thumb';
  sv.appendChild(svThumb);
  const hue = document.createElement('div'); hue.className = 'cp-hue';
  const alpha = document.createElement('div'); alpha.className = 'cp-alpha';
  const row = document.createElement('div'); row.className = 'cp-row';
  const hex = document.createElement('input'); hex.className = 'cp-hex'; hex.type = 'text'; hex.maxLength = 9;
  const okBtn = document.createElement('button');
  okBtn.className = 'mini'; okBtn.textContent = '确定';
  row.appendChild(hex); row.appendChild(okBtn);

  let hsv = rgbToHsv(rgba);
  let a = rgba[3] / 255;

  const paintSV = () => { sv.style.background = 'linear-gradient(to top, #000, transparent), linear-gradient(to right, #fff, hsl(' + hsv[0] + ',100%,50%))'; };
  const paintHue = () => { hue.style.background = 'linear-gradient(to right, #f00,#ff0,#0f0,#0ff,#00f,#f0f,#f00)'; };
  const paintAlpha = () => { const c = hsvToRgb([hsv[0], hsv[1], hsv[2]]); alpha.style.background = 'linear-gradient(to right, transparent, rgb(' + c[0] + ',' + c[1] + ',' + c[2] + '))'; };
  const sync = () => {
    svThumb.style.left = (hsv[1] * 100) + '%';
    svThumb.style.top = ((1 - hsv[2]) * 100) + '%';
    const c = hsvToRgb([hsv[0], hsv[1], hsv[2]]);
    hex.value = '#' + c.map(v => v.toString(16).padStart(2, '0')).join('') + (a < 1 ? Math.round(a * 255).toString(16).padStart(2, '0') : '');
    paintAlpha();
  };
  paintSV(); paintHue(); sync();

  sv.addEventListener('pointerdown', (ev) => {
    sv.setPointerCapture(ev.pointerId);
    const move = (e) => {
      const r = sv.getBoundingClientRect();
      hsv[1] = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
      hsv[2] = 1 - Math.max(0, Math.min(1, (e.clientY - r.top) / r.height));
      sync();
    };
    move(ev);
    sv.addEventListener('pointermove', move);
    sv.addEventListener('pointerup', () => sv.removeEventListener('pointermove', move), { once: true });
  });
  hue.addEventListener('pointerdown', (ev) => {
    hue.setPointerCapture(ev.pointerId);
    const move = (e) => {
      const r = hue.getBoundingClientRect();
      hsv[0] = Math.max(0, Math.min(360, (e.clientX - r.left) / r.width * 360));
      paintSV(); sync();
    };
    move(ev);
    hue.addEventListener('pointermove', move);
    hue.addEventListener('pointerup', () => hue.removeEventListener('pointermove', move), { once: true });
  });
  alpha.addEventListener('pointerdown', (ev) => {
    alpha.setPointerCapture(ev.pointerId);
    const move = (e) => {
      const r = alpha.getBoundingClientRect();
      a = Math.max(0, Math.min(1, (e.clientX - r.left) / r.width));
      sync();
    };
    move(ev);
    alpha.addEventListener('pointermove', move);
    alpha.addEventListener('pointerup', () => alpha.removeEventListener('pointermove', move), { once: true });
  });

  const commit = () => {
    const c = hsvToRgb([hsv[0], hsv[1], hsv[2]]);
    const out = [c[0], c[1], c[2], Math.round(a * 255)];
    texState.color = out;
    updateColorButton();
    closeColorPicker();
    if (onCommit) onCommit(out);
  };
  hex.addEventListener('change', () => {
    const v = hex.value.replace('#', '');
    if (/^[0-9a-fA-F]{6}$/.test(v)) {
      hsv = rgbToHsv([parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)]);
      paintSV(); sync();
    } else if (/^[0-9a-fA-F]{8}$/.test(v)) {
      hsv = rgbToHsv([parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)]);
      a = parseInt(v.slice(6, 8), 16) / 255;
      paintSV(); sync();
    }
  });
  okBtn.onclick = commit;

  box.appendChild(sv); box.appendChild(hue); box.appendChild(alpha); box.appendChild(row);
  document.body.appendChild(box);
  box.style.left = Math.min(x, window.innerWidth - 240) + 'px';
  box.style.top = Math.min(y, window.innerHeight - 250) + 'px';
}
function closeColorPicker() { const b = document.getElementById('color-picker-pop'); if (b) b.remove(); }
window.addEventListener('pointerdown', (e) => { if (!e.target.closest('#color-picker-pop') && !e.target.closest('#tex-color-btn')) closeColorPicker(); });

function rgbToHsv(rgb) {
  const r = rgb[0] / 255, g = rgb[1] / 255, b = rgb[2] / 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6;
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h *= 60; if (h < 0) h += 360;
  }
  const s = max === 0 ? 0 : d / max;
  return [h, s, max];
}
function hsvToRgb(hsv) {
  const h = hsv[0], s = hsv[1], v = hsv[2];
  const c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
  let r = 0, g = 0, b = 0;
  if (h < 60) { r = c; g = x; } else if (h < 120) { r = x; g = c; } else if (h < 180) { g = c; b = x; }
  else if (h < 240) { g = x; b = c; } else if (h < 300) { r = x; b = c; } else { r = c; b = x; }
  return [Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255)];
}

/* =========================================================================
 * 文件：上传 / 新建 / 保存
 * ======================================================================= */

function makeTexture(name, w, h, data) {
  const t = { name, width: w, height: h, data: data || new Uint8ClampedArray(w * h * 4) };
  state.textures[name] = t;
  state.currentTexture = name;
  texState.selection = null;
  texState.zoom = 8; texState.panX = 0; texState.panY = 0;
  texState.undoStack.length = 0; texState.redoStack.length = 0;
  return t;
}

async function uploadTextureFile(file) {
  if (file.size > 5 * 1024 * 1024) { await modalAlert('上传失败', '贴图超过 5MB 上限'); return; }
  if (!file.name.toLowerCase().endsWith('.png')) { await modalAlert('上传失败', '仅支持 PNG 格式'); return; }
  let bmp;
  try { bmp = await createImageBitmap(file); }
  catch (e) { await modalAlert('上传失败', '无法解析该 PNG 文件'); return; }
  const w = bmp.width, h = bmp.height;
  const cnv = document.createElement('canvas'); cnv.width = w; cnv.height = h;
  const ctx = cnv.getContext('2d'); ctx.drawImage(bmp, 0, 0);
  const data = new Uint8ClampedArray(ctx.getImageData(0, 0, w, h).data);
  const base = file.name.replace(/\.png$/i, '') || 'tex';
  const name = await modalPrompt('上传贴图', base, '贴图名称');
  if (!name || !name.trim()) return;
  let n = name.trim(), k = 1;
  while (getTexture(n)) n = name.trim() + '_' + (k++);
  makeTexture(n, w, h, data);
  renderTexCanvas();
  markTextureChanged();
  refreshUVPanel();
  refreshTexList();
}

async function newTextureDialog() {
  const name = await modalPrompt('新建贴图', '', '贴图名称');
  if (!name || !name.trim()) return;
  const res = await modalPrompt('贴图分辨率', '16 16', '宽 高（如 16 16）');
  if (!res) return;
  const parts = res.trim().split(/[\sx×X,]+/).map(v => parseInt(v)).filter(v => v >= 1);
  if (parts.length < 2) { await modalAlert('参数错误', '请输入「宽 高」两个正整数'); return; }
  const w = Math.min(Math.max(1, parts[0]), 4096), h = Math.min(Math.max(1, parts[1]), 4096);
  let n = name.trim(), k = 1;
  while (getTexture(n)) n = name.trim() + '_' + (k++);
  makeTexture(n, w, h);
  renderTexCanvas();
  markTextureChanged();
  refreshUVPanel();
  refreshTexList();
}

async function saveTexture() {
  const t = getCurrentTexture();
  if (!t) { await modalAlert('保存失败', '当前无贴图，请先新建或上传'); return; }
  const writePNG = async (h) => {
    const cnv = document.createElement('canvas'); cnv.width = t.width; cnv.height = t.height;
    const ctx = cnv.getContext('2d');
    ctx.putImageData(new ImageData(t.data.slice(), t.width, t.height), 0, 0);
    const blob = await new Promise(res => cnv.toBlob(res, 'image/png'));
    if (h && h.createWritable) {
      const w = await h.createWritable(); await w.write(blob); await w.close();
    } else {
      const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = t.name + '.png'; a.click(); URL.revokeObjectURL(a.href);
    }
  };
  // 项目文件夹模式：自动在工程目录下创建 textures 子目录
  if (state.directoryHandle && state.directoryHandle.getDirectoryHandle) {
    try {
      const dir = await state.directoryHandle.getDirectoryHandle('textures', { create: true });
      const fh = await dir.getFileHandle(t.name + '.png', { create: true });
      await writePNG(fh);
      setDirty(false);
      return;
    } catch (e) { /* 失败回退下载 */ }
  }
  if (window.showSaveFilePicker) {
    try {
      const h = await window.showSaveFilePicker({ suggestedName: t.name + '.png', types: [{ description: 'PNG', accept: { 'image/png': ['.png'] } }] });
      await writePNG(h);
      setDirty(false);
    } catch (e) { /* 取消 */ }
    return;
  }
  await writePNG(null);
  setDirty(false);
}

/* =========================================================================
 * 贴图改名 / 删除（右键菜单，复用 tree.js 的 showContextMenu）
 * ======================================================================= */

// 获取项目文件夹 textures 子目录（不存在则创建）；无项目文件夹时返回 null
async function texturesDirHandle() {
  if (!state.directoryHandle || !state.directoryHandle.getDirectoryHandle) return null;
  try { return await state.directoryHandle.getDirectoryHandle('textures', { create: true }); }
  catch (e) { return null; }
}

// 把全部对象 UV 里对 oldName 的贴图引用改为 newName（newName 为 null = 清空为无贴图）
function replaceTextureRef(oldName, newName) {
  const set = uv => { if (uv && uv.texture === oldName) uv.texture = newName; };
  for (const p of state.particles) set(p.uv);
  for (const fx of state.functions) set(fx.uv);
  for (const gname of Object.keys(state.groupUV)) set(state.groupUV[gname]);
  if (state.currentTexture === oldName) state.currentTexture = newName;
}

async function renameTextureItem(oldName) {
  const t = getTexture(oldName);
  if (!t) return;
  const res = await modalPrompt('重命名贴图', oldName, '新名称');
  if (!res) return;
  const name = res.trim();
  if (!name) { await modalAlert('重命名失败', '名称不能为空'); return; }
  if (name === oldName) return;
  if (getTexture(name)) { await modalAlert('重命名失败', '已存在同名贴图「' + name + '」'); return; }
  // 项目文件夹模式：同步重命名 textures/<old>.png → textures/<new>.png
  const dir = await texturesDirHandle();
  if (dir) {
    try {
      const fh = await dir.getFileHandle(oldName + '.png');
      if (fh.move) await fh.move(dir, name + '.png');
      else {
        const file = await fh.getFile();
        const nfh = await dir.getFileHandle(name + '.png', { create: true });
        const w = await nfh.createWritable();
        await w.write(file); await w.close();
        await dir.removeEntry(oldName + '.png');
      }
    } catch (e) { /* 文件同步失败仅改内存态 */ }
  }
  // 内存态改名 + 同步全部引用
  const nt = { name, width: t.width, height: t.height, data: t.data };
  delete state.textures[oldName];
  state.textures[name] = nt;
  replaceTextureRef(oldName, name);
  renderTexCanvas();
  markTextureChanged();
  refreshTexList();
  refreshUVPanel();
}

async function deleteTextureItem(name) {
  const ok = await modalConfirm('删除贴图', '确定删除「' + name + '」吗？\n引用它的对象将恢复为无贴图。');
  if (!ok) return;
  // 项目文件夹模式：删除 textures/<name>.png
  const dir = await texturesDirHandle();
  if (dir) {
    try { await dir.removeEntry(name + '.png'); } catch (e) { /* 文件删除失败仅改内存态 */ }
  }
  delete state.textures[name];
  if (state.currentTexture === name) state.currentTexture = null;
  replaceTextureRef(name, null);
  texState.selection = null;
  renderTexCanvas();
  markTextureChanged();
  refreshTexList();
  refreshUVPanel();
}

/* =========================================================================
 * 贴图列表（文件管理器预留区域）
 * ======================================================================= */

function refreshTexList() {
  const box = document.getElementById('tex-list');
  if (!box) return;
  box.innerHTML = '';
  const names = Object.keys(state.textures);
  if (names.length === 0) return;
  for (const name of names) {
    const t = state.textures[name];
    const item = document.createElement('button');
    item.className = 'tex-item' + (name === state.currentTexture ? ' active' : '');
    item.title = name;
    const cnv = document.createElement('canvas');
    cnv.width = t.width; cnv.height = t.height;
    const ctx = cnv.getContext('2d');
    ctx.putImageData(new ImageData(t.data.slice(), t.width, t.height), 0, 0);
    cnv.classList.add('tex-item-thumb');
    const label = document.createElement('span');
    label.className = 'tex-item-name';
    label.textContent = name;
    item.appendChild(cnv); item.appendChild(label);
    item.onclick = () => {
      state.currentTexture = name;
      texState.selection = null;
      texState.zoom = 8; texState.panX = 0; texState.panY = 0;
      renderTexCanvas();
      refreshTexList();
      refreshUVPanel();
    };
    item.oncontextmenu = (e) => {
      e.preventDefault();
      e.stopPropagation();
      showContextMenu(e.clientX, e.clientY, [
        { label: '重命名', action: () => renameTextureItem(name) },
        { label: '删除', danger: true, action: () => deleteTextureItem(name) },
      ]);
    };
    box.appendChild(item);
  }
}

/* =========================================================================
 * UV 面板
 * ======================================================================= */

function refreshTexturePanel() {
  renderTexCanvas();
  refreshUVPanel();
  refreshTexList();
}

function refreshUVPanel() {
  const box = document.getElementById('uv-panel');
  if (!box) return;
  box.innerHTML = '';
  const target = currentUVTarget();
  if (!target) { box.innerHTML = '<p class="hint">选中对象以编辑贴图 / UV</p>'; return; }
  const uv = readTargetUV(target) || defaultUV(16, 16);

  // 贴图引用下拉
  const texRow = document.createElement('label'); texRow.className = 'row';
  texRow.appendChild(document.createTextNode('贴图 '));
  const texSel = document.createElement('select');
  texSel.appendChild(new Option('（无）', ''));
  for (const name of Object.keys(state.textures)) texSel.appendChild(new Option(name, name));
  texSel.value = uv.texture || '';
  texSel.onchange = () => {
    const name = texSel.value || null;
    const nu = normalizeUV(readTargetUV(target) || {});
    nu.texture = name;
    const t = getTexture(name);
    if (t && name) {
      nu.texSize = [t.width, t.height]; // 贴图大小默认跟贴图分辨率
      // 选贴图时若 UV 采样大小仍为默认 0（未自定义过），默认覆盖整张贴图，
      // 避免静态/动画模式刚选贴图时采样 0 区域导致粒子看不到贴图
      if (!nu.uvSize || (nu.uvSize[0] === 0 && nu.uvSize[1] === 0)) nu.uvSize = [t.width, t.height];
    }
    writeTargetUV(target, nu);
    refreshUVPanel();
    renderTexCanvas();
  };
  texRow.appendChild(texSel);
  box.appendChild(texRow);

  // UV 模式
  const modeRow = document.createElement('label'); modeRow.className = 'row';
  modeRow.appendChild(document.createTextNode('UV 模式 '));
  const modeSel = document.createElement('select');
  for (const [m, label] of Object.entries(UV_MODES)) modeSel.appendChild(new Option(label, m));
  modeSel.value = uv.mode;
  modeSel.onchange = () => {
    const nu = normalizeUV({ ...readTargetUV(target), mode: modeSel.value });
    writeTargetUV(target, nu);
    refreshUVPanel();
    renderTexCanvas();
  };
  modeRow.appendChild(modeSel);
  box.appendChild(modeRow);

  if (uv.mode !== 'fill') {
    box.appendChild(uvVecField('贴图大小', nu => nu.texSize, (nu, v) => nu.texSize = v, uv, false, 'x'));
    box.appendChild(uvVecField('UV 起点', nu => nu.uvStart, (nu, v) => nu.uvStart = v, uv, true, '|'));
    box.appendChild(uvVecField('UV 大小', nu => nu.uvSize, (nu, v) => nu.uvSize = v, uv, false, 'x'));
  }
  if (uv.mode === 'animated') {
    box.appendChild(uvVecField('UV 步长', nu => nu.uvStep, (nu, v) => nu.uvStep = v, uv, true, '|'));
    box.appendChild(uvNumField('帧率', nu => nu.fps, (nu, v) => nu.fps = v, uv));
    box.appendChild(uvFrameField(uv));
    box.appendChild(uvChkField('循环', nu => nu.loop, (nu, v) => nu.loop = v, uv));
  }
}

// 轻量更新「自动 N」提示文本（不重建面板、不失焦）：重算当前 target 的自动帧数并写进所有 .uv-auto-count。
// 用于 UV 步长/起点等会改变自动帧数的字段 change 后即时刷新。
function refreshAutoFrameHint() {
  const t = currentUVTarget();
  if (!t) return;
  const uv = readTargetUV(t);
  if (!uv) return;
  const tex = getTexture(uv.texture);
  const auto = autoFramesFor(uv, tex ? tex.width : 16, tex ? tex.height : 16);
  document.querySelectorAll('.uv-auto-count').forEach(el => { el.textContent = String(auto); });
}

// 是否需要在主渲染循环中逐帧刷新贴图编辑器 overlay（UV 动画预览跟随帧移动）。
// 仅当贴图 tab 激活且当前编辑目标的 UV 为动画模式时才逐帧刷新，避免无谓开销。
function texAnimOverlayActive() {
  const pane = document.getElementById('pane-texture');
  if (!pane || !pane.classList.contains('active')) return false;
  const t = currentUVTarget();
  if (!t) return false;
  const uv = readTargetUV(t);
  return !!(uv && uv.texture && uv.mode === 'animated');
}

// 二维像素字段（[x, y] 或 [w, h]），时间轴分组风格；affectsAuto=true 表示该字段变化会改变自动帧数（即时刷新提示）
// sep：两框间分隔符——'x' 显示乘号（贴图大小/UV 大小），'|' 显示细竖线（UV 起点/UV 步长），null 不显示
function uvVecField(labelText, get, set, uv, affectsAuto, sep) {
  const row = document.createElement('div'); row.className = 'row';
  const lab = document.createElement('span'); lab.textContent = labelText;
  row.appendChild(lab);
  const group = document.createElement('span'); group.className = 'uv-field';
  const mk = (i, axis) => {
    const inp = document.createElement('input');
    inp.type = 'number'; inp.step = '1';
    inp.value = get(uv)[i];
    inp.title = axis;
    inp.addEventListener('change', () => {
      const nu = normalizeUV({ ...readTargetUV(currentUVTarget()) });
      const arr = get(nu).slice();
      arr[i] = Math.max(0, parseInt(inp.value) || 0);
      set(nu, arr);
      writeTargetUV(currentUVTarget(), nu);
      renderTexCanvas();
      if (affectsAuto) refreshAutoFrameHint();
    });
    return inp;
  };
  group.appendChild(mk(0, 'X'));
  if (sep) {
    const s = document.createElement('span');
    s.className = 'uv-sep' + (sep === 'x' ? ' uv-sep-mul' : '');
    s.textContent = sep === 'x' ? '×' : '';
    if (sep === 'x') s.title = '乘以'; 
    group.appendChild(s);
  }
  group.appendChild(mk(1, 'Y'));
  const suffix = document.createElement('span'); suffix.className = 'uv-suffix';
  suffix.textContent = labelText === 'UV 步长' ? '像素' : (labelText === '贴图大小' ? 'px' : '');
  if (suffix.textContent) group.appendChild(suffix);
  row.appendChild(group);
  return row;
}
function uvNumField(labelText, get, set, uv) {
  const row = document.createElement('div'); row.className = 'row';
  const lab = document.createElement('span'); lab.textContent = labelText;
  row.appendChild(lab);
  const group = document.createElement('span'); group.className = 'uv-field';
  const inp = document.createElement('input');
  inp.type = 'number'; inp.min = '1'; inp.value = get(uv);
  inp.addEventListener('change', () => {
    const nu = normalizeUV({ ...readTargetUV(currentUVTarget()) });
    set(nu, Math.max(1, parseInt(inp.value) || 1));
    writeTargetUV(currentUVTarget(), nu);
    renderTexCanvas();
  });
  group.appendChild(inp);
  const suffix = document.createElement('span'); suffix.className = 'uv-suffix';
  suffix.textContent = '/ 秒'; group.appendChild(suffix);
  row.appendChild(group);
  return row;
}
// 「最大帧数」专用字段：0 / 1 / 未设置为「自动」（不限制，按 UV 步长自动算满）；
// >1 的输入作为「小于实际帧数的上限」。时间轴风格「输入 / 自动 N」。
function uvFrameField(uv) {
  const row = document.createElement('div'); row.className = 'row';
  const lab = document.createElement('span'); lab.textContent = '最大帧数';
  row.appendChild(lab);
  const group = document.createElement('span'); group.className = 'uv-field';
  const inp = document.createElement('input');
  inp.type = 'number'; inp.min = '0'; inp.title = '0 或 1 = 自动（按 UV 步长算满）；>1 = 最大上限';
  const shown = (uv.maxFrame != null && uv.maxFrame > 1) ? uv.maxFrame : 1; // 1 表示自动
  inp.value = shown;
  const t = getTexture(uv.texture);
  const autoFrames = autoFramesFor(uv, t ? t.width : 16, t ? t.height : 16);
  const suffix = document.createElement('span'); suffix.className = 'uv-suffix';
  suffix.innerHTML = '/ <em class="uv-auto-count">' + autoFrames + '</em>';
  inp.addEventListener('change', () => {
    const nu = normalizeUV({ ...readTargetUV(currentUVTarget()) });
    const v = parseInt(inp.value);
    // 0 / 1 → 自动（1 表示为自动的上限值，实际不限制）；否则为上限
    nu.maxFrame = (isNaN(v) || v <= 1) ? 1 : v;
    writeTargetUV(currentUVTarget(), nu);
    renderTexCanvas();
    refreshAutoFrameHint();
  });
  group.appendChild(inp); group.appendChild(suffix);
  row.appendChild(group);
  return row;
}
function uvChkField(labelText, get, set, uv) {
  const row = document.createElement('label'); row.className = 'chk';
  const inp = document.createElement('input'); inp.type = 'checkbox';
  inp.checked = get(uv);
  inp.addEventListener('change', () => {
    const nu = normalizeUV({ ...readTargetUV(currentUVTarget()) });
    set(nu, inp.checked);
    writeTargetUV(currentUVTarget(), nu);
  });
  row.appendChild(inp); row.appendChild(document.createTextNode(labelText));
  return row;
}