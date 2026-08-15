/* =========================================================================
 * 缓动函数编辑器
 * ======================================================================= */

function easingToBezier(easing) {
  if (Array.isArray(easing)) return easing.slice(0, 4);
  const p = EASINGS[easing] || EASINGS[0];
  return [p[1], p[2], p[3], p[4]];
}

function easingCurveSVG(easing) {
  const w = 28, h = 16;
  let d = '';
  for (let i = 0; i <= 24; i++) {
    const t = i / 24;
    const y = easeVal(t, easing);
    const x = t * w, yy = h - 1 - y * (h - 2);
    d += (i === 0 ? 'M' : 'L') + x.toFixed(1) + ',' + yy.toFixed(1);
  }
  return `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><path d="${d}" fill="none" stroke="#5b9dff" stroke-width="1.6"/></svg>`;
}

function makeEasingBtn(easing, applyFn) {
  const btn = document.createElement('button');
  btn.className = 'ease-btn';
  btn.innerHTML = easingCurveSVG(easing);
  btn.title = '编辑缓动函数';
  btn.onclick = (e) => { e.stopPropagation(); openEasingEditor(easing, applyFn, btn); };
  return btn;
}

let easingEditor = null;

function openEasingEditor(easing, applyFn, anchor) {
  closeEasingEditor();
  const bezier = easingToBezier(easing);
  easingEditor = { bezier, apply: applyFn, anchor, dragging: -1, inputs: {} };
  const pop = document.createElement('div');
  pop.id = 'easing-editor';
  pop.className = 'easing-editor';
  const title = document.createElement('div');
  title.className = 'ee-title';
  title.textContent = '缓动函数编辑器';
  pop.appendChild(title);

  const inputs = document.createElement('div');
  inputs.className = 'ee-inputs';
  const mkRow = (label, idxX, idxY) => {
    const row = document.createElement('div');
    row.className = 'ee-input-row';
    const lab = document.createElement('span');
    lab.className = 'ee-input-label';
    lab.textContent = label;
    row.appendChild(lab);
    const mkInp = (idx) => {
      const inp = document.createElement('input');
      inp.type = 'number';
      inp.step = '0.01';
      inp.min = '0'; inp.max = '1';
      inp.value = bezier[idx].toFixed(3);
      inp.addEventListener('input', () => {
        const v = Math.min(1, Math.max(0, parseFloat(inp.value) || 0));
        easingEditor.bezier[idx] = v;
        easingEditor.apply(easingEditor.bezier.slice());
        drawEasingEditor();
      });
      inp.addEventListener('change', () => { refreshParticleTree(); refreshFunctionPanel(); });
      easingEditor.inputs[idx] = inp;
      row.appendChild(inp);
    };
    mkInp(idxX); mkInp(idxY);
    return row;
  };
  inputs.appendChild(mkRow('P1', 0, 1));
  inputs.appendChild(mkRow('P2', 2, 3));
  pop.appendChild(inputs);

  const canvas = document.createElement('canvas');
  canvas.className = 'ee-canvas';
  canvas.width = 196; canvas.height = 132;
  pop.appendChild(canvas);
  const presetSel = document.createElement('select');
  presetSel.className = 'ee-presets';
  const opt0 = document.createElement('option');
  opt0.value = ''; opt0.textContent = '预设…';
  presetSel.appendChild(opt0);
  for (let i = 0; i < EASINGS.length; i++) {
    const o = document.createElement('option');
    o.value = i; o.textContent = EASINGS[i][0];
    presetSel.appendChild(o);
  }
  if (Number.isInteger(easing)) presetSel.value = String(easing); // 预设下拉回显当前缓动
  presetSel.onchange = () => {
    if (presetSel.value === '') return;
    easingEditor.bezier = easingToBezier(parseInt(presetSel.value));
    easingEditor.apply(parseInt(presetSel.value));
    syncEasingInputs();
    drawEasingEditor();
    refreshParticleTree();
    refreshFunctionPanel();
  };
  pop.appendChild(presetSel);
  document.body.appendChild(pop);
  const r = anchor.getBoundingClientRect();
  pop.style.left = Math.min(r.left, window.innerWidth - 236) + 'px';
  const popH = pop.offsetHeight || 260;
  let top = r.bottom + 6;
  if (top + popH > window.innerHeight - 8) top = Math.max(8, r.top - popH - 6);
  pop.style.top = top + 'px';
  drawEasingEditor();
  canvas.addEventListener('pointerdown', onEasingPointerDown);
  canvas.addEventListener('pointermove', onEasingPointerMove);
  canvas.addEventListener('pointerup', () => { if (easingEditor) { easingEditor.dragging = -1; refreshParticleTree(); refreshFunctionPanel(); } });
  setTimeout(() => document.addEventListener('pointerdown', onEasingDocPointerDown), 0);
}

