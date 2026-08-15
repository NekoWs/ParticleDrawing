/* =========================================================================
 * 函数 / 傅里叶 生成
 * ======================================================================= */

function collectVariables() {
  const vars = {};
  document.querySelectorAll('#var-list .var-row').forEach(row => {
    const name = row.querySelector('.var-name').value.trim();
    const val = parseFloat(row.querySelector('.var-value').value);
    if (name && !isNaN(val)) vars[name] = val;
  });
  return vars;
}

function addVarRow(name, value) {
  const box = document.getElementById('var-list');
  const row = document.createElement('div');
  row.className = 'var-row';
  row.innerHTML = '<input class="var-name" type="text" placeholder="名称" /><input class="var-value" type="text" placeholder="值" />';
  if (name) row.querySelector('.var-name').value = name;
  if (value != null) row.querySelector('.var-value').value = value;
  const del = document.createElement('button');
  del.className = 'del-x'; del.textContent = '×';
  del.onclick = () => row.remove();
  row.appendChild(del);
  box.appendChild(row);
}

function evalOr(expr, env, fallback) { const e = (expr || '').trim(); return e === '' ? fallback : evaluate(e, env); }

function generateFunction() {
  const vars = collectVariables();
  const count = Math.max(1, parseInt(document.getElementById('fn-count').value) || 30);
  const duration = Math.max(0, parseInt(document.getElementById('fn-duration').value) || 100);
  const step = Math.max(1, parseInt(document.getElementById('fn-step').value) || 5);
  const fx = document.getElementById('fn-x').value.trim(), fy = document.getElementById('fn-y').value.trim(), fz = document.getElementById('fn-z').value.trim();
  const fr = document.getElementById('fn-r').value.trim(), fg = document.getElementById('fn-g').value.trim(), fb = document.getElementById('fn-b').value.trim(), fa = document.getElementById('fn-a').value.trim();
  const fs = document.getElementById('fn-s').value.trim(), fglow = document.getElementById('fn-glow').value.trim(), flight = document.getElementById('fn-light').value.trim();
  const animPos = fx && fy && fz, animCol = fr && fg && fb, animScl = fs !== '';
  try {
    pushUndo();
    const startIndex = state.particles.length;
    for (let i = 0; i < count; i++) {
      const env0 = { t: 0, i, n: count, ...vars };
      const pos = [evalOr(fx, env0, 0), evalOr(fy, env0, 0), evalOr(fz, env0, 0)];
      const color = [evalOr(fr, env0, 1), evalOr(fg, env0, 1), evalOr(fb, env0, 1), evalOr(fa, env0, 1)];
      const scale = evalOr(fs, env0, 1);
      const glow = evalOr(fglow, env0, 0) > 0.5;
      const light = Math.round(Math.min(15, Math.max(0, evalOr(flight, env0, 0))));
      const p = addParticle({ pos, color, scale, glow, lightLevel: light });
      if (duration > 0) {
        const posKf = [], colKf = [], sclKf = [];
        for (let t = step; t <= duration; t += step) {
          const env = { t, i, n: count, ...vars };
          if (animPos) posKf.push([t, [evaluate(fx, env), evaluate(fy, env), evaluate(fz, env)], state.defaultEasing]);
          if (animCol) colKf.push([t, [evaluate(fr, env), evaluate(fg, env), evaluate(fb, env), evalOr(fa, env, 1)], state.defaultEasing]);
          if (animScl) sclKf.push([t, [evaluate(fs, env)], state.defaultEasing]);
        }
        if (posKf.length) state.tracks.push({ pr: 'pos', m: 'set', ids: [p.id], kf: [[0, pos.slice(), state.defaultEasing], ...posKf] });
        if (colKf.length) state.tracks.push({ pr: 'col', m: 'set', ids: [p.id], kf: [[0, color.slice(), state.defaultEasing], ...colKf] });
        if (sclKf.length) state.tracks.push({ pr: 'scl', m: 'set', ids: [p.id], kf: [[0, [scale], state.defaultEasing], ...sclKf] });
      }
    }
  } catch (e) { alert('表达式错误：' + e.message); return; }
  autoGroup(state.particles.slice(startIndex).map(p => p.id));
  rebuildPoints();
  refreshParticleTree();
}

