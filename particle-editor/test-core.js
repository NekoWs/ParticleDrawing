/* 核心逻辑冒烟测试：求值器 + 代码块 + 变量时间轴 + 预设（不依赖浏览器 DOM/THREE） */
const fs = require('fs');

const code = [
  fs.readFileSync('js/easing.js', 'utf8'),
  fs.readFileSync('js/constants.js', 'utf8'),
  fs.readFileSync('js/animation.js', 'utf8'),
  fs.readFileSync('js/generators.js', 'utf8'),
  fs.readFileSync('js/io.js', 'utf8'),
  `
global.__fail = false;
function __assert(name, cond) { if (cond) console.log('PASS ' + name); else { console.log('FAIL ' + name); global.__fail = true; } }
const LF = String.fromCharCode(10);

// —— 表达式求值器 ——
__assert('sin', Math.abs(evaluate('sin(pi/2)', {}) - 1) < 1e-9);
__assert('vec comp', evaluate('vec(1,2,3).x', {}) === 1);
__assert('polar', isVec(evaluate('polar(3, pi/2)', {})) && Math.abs(evaluate('polar(3, pi/2)', {}).z - 3) < 1e-9);
__assert('rotZ matvec', isVec(evaluate('rotZ(pi/2) * vec(1,0,0)', {})) && Math.abs(evaluate('rotZ(pi/2) * vec(1,0,0)', {}).y - 1) < 1e-9);
let ok = true;
for (let i = 0; i < 8; i++) { const a = (i / 8) * Math.PI * 2; const p = evaluate('rotZ(' + a + ') * vec(3,0,0)', {}); if (Math.abs(Math.hypot(p.x, p.y) - 3) > 1e-9) ok = false; }
__assert('matrix circle', ok);

// —— 公式代码块（分号分隔 + 换行） ——
const out1 = evalFunctionCode(['[x,y,z] = [1, 2, 3]', 'r = x*0.5', 'sc = 0.3', 'glow = 1', 'light = 12'].join(';'), {});
__assert('code pos', out1.pos[0] === 1 && out1.pos[2] === 3);
__assert('code r uses x', Math.abs(out1.color[0] - 0.5) < 1e-9);
__assert('code sc/glow/light', out1.scale === 0.3 && out1.glow === true && out1.light === 12);
const SCN = ';' + LF;
const out3 = evalFunctionCode(['a = 1', 'b = a*2', '[x,y,z] = [a, b, 0]'].join(SCN), {});
__assert('code semicolon+newline', out3.pos[0] === 1 && out3.pos[1] === 2);
const out2 = evalFunctionCode(['tmp = i*2', '[x,y,z] = [tmp, 0, tmp]', '[vx,vy,vz] = [1, 0, 0]'].join(';'), { i: 3, n: 10, t: 0 });
__assert('code temp var', out2.pos[0] === 6 && out2.vel[0] === 1);
const out4 = evalFunctionCode('[x,y,z] = rotY(pi/2) * vec(1, 0, 0)', {});
__assert('code unpack vec', Math.abs(out4.pos[2] + 1) < 1e-9 && Math.abs(out4.pos[0]) < 1e-9);

// —— LINEAR 缓动线性 ——
let lin = true;
for (let i = 0; i <= 8; i++) { const t = i / 8; if (Math.abs(easeVal(t, 0) - t) > 1e-9) lin = false; }
__assert('easeVal LINEAR linear', lin);

// —— 变量关键帧（缓动语义：后一关键帧 easing b[2]） ——
__assert('var kf interp', Math.abs(varKfValue([[0,0,3],[10,10,3]], 5) - 5) < 1e-9);
__assert('var kf clamp', varKfValue([[0,0,3],[10,10,3]], 20) === 10);
// 语义反转：a[2]=EASE_IN_OUT(3)，b[2]=LINEAR(0)，t=2.5 → 用 b[2] 线性插值=2.5（旧语义会得 1.25）
__assert('var kf uses b easing', Math.abs(varKfValue([[0,0,3],[10,10,0]], 2.5) - 2.5) < 1e-9);
const env2 = buildEnv({ rr: { expr: '5', kf: [[0, 1, 3], [10, 2, 3]] }, ss: { expr: 'rr*2', kf: [] } }, { i: 0, n: 10, t: 5 });
__assert('buildEnv kf+chain', Math.abs(env2.rr - 1.5) < 1e-9 && Math.abs(env2.ss - 3) < 1e-9);
let reserved = false; try { buildEnv({ x: { expr: '1', kf: [] } }, { i: 0, n: 1, t: 0 }); } catch (e) { reserved = true; }
__assert('reserved var name', reserved);

// —— 预设 ——
const fx = { id: 'fx0', name: 't', center: [0,4,0], count: 100, style: 'DOT', code: '', vars: {}, duration: 0, step: 5, preset: null, params: null };
applyPreset(fx, 'sphere');
__assert('sphere preset code', fx.code.indexOf('[x,y,z]') >= 0 && fx.code.indexOf(';') >= 0);
__assert('sphere preset var', fx.vars.rad && fx.vars.rad.expr === '3' && Array.isArray(fx.vars.rad.kf));
__assert('sphere params only size', FUNCTION_PRESETS.sphere.params.length === 1);
const b = evaluateParticleBase(fx, 0, 100);
__assert('sphere base radius', Math.abs(Math.hypot(b.pos[0], b.pos[1] - 4, b.pos[2]) - 3) < 0.01);

const fx2 = { id: 'fx1', name: 'c', center: [0,0,0], count: 0, style: 'DOT', code: '', vars: {}, duration: 0, step: 5, preset: null, params: null };
applyPreset(fx2, 'cube');
__assert('cube count', fx2.count === 512);
const b2 = evaluateParticleBase(fx2, 0, 512);
__assert('cube corner', Math.abs(b2.pos[0] + 2) < 0.01 && Math.abs(b2.pos[1] + 2) < 0.01);

// —— 变量时间轴驱动形状 ——
const fx3 = { id: 'fx2', name: 'anim', center: [0,0,0], count: 1, style: 'DOT', code: '[x,y,z] = [rad, 0, 0]', vars: { rad: { expr: '1', kf: [[0, 1, 3], [10, 2, 3]] } }, duration: 10, step: 5, preset: null, params: null };
const p0 = evaluateParticleAt(fx3, 0, 1, 0);
const p10 = evaluateParticleAt(fx3, 0, 1, 10);
__assert('var anim drives pos', Math.abs(p0.pos[0] - 1) < 1e-9 && Math.abs(p10.pos[0] - 2) < 1e-9);

// —— 变量表达式引用 t 也生成动画轨道 ——
const fx4 = { id: 'fx3', name: 'te', center: [0,0,0], count: 2, style: 'DOT', code: '[x,y,z] = [rad, 0, 0]', vars: { rad: { expr: 'min(5, t)', kf: [] } }, duration: 10, step: 5, preset: null, params: null };
const before = state.tracks.length;
buildDerivedTracks(fx4);
__assert('var expr t builds tracks', state.tracks.length > before);
const dtrack = state.tracks.find(tr => tr.fx === 'fx3');
__assert('derived track easing linear', dtrack && dtrack.kf.length > 0 && dtrack.kf.every(k => k[2] === 0));

// —— 分辨率变量联动 count ——
const fx5 = { id: 'fx4', name: 'torus', center: [0,0,0], count: 288, style: 'DOT', code: '', vars: {}, duration: 0, step: 5, preset: null, params: null };
applyPreset(fx5, 'torus');
__assert('torus count initial', fx5.count === 288);
fx5.vars.m.expr = '48';
syncPresetCount(fx5);
__assert('torus count sync', fx5.count === 576);

// —— 关键帧对齐 + 缓动一致 → 用关键帧 + 缓动（少帧、连续） ——
state.tracks = [];
state.particles = [];
const fxk = { id: 'fxk', name: 'k', center: [0, 0, 0], count: 2, style: 'DOT', code: '[x,y,z] = [rad, 0, 0]', vars: { rad: { expr: '2', kf: [[0, 2, 3], [20, 5, 3], [40, 2, 3]] } }, duration: 40, step: 1, preset: null, params: null };
state.functions = [fxk];
for (let i = 0; i < 2; i++) {
  const base = evaluateParticleBase(fxk, i, 2);
  state.particles.push({ id: 'fxk:p' + i, fx: 'fxk', style: 'DOT', color: base.color.slice(), scale: base.scale, glow: base.glow, lightLevel: base.light, pos: base.pos.slice(), vel: [0, 0, 0] });
}
buildDerivedTracks(fxk);
const kfTrack = state.tracks.find(tr => tr.fx === 'fxk' && tr.pr === 'pos.x');
__assert('keyframe track few kf', kfTrack && kfTrack.kf.length === 3);
__assert('keyframe track has easing', kfTrack && kfTrack.kf[1][2] === 3 && kfTrack.kf[2][2] === 3);

console.log(global.__fail ? 'RESULT: FAIL' : 'RESULT: PASS');
`,
].join('\n');

global.THREE = {
  OrbitControls: function () {},
  Vector3: class { constructor(x, y, z) { this.x = x || 0; this.y = y || 0; this.z = z || 0; } },
};
global.document = {
  getElementById: () => null,
  querySelectorAll: () => [],
  createElement: () => ({ appendChild() {}, style: {}, classList: { add() {}, remove() {}, toggle() {} } }),
};

eval(code);
if (global.__fail) process.exit(1);
