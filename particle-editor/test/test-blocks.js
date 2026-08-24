/* blocks.js 双向转换 + 求值一致性测试 */
const fs = require('fs');
const src = [
  fs.readFileSync('js/langs.js', 'utf8'),
  fs.readFileSync('js/i18n.js', 'utf8'),
  fs.readFileSync('js/easing.js', 'utf8'),
  fs.readFileSync('js/constants.js', 'utf8'),
  fs.readFileSync('js/blocks.js', 'utf8'),
  `
  let __fail = false;
  function ok(name, cond) { if (cond) console.log('PASS ' + name); else { console.log('FAIL ' + name); __fail = true; } }

  // 兼容 shim：测试用字符串签名，源码 API 为 compileFunctionCode → execFunctionCode
  function evalFunctionCode(src, env) { return execFunctionCode(compileFunctionCode(src), env); }

  // 收集测试代码片段（含预设 code）
  const samples = [
    'th = acos(1-2*(i+0.5)/n)',
    'ph = i*pi*(3-sqrt(5))',
    '[x,y,z] = [rad*sin(th)*cos(ph), rad*cos(th), rad*sin(th)*sin(ph)]',
    '[r,g,b,a] = [1,1,1,1]',
    'sc = 0.3',
    'glow = 1',
    'light = 12',
    'aa = (i%m)/m*2*pi',
    '[x,y,z] = [((floor(i/(n*n)))/(n-1)-0.5)*edge, ((floor((i%(n*n))/n))/(n-1)-0.5)*edge, ((i%(n*n)%n)/(n-1)-0.5)*edge]',
    '[x,y,z] = rotY(pi/2) * vec(1, 0, 0)',
    '[x,y,z] = polar(3, pi/2)',
    'rr = sqrt(inner^2 + (outer^2-inner^2)*i/n)',
    'xx = min(5, max(0, i*2))',
    'yy = clamp(i, 0, n) + lerp(0, 1, t/40)',
    '[x,y,z] = sphere(rad, i/n*pi, i*2)',
    'tmp = dot(vec(1,0,0), vec(0,1,0))',
    '[x,y,z] = rotZ(pi/4) * vec(3,0,0)',
    'v = vec(1,2,3).x + len(vec(3,4,0))',
  ];

  // 1) 每条语句：code → stmt → code 往返，且再往返一次幂等
  for (const s of samples) {
    let node, back;
    try {
      node = stmtToNode(s);
      back = stmtToCode(node);
    } catch (e) {
      ok('parse ' + s + ' -> ' + e.message, false);
      continue;
    }
    // 幂等性：back 再解析再生成应等于 back
    const back2 = stmtToCode(stmtToNode(back));
    ok('idempotent ' + s, back === back2);
  }

  // 2) 往返后求值一致：对同一 env，evalFunctionCode(原 code) === evalFunctionCode(往返 code)
  const envs = [ { i: 3, n: 200, t: 10, rad: 3, m: 24, k: 12, inner: 0, outer: 4, edge: 4, th: 0.5, ph: 1.2 } ];
  for (const env of envs) {
    for (const s of samples) {
      // 语句可能是属性赋值，需包裹成完整 code 块才可求值（属性缺省其余分量取默认）
      let v0, v1;
      try {
        v0 = evalFunctionCode(s, env);
        const node = stmtToNode(s);
        v1 = evalFunctionCode(stmtToCode(node), env);
      } catch (e) {
        ok('eval ' + s + ' -> ' + e.message, false);
        continue;
      }
      ok('eval-consist ' + s, JSON.stringify(v0) === JSON.stringify(v1));
    }
  }

  // 3) 完整预设 code：codeToStatements → statementsToCode 往返，且求值一致
  const presetCode = FUNCTION_PRESETS.cube.build({ edge: 4 }).code;
  const stmts = codeToStatements(presetCode);
  const reCode = statementsToCode(stmts);
  ok('preset stmts count', stmts.length === 2); // 当前 cube 模板：位置 + 颜色两条语句
  ok('preset idempotent', statementsToCode(codeToStatements(reCode)) === reCode);
  for (let i = 0; i < 4; i++) {
    const env = { i, n: 512, edge: 4, sx: 8, sy: 8, sz: 8, t: 5 };
    const a = evalFunctionCode(presetCode, env);
    const b = evalFunctionCode(reCode, env);
    ok('preset eval-consist i=' + i, JSON.stringify(a) === JSON.stringify(b));
  }

  // 4) 表达式级往返（幂等 + 优先级保持）
  const exprs = [
    'rad*sin(th)*cos(ph)',
    '1-2*(i+0.5)/n',
    '(a+b)*c',
    'a+b*c',
    '(a-b)-c',
    'a-(b-c)',
    '2^3^2',
    '(2^3)^2',
    'a*b+c*d',
  ];
  for (const e of exprs) {
    const node = parseExpr(e);
    const back = exprToCode(node, 0);
    // 幂等
    ok('expr idempotent ' + e, exprToCode(parseExpr(back), 0) === back);
    // 优先级保真：对含变量表达式用对应 env 求值，原式与生成式一致
    const env = { a: 2, b: 3, c: 4, d: 5, i: 3, n: 10, rad: 2, th: 0.5, ph: 1.2 };
    const v0 = evaluate(e, env);
    const v1 = evaluate(back, env);
    ok('expr eval-consist ' + e, Math.abs(v0 - v1) < 1e-12);
  }

  // 5) 类型推断
  const vt = (name) => (name === 'i' || name === 'n' || name === 't' || name === 'rad' || name === 'th') ? T_SCALAR : T_ANY;
  ok('type vec', exprType(parseExpr('vec(1,2,3)'), vt) === T_VEC);
  ok('type mat', exprType(parseExpr('rotZ(pi)'), vt) === T_MAT);
  ok('type scalar', exprType(parseExpr('i*2'), vt) === T_SCALAR);
  ok('type comp scalar', exprType(parseExpr('vec(1,2,3).x'), vt) === T_SCALAR);
  ok('type mat*vec = vec', exprType(parseExpr('rotZ(pi)*vec(1,0,0)'), vt) === T_VEC);
  ok('type dot scalar', exprType(parseExpr('dot(vec(1,0,0), vec(0,1,0))'), vt) === T_SCALAR);

  // 6) 临时变量收集
  const temps = collectTemps(codeToStatements('aa = i*2; bb = aa+1; [x,y,z]=[aa,bb,0]'));
  ok('collectTemps', JSON.stringify(temps) === JSON.stringify(['aa', 'bb']));

  // 7) 单属性赋值解析为 attr 块（设置 x/y/z 等属性）
  const attrNode = stmtToNode('x = 5');
  ok('parse attr x', attrNode.kind === 'attr' && attrNode.name === 'x' && attrNode.expr.kind === 'num' && attrNode.expr.value === 5);
  ok('attr roundtrip', stmtToCode(attrNode) === 'x = 5');
  threw = false; try { stmtToNode('glow = 0.5'); } catch (_) { threw = true; }
  ok('reject glow non-binary', threw);

  console.log(__fail ? 'RESULT: FAIL' : 'RESULT: PASS');
  `,
].join('\n');
global.THREE = { Vector3: class {}, OrbitControls: function () {} };
global.document = { getElementById: () => null, querySelectorAll: () => [], createElement: () => ({ appendChild() {}, style: {}, classList: { add() {}, remove() {}, toggle() {} } }) };
eval(src);
if (global.__fail) process.exit(1);
