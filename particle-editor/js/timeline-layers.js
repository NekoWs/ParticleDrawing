/* =========================================================================
 * 底部时间轴 · 图层区（AE 式）
 *
 * - 行模型：组聚合行（成员 st 跨度，可展开为成员行）+ 未分组粒子行 + 函数对象行；
 * - 拖拽条形起点改 st（整数 tick 吸附）；组条拖拽整体平移全部成员；
 * - 视口横向与标尺共享 timelineViewStart / TL_PX_PER_TICK（panels.js）；
 * - undo：拖拽开始前 pushUndo 一次（undo.js 的 snapshot 经字段展开天然覆盖 st/ent）。
 * ======================================================================= */

const TL_LAYER_ROW_H = 18;
const tlLayerState = { expanded: new Set(), scroll: 0, drag: null, hit: [] };

function tlLayerRows() {
  const rows = [];
  const grouped = new Set();
  for (const [gname, members] of Object.entries(state.groups)) {
    for (const id of members) grouped.add(id);
    rows.push({ kind: 'group', name: gname, members: members.slice() });
  }
  for (const p of state.particles) {
    if (p.fx || grouped.has(p.id)) continue;
    rows.push({ kind: 'particle', p });
  }
  for (const fx of state.functions) rows.push({ kind: 'fx', fx });
  return rows;
}

function rowLabel(r) {
  if (r.kind === 'group') return '组 ' + r.name + ' (' + r.members.length + ')';
  if (r.kind === 'fx') return 'f ' + r.fx.name;
  return 'p ' + r.p.id;
}

/** 行的可见性跨度 [start, end]：组=成员 st 的 min~max；函数对象=st~st+extent；粒子=st 点。 */
function rowSpan(r) {
  if (r.kind === 'group') {
    let lo = Infinity, hi = -Infinity;
    for (const id of r.members) {
      const p = state.particles.find(q => q.id === id);
      if (!p) continue;
      const s = p.st || 0; lo = Math.min(lo, s); hi = Math.max(hi, s);
    }
    if (lo === Infinity) { lo = 0; hi = 0; }
    return [lo, Math.max(hi, lo)];
  }
  if (r.kind === 'fx') {
    const fx = r.fx;
    let extent = 0, hasVarAnim = false;
    for (const v of Object.values(fx.vars)) for (const k of (v.kf || [])) { hasVarAnim = true; if (k[0] > extent) extent = k[0]; }
    if (!hasVarAnim && /\bt\b/.test(fx.code || '')) extent = Math.max(extent, fx.duration || 0);
    return [fx.st || 0, (fx.st || 0) + extent];
  }
  const s = r.p.st || 0;
  return [s, s];
}

function drawTimelineLayers() {
  const canvas = document.getElementById('tl-layers-canvas');
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.fillStyle = '#181b22';
  ctx.fillRect(0, 0, w, h);

  const pxPerTick = TL_PX_PER_TICK;
  const X = t => (t - timelineViewStart) * pxPerTick;
  const rowH = TL_LAYER_ROW_H;

  // 展开组的成员行插入到组行之后
  const flat = [];
  for (const r of tlLayerRows()) {
    flat.push(r);
    if (r.kind === 'group' && tlLayerState.expanded.has(r.name)) {
      for (const id of r.members) {
        const p = state.particles.find(q => q.id === id);
        if (p) flat.push({ kind: 'member', p, group: r.name });
      }
    }
  }

  tlLayerState.hit = [];
  let y = -tlLayerState.scroll;
  for (const r of flat) {
    if (y + rowH >= 0 && y <= h) {
      const [s, e] = rowSpan(r);
      ctx.fillStyle = '#8a92a3'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'middle';
      const arrow = r.kind === 'group' ? (tlLayerState.expanded.has(r.name) ? '▾ ' : '▸ ') : '';
      ctx.fillText(arrow + rowLabel(r), 4, y + rowH / 2);
      const bx = X(s), bw = Math.max(4, X(e) - X(s));
      ctx.fillStyle = r.kind === 'group' ? '#c9a24b' : r.kind === 'fx' ? '#7e6bc9' : '#4b7ec9';
      ctx.fillRect(bx, y + 3, bw, rowH - 6);
      ctx.fillStyle = '#e8ecf5';
      ctx.fillRect(bx - 1.5, y + 2, 3, rowH - 4);   // 起点手柄
      // 播放头之前（未出场）画遮罩
      if (state.time < s) { ctx.fillStyle = 'rgba(24,27,34,0.55)'; ctx.fillRect(bx, y + 3, Math.max(0, w - bx), rowH - 6); }
      tlLayerState.hit.push({ y, rowH, r });
    }
    y += rowH;
  }

  const phx = (state.time - timelineViewStart) * pxPerTick;
  ctx.strokeStyle = 'rgba(255,204,85,0.5)';
  ctx.beginPath(); ctx.moveTo(phx, 0); ctx.lineTo(phx, h); ctx.stroke();

  if (flat.length * rowH > h) {
    ctx.fillStyle = '#666e7d'; ctx.font = '9px sans-serif'; ctx.textAlign = 'right';
    ctx.fillText('滚轮翻行', w - 4, 8);
    ctx.textAlign = 'left';
  }
}

