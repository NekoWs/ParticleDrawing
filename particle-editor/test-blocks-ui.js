/* blocks-ui 逻辑冒烟测试：验证「解析代码 → 主链/碎片 → 生成代码 → 写回」 */
const fs = require('fs');

function makeEl(tag) {
  return {
    tag, children: [], textContent: '', value: '', className: '', dataset: {},
    id: '',
    style: {}, classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
    appendChild(c) { this.children.push(c); if (c.id) dom[c.id] = c; return c; },
    append(c) { this.children.push(c); if (c.id) dom[c.id] = c; return c; },
    insertBefore(n, r) { this.children.push(n); if (n.id) dom[n.id] = n; return n; },
    addEventListener(){}, removeEventListener(){},
    set innerHTML(v) { this._html = v; }, get innerHTML() { return this._html || ''; },
    querySelector(){ return null; }, querySelectorAll(){ return []; },
    closest(){ return null; }, matches(){ return false; },
    setPointerCapture(){}, remove(){},
    focus(){}, blur(){},
    get offsetLeft() { return 0; }, get offsetTop() { return 0; },
    get offsetWidth() { return 100; }, get offsetHeight() { return 100; },
    getBoundingClientRect() { return { left: 0, top: 0, width: 100, height: 100 }; },
    get clientWidth() { return 100; }, get clientHeight() { return 100; },
    nextSibling: null, parentElement: null,
  };
}
const dom = {};
// 预置必要的 DOM 节点
dom['viewport'] = makeEl('viewport');
dom['viewport'].nextSibling = null;
dom['viewport'].parentElement = makeEl('layout');
dom['body'] = makeEl('body');
global.document = {
  getElementById(id) { return dom[id] || null; },
  querySelectorAll(){ return []; },
  querySelector(){ return null; },
  createElement(tag) { return makeEl(tag); },
  createTextNode(t) { return { textContent: String(t), nodeType: 3 }; },
  body: dom['body'],
};
global.window = { addEventListener(){}, innerWidth: 1200, innerHeight: 800 };
global.localStorage = { getItem(){ return null; }, setItem(){}, removeItem(){} };
global.indexedDB = undefined;

const src = [
  fs.readFileSync('js/easing.js', 'utf8'),
  fs.readFileSync('js/constants.js', 'utf8'),
  fs.readFileSync('js/blocks.js', 'utf8'),
  fs.readFileSync('js/float-window.js', 'utf8'),
  fs.readFileSync('js/undo.js', 'utf8'),
  fs.readFileSync('js/blocks-ui.js', 'utf8'),
  `
  let __fail = false;
  function ok(name, cond) { if (cond) console.log('PASS ' + name); else { console.log('FAIL ' + name); __fail = true; } }

  commitFunctionRebuild = function(fx) {};
  refreshFunctionPanel = function() {};
  refreshParticleTree = function() {};
  rebuildPoints = function() {};
  updateLoopIndicator = function() {};
  pushUndo = function() {};
  resize = function() {};
  getFunction = (id) => state.functions.find(f => f.id === id);

  state.functions = [{ id:'fx0', name:'测试', center:[0,0,0], count:10, style:'DOT',
    code:'[x,y,z] = [i, 0, 0]; [r,g,b,a] = [1,1,1,1]; sc = 0.3', vars:{ rad:{ expr:'3', kf:[] } }, duration:0, step:5, preset:null, params:null, ui:null }];
  const fx = state.functions[0];
  state.selectedFunction = 'fx0';

  ensurePuzzleDom();
  openBlockDrawer(fx);

  // 1) 解析为主链
  ok('chain parse', bctx.chain.length === 3 && bctx.chain[0].kind === 'pos');
  ok('varExprs', 'rad' in bctx.varExprs);

  // 2) 回显
  ok('echo non-empty', document.getElementById('echo-text').textContent.indexOf('[x,y,z]') >= 0);

  // 3) 追加 set 到主链
  bctxPushUndo();
  bctx.chain.push({ kind:'set', name:'xx', expr:{ kind:'op', op:'*', a:{kind:'var',name:'i'}, b:{kind:'num',value:2} } });
  ok('append set', statementsToCode(bctx.chain).indexOf('xx = i * 2') >= 0);

  // 4) 撤销
  bctxUndo();
  ok('undo remove set', bctx.chain.length === 3);

  // 5) 碎片：从主链拆下后两段
  bctxPushUndo();
  const grp = bctx.chain.splice(1);
  bctx.frags.push({ stmts: grp, x: 100, y: 100 });
  ok('split frag', bctx.chain.length === 1 && bctx.frags.length === 1);
  // 主链生成代码只含第一段
  ok('frag not in code', statementsToCode(bctx.chain).indexOf('[r,g,b,a]') < 0);

  // 6) 写回
  closeBlockDrawer(true);
  ok('commit code', fx.code.indexOf('[x,y,z]') >= 0 && fx.code.indexOf('[r,g,b,a]') < 0);
  ok('ui saved', fx.ui && fx.ui.chain && fx.ui.frags && fx.ui.frags.length === 1);
  ok('drawer closed', !bctx);

  // 7) 重开恢复碎片
  openBlockDrawer(fx);
  ok('frag restored', bctx.frags.length === 1 && bctx.frags[0].stmts[0].kind === 'col');

  // 7b) 拖动运算符删除：删中间运算符+其后数值，再删至退化（chain 替换为剩余项）
  const ch = { kind:'chain', terms:[{kind:'var',name:'a'},{kind:'var',name:'b'},{kind:'var',name:'c'}], ops:['+','*'] };
  bctx.chain.push({ kind:'set', name:'tmp', expr: ch });
  removeChainOp(ch, 0);
  ok('op remove middle', ch.ops.length === 1 && ch.ops[0] === '*' && ch.terms.length === 2 && ch.terms[0].name === 'a' && ch.terms[1].name === 'c');
  removeChainOp(ch, 0);
  const lastSet = bctx.chain[bctx.chain.length - 1];
  ok('op remove degenerate', lastSet.kind === 'set' && lastSet.expr.kind === 'var' && lastSet.expr.name === 'a');

  closeBlockDrawer(false);

  // 8) 类型约束
  ok('typeAccepts scalar<-vec false', !typeAccepts(T_SCALAR, T_VEC));
  ok('typeAccepts any<-vec true', typeAccepts(T_ANY, T_VEC));

  // 9) 负数字面量
  const nn = parseExpr('-3');
  ok('neg num', nn.kind === 'num' && nn.value === -3);

  console.log(__fail ? 'RESULT: FAIL' : 'RESULT: PASS');
  `,
].join('\n');

global.THREE = { Vector3: class {}, OrbitControls: function () {} };
eval(src);
if (global.__fail) process.exit(1);
