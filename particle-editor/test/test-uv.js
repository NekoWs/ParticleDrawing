/* 贴图/UV + scale XYZ 数据模型回归测试（不依赖浏览器 DOM/THREE） */
const fs = require('fs');

const code = [
  fs.readFileSync('js/constants.js', 'utf8'),
  fs.readFileSync('js/io.js', 'utf8'),
  fs.readFileSync('js/edit.js', 'utf8'),
  `
global.__fail = false;
function __assert(name, cond) { if (cond) console.log('PASS ' + name); else { console.log('FAIL ' + name); global.__fail = true; } }

__assert('defaultUV texture=null', defaultUV(16, 16).texture === null);
__assert('defaultUV texSize [w,h]', defaultUV(32, 16).texSize[0] === 32 && defaultUV(32, 16).texSize[1] === 16);
__assert('defaultUV uvStart [0,0]', defaultUV(32, 16).uvStart[0] === 0 && defaultUV(32, 16).uvStart[1] === 0);
__assert('defaultUV uvSize [0,0]', defaultUV(32, 16).uvSize[0] === 0 && defaultUV(32, 16).uvSize[1] === 0);
__assert('defaultUV uvStep [0,0]', defaultUV(32, 16).uvStep[0] === 0 && defaultUV(32, 16).uvStep[1] === 0);
__assert('defaultUV loop true', defaultUV(16, 16).loop === true);
__assert('UV_MODES', UV_MODES.static === '静态' && UV_MODES.fill === '填充' && UV_MODES.animated === '动画');

const uv = { texture: 't1', mode: 'animated', texSize: [32, 16], uvStart: [4, 0], uvSize: [8, 8], uvStep: [8, 0], fps: 10, maxFrame: 4, loop: true };
const puv = parseUV(serializeUV(uv));
__assert('uv roundtrip texSize', puv.texSize[0] === 32 && puv.texSize[1] === 16);
__assert('uv roundtrip uvStart', puv.uvStart[0] === 4 && puv.uvStart[1] === 0);
__assert('uv roundtrip uvSize', puv.uvSize[0] === 8 && puv.uvSize[1] === 8);
__assert('uv roundtrip uvStep', puv.uvStep[0] === 8 && puv.uvStep[1] === 0);
__assert('uv roundtrip fps', puv.fps === 10 && puv.maxFrame === 4);
__assert('uv roundtrip loop', puv.loop === true);
__assert('parseUV undefined', parseUV(undefined) === undefined);

const ap = addParticle({});
__assert('addParticle scale array', Array.isArray(ap.scale) && ap.scale.length === 3);
__assert('addParticle no style', !('style' in ap));

const dp = addParticle({ pos: [0, 0, 0] });
__assert('serialize omit default scale', serializeParticle(dp).sc === undefined);
const sp = addParticle({ scale: [2, 1, 0.5] });
const j = serializeParticle(sp);
__assert('serialize scale array', Array.isArray(j.sc) && j.sc[0] === 2 && j.sc[2] === 0.5);

const uvp = addParticle({ uv: { texture: 't1', mode: 'static', texSize: [16,16], uvStart: [0,0], uvSize: [16,16], uvStep: [16,0], fps: 1, maxFrame: 1, loop: true } });
__assert('serialize uv.texture', serializeParticle(uvp).uv && serializeParticle(uvp).uv.texture === 't1');
__assert('serialize no uv when null', serializeParticle(addParticle({})).uv === undefined);

// parseParticlesTracks：scale 数组回读 + 旧版标量兼容
state.particles = [];
state.tracks = [];
state.groups = {};
state.loop = true;
parseParticlesTracks({ p: [{ id: 'p0', pos: [0,0,0], sc: [2,3,4] }, { id: 'p1', pos: [1,0,0], sc: 5 }] });
__assert('parse scale array', state.particles[0].scale[0] === 2 && state.particles[0].scale[2] === 4);
__assert('parse scale scalar->vec', state.particles[1].scale[0] === 5 && state.particles[1].scale[1] === 5);

// exportProject -> import roundtrip 含 UV 与贴图列表
state.particles = [addParticle({ id: 'p0', pos: [0,0,0], scale: [2,1,1], uv: { texture: 't1', mode: 'static', texSize: [16,16], uvStart: [0,0], uvSize: [8,8], uvStep: [8,0], fps: 1, maxFrame: 1, loop: true } })];
state.groups = {};
state.groupUV = {};
state.textures = { t1: { name: 't1', width: 16, height: 16, data: new Uint8ClampedArray(16*16*4) } };
state.functions = [];
const proj = exportProject();
__assert('export tex list', proj.tex && proj.tex[0] === 't1');
__assert('export particle uv', proj.p[0].uv && proj.p[0].uv.texture === 't1');
__assert('export v=4', proj.v === 4);

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