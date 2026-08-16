const fs = require('fs');
const src = [
  fs.readFileSync('js/easing.js','utf8'),
  fs.readFileSync('js/constants.js','utf8'),
  fs.readFileSync('js/blocks.js','utf8'),
  `
  let fail=0; function ok(n,c){ console.log((c?'PASS ':'FAIL ')+n); if(!c) fail++; }
  // 负数字面量往返
  const c1 = exprToCode(parseExpr('-3'), 0); ok('expr -3 -> '+c1, c1 === '-3');
  ok('expr -3 idempotent', exprToCode(parseExpr(c1),0) === c1);
  // -x 往返
  const c2 = exprToCode(parseExpr('-x'), 0); ok('expr -x -> '+c2, c2 === '-x');
  // 负数作为操作数加括号
  const n = { kind:'op', op:'*', a:{kind:'num',value:2}, b:{kind:'num',value:-3} };
  const c3 = exprToCode(n, 0); ok('2 * -3 -> '+c3, c3 === '2 * (-3)');
  // 求值一致
  ok('eval -3', evaluate('-3',{})===-3);
  ok('eval -x*y', evaluate('-x*y',{x:2,y:3})===-6);
  ok('eval 2*-3', evaluate('2 * (-3)',{})===-6);
  ok('eval -(2+3)', evaluate('-(2+3)',{})===-5);
  // stmt 含负数
  const s = stmtToNode('sc = -3');
  ok('stmt sc=-3', s.kind==='scl' && s.expr.kind==='num' && s.expr.value===-3);
  ok('stmt roundtrip', stmtToCode(s) === 'sc = -3');
  // 完整代码块
  const code = 'sc = -3; [x,y,z] = [i, -2, 0]';
  const stmts = codeToStatements(code);
  const re = statementsToCode(stmts);
  ok('code roundtrip', re === 'sc = -3;' + String.fromCharCode(10) + '[x,y,z] = [i, -2, 0]');
  // 幂等
  ok('code idempotent', statementsToCode(codeToStatements(re)) === re);
  console.log(fail?'RESULT: FAIL':'RESULT: PASS');
  `
].join('\n');
global.THREE = { OrbitControls: function(){}, Vector3: class { constructor(x,y,z){this.x=x||0;this.y=y||0;this.z=z||0;} } };
global.document = { getElementById:()=>null, querySelectorAll:()=>[], createElement:()=>({appendChild(){},style:{},classList:{add(){},remove(){},toggle(){}}}) };
eval(src);
