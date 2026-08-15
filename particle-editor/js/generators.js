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

/* =========================================================================
 * 函数对象：活源重算
 * ======================================================================= */

// 链式求值变量：vars 为 { name: {expr, kf} }，关键帧优先按 t 插值，无帧用表达式
function buildEnv(vars, ctx) {
  const env = { i: ctx.i, n: ctx.n, t: ctx.t || 0 };
  const memo = {};
  const inStack = new Set();
  function resolve(name) {
    if (name in memo) return memo[name];
    if (name in env) return env[name];
    const v = vars[name];
    if (!v) throw new Error('未知变量: ' + name);
    if (inStack.has(name)) throw new Error('变量循环引用: ' + name);
    inStack.add(name);
    const kf = v.kf || [];
    const val = (kf.length > 0) ? varKfValue(kf, ctx.t || 0) : evaluate(v.expr || '0', resolve);
    inStack.delete(name);
    memo[name] = val;
    return val;
  }
  for (const name in vars) {
    if (ATTR_NAMES.includes(name)) throw new Error('变量名 ' + name + ' 是属性保留字，请换名');
    resolve(name);
  }
  for (const name in memo) env[name] = memo[name];
  return env;
}

function exprUsesT(expr) {
  const e = (expr || '').trim();
  if (!e) return false;
  try { return tokenize(e).some(tk => tk.t === 'var' && tk.name === 't'); }
  catch (err) { return false; }
}

// 求值单个粒子在某时刻的完整状态（执行公式代码块）
function evaluateParticleAt(fx, i, n, t) {
  const env = buildEnv(fx.vars || {}, { i, n, t });
  const out = evalFunctionCode(fx.code || '', env);
  const center = fx.center || [0, 0, 0];
  const clamp01 = x => (Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : 0);
  return {
    pos: [out.pos[0] + center[0], out.pos[1] + center[1], out.pos[2] + center[2]],
    color: out.color.map(clamp01),
    vel: [out.vel[0], out.vel[1], out.vel[2]],
    scale: Number.isFinite(out.scale) ? out.scale : 1,
    glow: !!out.glow,
    light: Math.max(0, Math.min(15, Math.round(out.light))),
  };
}
function evaluateParticleBase(fx, i, n) { return evaluateParticleAt(fx, i, n, 0); }

const eq3 = (a, b) => a.length === b.length && a.every((x, i) => Math.abs(x - b[i]) < 1e-9);

// 生成函数派生轨道（公式/变量随时间变化时按 duration/step 采样）
function buildDerivedTracks(fx) {
  const n = Math.max(1, Math.round(fx.count) || 1);
  const duration = Math.max(0, Math.round(fx.duration) || 0);
  const step = Math.max(1, Math.round(fx.step) || 1);
  if (duration <= 0) return;
  const hasVarAnim = Object.values(fx.vars || {}).some(v => (v.kf || []).length > 0 || exprUsesT(v.expr));
  if (!exprUsesT(fx.code) && !hasVarAnim) return;
  const times = [0];
  for (let t = step; t <= duration; t += step) times.push(t);
  for (let i = 0; i < n; i++) {
    const pid = fx.id + ':p' + i;
    const samples = times.map(t => evaluateParticleAt(fx, i, n, t));
    const base = samples[0];
    const changed = (key) => samples.some(s => s !== base && !eq3(s[key], base[key]));
    // 采样点已是缓动后的精确值，点间应线性插值（LINEAR=0），避免默认缓动二次插值产生过冲/回弹
    if (changed('pos')) state.tracks.push({ pr: 'pos', m: 'set', ids: [pid], kf: times.map((t, idx) => [t, samples[idx].pos.slice(), 0]), fx: fx.id });
    if (changed('color')) state.tracks.push({ pr: 'col', m: 'set', ids: [pid], kf: times.map((t, idx) => [t, samples[idx].color.slice(), 0]), fx: fx.id });
    if (changed('vel')) state.tracks.push({ pr: 'vel', m: 'set', ids: [pid], kf: times.map((t, idx) => [t, samples[idx].vel.slice(), 0]), fx: fx.id });
    if (samples.some(s => Math.abs(s.scale - base.scale) > 1e-9)) state.tracks.push({ pr: 'scl', m: 'set', ids: [pid], kf: times.map((t, idx) => [t, [samples[idx].scale], 0]), fx: fx.id });
  }
}