function renderFourierInputs() {
  const box = document.getElementById('four-coeffs');
  box.innerHTML = '';
  const plane = PLANES[document.getElementById('four-plane').value] || PLANES.XZ;
  for (const axis of plane.axes) {
    const blk = document.createElement('div');
    blk.className = 'axis-block';
    blk.innerHTML = `<div class="ax-title">${axis} 轴</div>`;
    const a0wrap = document.createElement('label');
    a0wrap.className = 'row'; a0wrap.innerHTML = 'a0';
    const a0in = document.createElement('input');
    a0in.type = 'number'; a0in.step = '0.1'; a0in.value = axis === plane.axes[0] ? '3' : '0';
    a0in.dataset.a0 = axis;
    a0wrap.appendChild(a0in); blk.appendChild(a0wrap);
    const list = document.createElement('div');
    list.className = 'four-terms'; list.dataset.axis = axis;
    blk.appendChild(list);
    const addBtn = document.createElement('button');
    addBtn.className = 'mini'; addBtn.textContent = '+ 添加项';
    addBtn.onclick = () => addFourierTerm(axis);
    blk.appendChild(addBtn);
    box.appendChild(blk);
  }
  const constAx = plane.constant;
  const blk = document.createElement('div');
  blk.className = 'axis-block';
  blk.innerHTML = `<div class="ax-title">${constAx} 常量</div>`;
  const cwrap = document.createElement('label');
  cwrap.className = 'row';
  const cin = document.createElement('input');
  cin.type = 'number'; cin.step = '0.1'; cin.value = '0'; cin.id = 'four-' + constAx;
  cwrap.appendChild(cin); blk.appendChild(cwrap); box.appendChild(blk);
}

function addFourierTerm(axis, n, a, b) {
  const list = document.querySelector(`.four-terms[data-axis="${axis}"]`);
  if (!list) return;
  const row = document.createElement('div');
  row.className = 'term-row';
  row.innerHTML = '<input class="term-n" type="number" min="1" placeholder="n" /><input class="term-a" type="number" step="0.1" placeholder="a" /><input class="term-b" type="number" step="0.1" placeholder="b" />';
  row.querySelector('.term-n').value = n != null ? n : (list.children.length + 1);
  row.querySelector('.term-a').value = a != null ? a : 0;
  row.querySelector('.term-b').value = b != null ? b : 0;
  const del = document.createElement('button');
  del.className = 'del-x'; del.textContent = '×';
  del.onclick = () => row.remove();
  row.appendChild(del);
  list.appendChild(row);
}

function evalFourier(axis, omega, t) {
  const a0 = parseFloat(document.querySelector(`input[data-a0="${axis}"]`)?.value) || 0;
  let v = a0;
  document.querySelectorAll(`.four-terms[data-axis="${axis}"] .term-row`).forEach(row => {
    const n = parseFloat(row.querySelector('.term-n').value) || 0;
    const a = parseFloat(row.querySelector('.term-a').value) || 0;
    const b = parseFloat(row.querySelector('.term-b').value) || 0;
    v += a * Math.cos(n * omega * t) + b * Math.sin(n * omega * t);
  });
  return v;
}

function generateFourier() {
  const plane = PLANES[document.getElementById('four-plane').value] || PLANES.XZ;
  const omega = parseFloat(document.getElementById('four-omega').value) || 1;
  const count = Math.max(2, parseInt(document.getElementById('four-count').value) || 200);
  const constant = parseFloat(document.getElementById('four-' + plane.constant)?.value) || 0;
  pushUndo();
  const startIndex = state.particles.length;
  for (let i = 0; i < count; i++) {
    const t = (i / count) * (Math.PI * 2 / omega);
    const val = { X: constant, Y: constant, Z: constant };
    for (const axis of plane.axes) val[axis] = evalFourier(axis, omega, t);
    addParticle({ pos: [val.X, val.Y, val.Z] });
  }
  autoGroup(state.particles.slice(startIndex).map(p => p.id));
  rebuildPoints();
  refreshParticleTree();
}