function syncEasingInputs() {
  if (!easingEditor) return;
  for (let i = 0; i < 4; i++) {
    const inp = easingEditor.inputs[i];
    if (inp && document.activeElement !== inp) inp.value = easingEditor.bezier[i].toFixed(3);
  }
}

function onEasingDocPointerDown(e) {
  if (easingEditor && !e.target.closest('#easing-editor')) { closeEasingEditor(); refreshParticleTree(); refreshFunctionPanel(); }
}

function closeEasingEditor() {
  easingEditor = null;
  const pop = document.getElementById('easing-editor');
  if (pop) pop.remove();
  document.removeEventListener('pointerdown', onEasingDocPointerDown);
}

function cubicBezierX(t, x1, x2) {
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  return ((ax * t + bx) * t + cx) * t;
}

function cubicBezierY(t, y1, y2) {
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  return ((ay * t + by) * t + cy) * t;
}

function drawEasingEditor() {
  const pop = document.getElementById('easing-editor');
  if (!pop || !easingEditor) return;
  const [x1, y1, x2, y2] = easingEditor.bezier;
  syncEasingInputs();
  const canvas = pop.querySelector('.ee-canvas');
  const ctx = canvas.getContext('2d');
  const w = canvas.width, h = canvas.height, pad = 12;
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = '#171a20';
  ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = '#323848';
  ctx.strokeRect(pad, pad, w - 2 * pad, h - 2 * pad);
  const px = (x) => pad + Math.min(1, Math.max(0, x)) * (w - 2 * pad);
  const py = (y) => pad + (1 - Math.min(1, Math.max(0, y))) * (h - 2 * pad);
  ctx.strokeStyle = '#5b9dff';
  ctx.lineWidth = 2;
  ctx.beginPath();
  for (let i = 0; i <= 48; i++) {
    const t = i / 48;
    const bx = pad + cubicBezierX(t, x1, x2) * (w - 2 * pad);
    const by = pad + (1 - cubicBezierY(t, y1, y2)) * (h - 2 * pad);
    if (i === 0) ctx.moveTo(bx, by); else ctx.lineTo(bx, by);
  }
  ctx.stroke();
  ctx.strokeStyle = '#4a5568';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(pad, h - pad); ctx.lineTo(px(x1), py(y1));
  ctx.moveTo(w - pad, pad); ctx.lineTo(px(x2), py(y2));
  ctx.stroke();
  drawPoint(ctx, px(x1), py(y1), '#ff6b6b');
  drawPoint(ctx, px(x2), py(y2), '#6ba7ff');
}

function drawPoint(ctx, x, y, color) {
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.arc(x, y, 6, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = '#fff';
  ctx.lineWidth = 1.5;
  ctx.stroke();
}

function onEasingPointerDown(e) {
  if (!easingEditor) return;
  const canvas = document.getElementById('easing-editor').querySelector('.ee-canvas');
  const rect = canvas.getBoundingClientRect();
  const w = canvas.width, h = canvas.height, pad = 12;
  const mx = (e.clientX - rect.left - pad) / (w - 2 * pad);
  const my = 1 - (e.clientY - rect.top - pad) / (h - 2 * pad);
  const [x1, y1, x2, y2] = easingEditor.bezier;
  const d1 = Math.hypot(mx - x1, my - y1);
  const d2 = Math.hypot(mx - x2, my - y2);
  easingEditor.dragging = d1 < d2 ? 0 : 1;
  canvas.setPointerCapture(e.pointerId);
}

function onEasingPointerMove(e) {
  if (!easingEditor || easingEditor.dragging < 0) return;
  const canvas = document.getElementById('easing-editor').querySelector('.ee-canvas');
  const rect = canvas.getBoundingClientRect();
  const w = canvas.width, h = canvas.height, pad = 12;
  let mx = (e.clientX - rect.left - pad) / (w - 2 * pad);
  let my = 1 - (e.clientY - rect.top - pad) / (h - 2 * pad);
  mx = Math.min(1, Math.max(0, mx));
  my = Math.min(1, Math.max(0, my));
  const b = easingEditor.bezier;
  if (easingEditor.dragging === 0) { b[0] = mx; b[1] = my; }
  else { b[2] = mx; b[3] = my; }
  drawEasingEditor();
  easingEditor.apply(b.slice());
}