function tlLayerHit(clientY) {
  const canvas = document.getElementById('tl-layers-canvas');
  const rect = canvas.getBoundingClientRect();
  const y = clientY - rect.top + tlLayerState.scroll;
  for (const hit of tlLayerState.hit) {
    if (y >= hit.y && y < hit.y + hit.rowH) return hit;
  }
  return null;
}

function setRowStart(r, v) {
  if (r.kind === 'particle' || r.kind === 'member') r.p.st = v;
  else if (r.kind === 'fx') r.fx.st = v;
}

function shiftGroup(r, delta) {
  if (!delta) return;
  for (const id of r.members) {
    const p = state.particles.find(q => q.id === id);
    if (p) p.st = Math.max(0, (p.st || 0) + delta);
  }
}

function timelineXToTickL(clientX) {
  const canvas = document.getElementById('tl-layers-canvas');
  const rect = canvas.getBoundingClientRect();
  return timelineViewStart + (clientX - rect.left) / TL_PX_PER_TICK;
}

function tlInitLayerEvents() {
  const canvas = document.getElementById('tl-layers-canvas');
  if (!canvas) return;

  canvas.addEventListener('pointerdown', ev => {
    const hit = tlLayerHit(ev.clientY);
    if (!hit) return;
    const rect = canvas.getBoundingClientRect();
    const localX = ev.clientX - rect.left;
    // 组行行首 14px 是展开箭头，交给 click 处理
    if (hit.r.kind === 'group' && localX < 14) return;
    pushUndo();
    canvas.setPointerCapture(ev.pointerId);
    if (hit.r.kind === 'group') {
      tlLayerState.drag = { kind: 'shift', r: hit.r, lastTick: Math.round(timelineXToTickL(ev.clientX)) };
    } else {
      tlLayerState.drag = { kind: 'start', r: hit.r };
      setRowStart(hit.r, Math.max(0, Math.round(timelineXToTickL(ev.clientX))));
    }
    refreshAllPanelsLight();
  });

  canvas.addEventListener('pointermove', ev => {
    const d = tlLayerState.drag;
    if (!d) return;
    const t = Math.round(timelineXToTickL(ev.clientX));
    if (d.kind === 'start') {
      setRowStart(d.r, Math.max(0, t));
    } else {
      const delta = t - d.lastTick;
      if (delta) { shiftGroup(d.r, delta); d.lastTick += delta; }
    }
    refreshAllPanelsLight();
  });

  const endDrag = () => { tlLayerState.drag = null; };
  canvas.addEventListener('pointerup', endDrag);
  canvas.addEventListener('pointercancel', endDrag);

  canvas.addEventListener('wheel', ev => {
    ev.preventDefault();
    tlLayerState.scroll = Math.max(0, tlLayerState.scroll + (ev.deltaY > 0 ? TL_LAYER_ROW_H * 2 : -TL_LAYER_ROW_H * 2));
    drawTimelineLayers();
  }, { passive: false });

  canvas.addEventListener('click', ev => {
    const hit = tlLayerHit(ev.clientY);
    if (hit && hit.r.kind === 'group') {
      const rect = canvas.getBoundingClientRect();
      if (ev.clientX - rect.left < 14) {
        const name = hit.r.name;
        if (tlLayerState.expanded.has(name)) tlLayerState.expanded.delete(name);
        else tlLayerState.expanded.add(name);
        drawTimelineLayers();
      }
    }
  });
}
