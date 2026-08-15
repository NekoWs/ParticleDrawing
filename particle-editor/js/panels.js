/* =========================================================================
 * 属性面板
 * ======================================================================= */

function updatePropPanel() {
  const sel = currentSelected();
  if (sel.length === 0) return;
  const first = sel[0];
  const same = (fn) => sel.every(q => fn(q) === fn(first));
  const styleSel = document.getElementById('prop-style');
  const styleSame = same(q => q.style);
  let mixedOpt = styleSel.querySelector('option[value="__mixed__"]');
  if (!styleSame) {
    if (!mixedOpt) { mixedOpt = document.createElement('option'); mixedOpt.value = '__mixed__'; mixedOpt.textContent = '-'; styleSel.appendChild(mixedOpt); }
    styleSel.value = '__mixed__';
  } else {
    styleSel.value = first.style;
  }

  const colorSame = same(q => q.color[0] + ',' + q.color[1] + ',' + q.color[2]);
  document.getElementById('prop-color').value = colorSame ? rgbToHex(first.color[0], first.color[1], first.color[2]) : '#808080';

  const aSame = same(q => q.color[3]);
  const aInput = document.getElementById('prop-alpha');
  if (aSame) { aInput.value = first.color[3]; document.getElementById('alpha-val').textContent = first.color[3].toFixed(2); }
  else { aInput.value = 0.5; document.getElementById('alpha-val').textContent = '-'; }

  const sSame = same(q => q.scale);
  const sInput = document.getElementById('prop-scale');
  sInput.value = sSame ? first.scale : '';
  sInput.placeholder = sSame ? '' : '-';

  const gSame = same(q => q.glow);
  const gInput = document.getElementById('prop-glow');
  gInput.checked = gSame ? first.glow : false;
  gInput.indeterminate = !gSame;

  const lSame = same(q => q.lightLevel);
  const lInput = document.getElementById('prop-light');
  if (lSame) { lInput.value = first.lightLevel; document.getElementById('light-val').textContent = first.lightLevel; }
  else { lInput.value = 0; document.getElementById('light-val').textContent = '-'; }

  const pos = currentVisual(first).pos;
  const setPos = (id, val, sameVal) => { const el = document.getElementById(id); el.value = sameVal ? val : ''; el.placeholder = sameVal ? '' : '-'; };
  const xSame = same(q => currentVisual(q).pos[0].toFixed(2) === pos[0].toFixed(2));
  const ySame = same(q => currentVisual(q).pos[1].toFixed(2) === pos[1].toFixed(2));
  const zSame = same(q => currentVisual(q).pos[2].toFixed(2) === pos[2].toFixed(2));
  setPos('prop-posx', pos[0].toFixed(2), xSame);
  setPos('prop-posy', pos[1].toFixed(2), ySame);
  setPos('prop-posz', pos[2].toFixed(2), zSame);
}

function rgbToHex(r, g, b) {
  const c = v => Math.round(Math.min(1, Math.max(0, v)) * 255).toString(16).padStart(2, '0');
  return '#' + c(r) + c(g) + c(b);
}

function hexToRgb(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
}

/* =========================================================================
 * 时间轴（底部：仅播放进度）
 * ======================================================================= */

const TL_PX_PER_TICK = 4;
let timelineViewStart = -25;

function drawTimeline() {
  const canvas = document.getElementById('timeline');
  if (!canvas) return;
  const dpr = window.devicePixelRatio || 1;
  const w = canvas.clientWidth || 1, h = canvas.clientHeight || 1;
  canvas.width = w * dpr; canvas.height = h * dpr;
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, h);
  const pxPerTick = TL_PX_PER_TICK;
  const viewEnd = timelineViewStart + w / pxPerTick;
  ctx.fillStyle = '#1f222a'; ctx.fillRect(0, 0, w, h);
  ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke();
  const step = niceStep(viewEnd - timelineViewStart);
  ctx.fillStyle = '#9aa0ad'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top';
  for (let t = Math.floor(timelineViewStart / step) * step; t <= viewEnd; t += step) {
    if (t < 0) continue;
    const x = (t - timelineViewStart) * pxPerTick;
    ctx.fillText(Math.round(t), x + 2, 2);
    ctx.strokeStyle = '#3a3f4b'; ctx.beginPath(); ctx.moveTo(x, h / 2 - 6); ctx.lineTo(x, h / 2 + 6); ctx.stroke();
  }
  const phx = (state.time - timelineViewStart) * pxPerTick;
  ctx.strokeStyle = '#ffcc55'; ctx.lineWidth = 2;
  ctx.beginPath(); ctx.moveTo(phx, 0); ctx.lineTo(phx, h); ctx.stroke();
  ctx.fillStyle = '#ffcc55'; ctx.beginPath(); ctx.moveTo(phx - 5, 0); ctx.lineTo(phx + 5, 0); ctx.lineTo(phx, 8); ctx.closePath(); ctx.fill();
}

function niceStep(range) {
  const rough = Math.max(1, range / 10);
  const pow = Math.pow(10, Math.floor(Math.log10(rough)));
  const norm = rough / pow;
  return (norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10) * pow;
}

function timelineXToTick(clientX) {
  const canvas = document.getElementById('timeline');
  const rect = canvas.getBoundingClientRect();
  return timelineViewStart + (clientX - rect.left) / TL_PX_PER_TICK;
}