// 活源重算：按当前函数定义重建派生粒子与派生轨道（保留手动轨道）
function rebuildFunctionObject(fx) {
  const n = Math.max(1, Math.round(fx.count) || 1);
  state.tracks = state.tracks.filter(tr => tr.fx !== fx.id); // 移除函数派生轨道
  const existing = new Map();
  for (const p of state.particles) if (p.fx === fx.id) existing.set(p.id, p);
  const kept = new Set();
  for (let i = 0; i < n; i++) {
    const id = fx.id + ':p' + i;
    kept.add(id);
    const base = evaluateParticleBase(fx, i, n);
    let p = existing.get(id);
    if (!p) {
      p = { id, fx: fx.id, style: fx.style, color: [1, 1, 1, 1], scale: 1, glow: false, lightLevel: 0, pos: [0, 0, 0], vel: [0, 0, 0] };
      state.particles.push(p);
    }
    p.style = fx.style;
    p.color = base.color.slice();
    p.scale = base.scale;
    p.glow = base.glow;
    p.lightLevel = base.light;
    p.pos = base.pos.slice();
    p.vel = base.vel.slice();
  }
  for (const p of [...state.particles]) {
    if (p.fx === fx.id && !kept.has(p.id)) {
      state.particles = state.particles.filter(x => x !== p);
      state.tracks = state.tracks.filter(tr => !tr.ids.includes(p.id));
      state.selected.delete(p.id);
    }
  }
  buildDerivedTracks(fx);
  rebuildPoints();
  refreshParticleTree();
  state.dirty = true;
}

// 预设：按参数生成代码块 + 变量（改参数时不重置 count）
function applyPresetBuild(fx) {
  const preset = FUNCTION_PRESETS[fx.preset];
  if (!preset) return;
  const built = preset.build(fx.params || {});
  fx.vars = { ...built.vars };
  fx.code = built.code;
}

// 分辨率变量联动 count：改 m/k/cols/rows/turns/ppr 时重算 count=乘积
function syncPresetCount(fx) {
  const preset = FUNCTION_PRESETS[fx.preset];
  if (!preset || !preset.countVars || preset.countVars.length === 0) return;
  let count = 1;
  for (const name of preset.countVars) {
    const v = fx.vars[name];
    if (!v) return;
    let val;
    try { val = evaluate(v.expr || '0', { i: 0, n: 1, t: 0 }); } catch (e) { return; }
    if (!Number.isFinite(val) || val <= 0) return;
    count *= Math.round(val);
  }
  fx.count = Math.max(1, Math.round(count));
}

function applyPreset(fx, presetId) {
  const preset = FUNCTION_PRESETS[presetId];
  if (!preset) return;
  fx.preset = presetId;
  fx.params = {};
  for (const p of preset.params) fx.params[p.key] = p.def;
  const built = preset.build(fx.params);
  fx.count = built.count; // 仅在切换预设时设置默认采样数
  fx.vars = { ...built.vars };
  fx.code = built.code;
}

function createFunctionObject(presetId) {
  pushUndo();
  const fx = {
    id: nextFunctionId(), name: presetId ? FUNCTION_PRESETS[presetId].label : '函数对象',
    center: [0, 4, 0], count: 30, style: 'DOT',
    code: '[x,y,z] = [0, 0, 0];\n[r,g,b,a] = [1,1,1,1];\nsc = 0.3;\nglow = 0;\nlight = 0',
    vars: {}, duration: 100, step: 5, preset: null, params: null,
  };
  state.functions.push(fx);
  if (presetId) applyPreset(fx, presetId);
  state.selectedFunction = fx.id;
  state.selected = new Set();
  state.selectedGroup = null;
  try {
    rebuildFunctionObject(fx);
  } catch (e) {
    alert('表达式错误：' + e.message);
  }
  refreshParticleTree();
  refreshFunctionPanel();
  return fx;
}

function deleteFunctionObject(fxId) {
  pushUndo();
  state.functions = state.functions.filter(f => f.id !== fxId);
  for (const p of [...state.particles]) {
    if (p.fx === fxId) {
      state.particles = state.particles.filter(x => x !== p);
      state.tracks = state.tracks.filter(tr => !tr.ids.includes(p.id));
      state.selected.delete(p.id);
    }
  }
  state.tracks = state.tracks.filter(tr => !tr.ids.includes('f:' + fxId)); // 移除整体变换轨道
  if (state.selectedFunction === fxId) state.selectedFunction = null;
  state.expandedParticles.delete('f:' + fxId);
  rebuildPoints();
  refreshParticleTree();
  refreshFunctionPanel();
}
