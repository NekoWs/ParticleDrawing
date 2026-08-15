/* =========================================================================
 * 缓动求值
 * ======================================================================= */

function cubicBezier(t, x1, y1, x2, y2) {
  if (x1 === y1 && x2 === y2) return t; // 对角贝塞尔（含 LINEAR）即线性，避免牛顿迭代浮点误差
  const cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
  const cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
  const xFor = s => ((ax * s + bx) * s + cx) * s;
  const dxFor = s => (3 * ax * s + 2 * bx) * s + cx;
  let s = t;
  for (let i = 0; i < 8; i++) { const e = xFor(s) - t; if (Math.abs(e) < 1e-6) break; s -= e / dxFor(s); }
  return ((ay * s + by) * s + cy) * s;
}

function easeVal(t, easing) {
  const t1 = Math.min(1, Math.max(0, t));
  if (Array.isArray(easing)) return cubicBezier(t1, easing[0], easing[1], easing[2], easing[3]);
  const p = EASINGS[easing] || EASINGS[0];
  return cubicBezier(t1, p[1], p[2], p[3], p[4]);
}

function easeInOut(t) { return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2; }

/* =========================================================================
 * 迷你表达式求值器（标量 / 向量 vec3 / 矩阵 mat3）
 * ======================================================================= */

// —— 值类型标记 ——
function isVec(v) { return v != null && v.__v === 3; }
function isMat(v) { return v != null && v.__m === 3; }
function vec3(x, y, z) { return { __v: 3, x, y, z }; }
function mat3(m) { return { __m: 3, m }; }

const FUNCS = {
  sin: 1, cos: 1, tan: 1, asin: 1, acos: 1, atan: 1, atan2: 2,
  sqrt: 1, abs: 1, exp: 1, log: 1, ln: 1,
  floor: 1, ceil: 1, round: 1, pow: 2, min: 2, max: 2, clamp: 3, lerp: 3,
  vec: 3, dot: 2, cross: 2, len: 1, norm: 1,
  rotX: 1, rotY: 1, rotZ: 1, rotAxis: 2,
  polar: 2, sphere: 3, torus: 4,
};
const FUNC_IMPL = {
  sin: a => Math.sin(a), cos: a => Math.cos(a), tan: a => Math.tan(a),
  asin: a => Math.asin(a), acos: a => Math.acos(a), atan: a => Math.atan(a), atan2: (a, b) => Math.atan2(a, b),
  sqrt: a => Math.sqrt(a), abs: a => Math.abs(a), exp: a => Math.exp(a),
  log: a => Math.log(a), ln: a => Math.log(a),
  floor: a => Math.floor(a), ceil: a => Math.ceil(a), round: a => Math.round(a),
  pow: (a, b) => Math.pow(a, b), min: (a, b) => Math.min(a, b), max: (a, b) => Math.max(a, b),
  clamp: (a, b, c) => Math.min(Math.max(a, b), c), lerp: (a, b, c) => a + (b - a) * c,
  vec: (x, y, z) => vec3(x, y, z),
  dot: (a, b) => { if (!isVec(a) || !isVec(b)) throw new Error('dot 需要向量'); return a.x * b.x + a.y * b.y + a.z * b.z; },
  cross: (a, b) => { if (!isVec(a) || !isVec(b)) throw new Error('cross 需要向量'); return vec3(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x); },
  len: a => isVec(a) ? Math.hypot(a.x, a.y, a.z) : Math.abs(a),
  norm: a => { if (!isVec(a)) throw new Error('norm 需要向量'); const l = Math.hypot(a.x, a.y, a.z) || 1; return vec3(a.x / l, a.y / l, a.z / l); },
  rotX: t => { const c = Math.cos(t), s = Math.sin(t); return mat3([[1, 0, 0], [0, c, -s], [0, s, c]]); },
  rotY: t => { const c = Math.cos(t), s = Math.sin(t); return mat3([[c, 0, s], [0, 1, 0], [-s, 0, c]]); },
  rotZ: t => { const c = Math.cos(t), s = Math.sin(t); return mat3([[c, -s, 0], [s, c, 0], [0, 0, 1]]); },
  rotAxis: (axis, t) => {
    if (!isVec(axis)) throw new Error('rotAxis 需要向量轴');
    const l = Math.hypot(axis.x, axis.y, axis.z) || 1;
    const x = axis.x / l, y = axis.y / l, z = axis.z / l;
    const c = Math.cos(t), s = Math.sin(t), C = 1 - c;
    return mat3([
      [c + x * x * C, x * y * C - z * s, x * z * C + y * s],
      [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
      [z * x * C - y * s, z * y * C + x * s, c + z * z * C],
    ]);
  },
  polar: (r, a) => vec3(r * Math.cos(a), 0, r * Math.sin(a)),
  sphere: (r, th, ph) => vec3(r * Math.sin(th) * Math.cos(ph), r * Math.cos(th), r * Math.sin(th) * Math.sin(ph)),
  torus: (R, r, th, ph) => vec3((R + r * Math.cos(th)) * Math.cos(ph), r * Math.sin(th), (R + r * Math.cos(th)) * Math.sin(ph)),
};
const PREC = { '+': 1, '-': 1, '*': 2, '/': 2, '%': 2, '^': 3 };

function matVec(M, v) {
  const m = M.m;
  return vec3(
    m[0][0] * v.x + m[0][1] * v.y + m[0][2] * v.z,
    m[1][0] * v.x + m[1][1] * v.y + m[1][2] * v.z,
    m[2][0] * v.x + m[2][1] * v.y + m[2][2] * v.z,
  );
}
function matMat(A, B) {
  const a = A.m, b = B.m, o = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) for (let k = 0; k < 3; k++) o[i][j] += a[i][k] * b[k][j];
  return mat3(o);
}

function applyOp(op, a, b) {
  if (op === '+') {
    if (isVec(a) && isVec(b)) return vec3(a.x + b.x, a.y + b.y, a.z + b.z);
    if (isMat(a) && isMat(b)) return mat3(a.m.map((r, i) => r.map((c, j) => c + b.m[i][j])));
    if (typeof a === 'number' && typeof b === 'number') return a + b;
    throw new Error('运算符 + 类型不匹配');
  }
  if (op === '-') {
    if (isVec(a) && isVec(b)) return vec3(a.x - b.x, a.y - b.y, a.z - b.z);
    if (isMat(a) && isMat(b)) return mat3(a.m.map((r, i) => r.map((c, j) => c - b.m[i][j])));
    if (typeof a === 'number' && typeof b === 'number') return a - b;
    throw new Error('运算符 - 类型不匹配');
  }
  if (op === '*') {
    if (isMat(a)) {
      if (isVec(b)) return matVec(a, b);
      if (isMat(b)) return matMat(a, b);
      if (typeof b === 'number') return mat3(a.m.map(r => r.map(c => c * b)));
    }
    if (isVec(a)) {
      if (typeof b === 'number') return vec3(a.x * b, a.y * b, a.z * b);
      if (isVec(b)) return vec3(a.x * b.x, a.y * b.y, a.z * b.z);
    }
    if (typeof a === 'number') {
      if (isVec(b)) return vec3(a * b.x, a * b.y, a * b.z);
      if (isMat(b)) return mat3(b.m.map(r => r.map(c => a * c)));
      return a * b;
    }
    throw new Error('运算符 * 类型不匹配');
  }
  if (op === '/') {
    if (isVec(a) && typeof b === 'number') return vec3(a.x / b, a.y / b, a.z / b);
    if (typeof a === 'number' && typeof b === 'number') return a / b;
    throw new Error('运算符 / 类型不匹配');
  }
  if (op === '%') {
    if (typeof a === 'number' && typeof b === 'number') return a % b;
    throw new Error('运算符 % 仅支持标量');
  }
  if (op === '^') {
    if (typeof a === 'number' && typeof b === 'number') return Math.pow(a, b);
    throw new Error('运算符 ^ 仅支持标量');
  }
  throw new Error('未知运算符: ' + op);
}

function tokenize(expr) {
  const tokens = [];
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t' || c === '\n') { i++; continue; }
    if (c === '.' && (expr[i + 1] === 'x' || expr[i + 1] === 'y' || expr[i + 1] === 'z')) {
      tokens.push({ t: 'comp', axis: expr[i + 1] }); i += 2; continue;
    }
    if ((c >= '0' && c <= '9') || c === '.') {
      let j = i; while (j < expr.length && /[0-9.]/.test(expr[j])) j++;
      tokens.push({ t: 'num', v: parseFloat(expr.slice(i, j)) }); i = j; continue;
    }
    if (/[a-zA-Z_]/.test(c)) {
      let j = i; while (j < expr.length && /[a-zA-Z0-9_]/.test(expr[j])) j++;
      const name = expr.slice(i, j);
      if (name === 'pi') tokens.push({ t: 'num', v: Math.PI });
      else if (name === 'e') tokens.push({ t: 'num', v: Math.E });
      else if (name in FUNCS) tokens.push({ t: 'func', name });
      else tokens.push({ t: 'var', name });
      i = j; continue;
    }
    if ('+-*/%^(),'.includes(c)) { tokens.push({ t: c }); i++; continue; }
    i++;
  }
  return tokens;
}

function evaluate(expr, vars) {
  const output = [];
  const stack = [];
  for (const tk of tokenize(expr)) {
    if (tk.t === 'num') output.push(tk.v);
    else if (tk.t === 'var') {
      const v = (typeof vars === 'function') ? vars(tk.name) : vars[tk.name];
      if (v === undefined) throw new Error('未知变量: ' + tk.name);
      output.push(v);
    } else if (tk.t === 'func') stack.push(tk.name);
    else if (tk.t === 'comp') output.push(tk);
    else if (tk.t === ',') { while (stack.length && stack[stack.length - 1] !== '(') output.push(stack.pop()); }
    else if (tk.t === '(') stack.push(tk.t);
    else if (tk.t === ')') {
      while (stack.length && stack[stack.length - 1] !== '(') output.push(stack.pop());
      stack.pop();
      if (stack.length && stack[stack.length - 1] in FUNCS) output.push(stack.pop());
    } else if (tk.t in PREC) {
      const prec = PREC[tk.t], rightAssoc = tk.t === '^';
      while (stack.length) {
        const top = stack[stack.length - 1];
        if (top === '(') break;
        if (top in FUNCS) { output.push(stack.pop()); continue; }
        if (top in PREC && (PREC[top] > prec || (PREC[top] === prec && !rightAssoc))) output.push(stack.pop());
        else break;
      }
      stack.push(tk.t);
    }
  }
  while (stack.length) output.push(stack.pop());
  const s = [];
  for (const o of output) {
    if (typeof o === 'number' || isVec(o) || isMat(o)) s.push(o);
    else if (o.t === 'comp') {
      const v = s.pop();
      if (!isVec(v)) throw new Error('分量访问需要向量');
      s.push(o.axis === 'x' ? v.x : o.axis === 'y' ? v.y : v.z);
    } else if (o in FUNCS) { const args = []; for (let k = 0; k < FUNCS[o]; k++) args.unshift(s.pop()); s.push(FUNC_IMPL[o](...args)); }
    else if (o in PREC) { const b = s.pop(), a = s.pop(); s.push(applyOp(o, a, b)); }
  }
  return s[s.length - 1];
}

/* =========================================================================
 * 函数对象：公式代码块求值（分号分隔、顺序执行、打包/单分量赋值、临时变量）
 * ======================================================================= */

// 属性保留字（vars 与临时变量不可同名）
const ATTR_NAMES = ['x', 'y', 'z', 'r', 'g', 'b', 'a', 'vx', 'vy', 'vz', 'sc', 'glow', 'light'];

// 变量关键帧插值（kf: [tick, value, easing]，value 为标量）
function varKfValue(kf, t) {
  if (!kf || kf.length === 0) return 0;
  if (t <= kf[0][0]) return kf[0][1];
  if (t >= kf[kf.length - 1][0]) return kf[kf.length - 1][1];
  for (let i = 0; i < kf.length - 1; i++) {
    const a = kf[i], b = kf[i + 1];
    if (t >= a[0] && t <= b[0]) {
      const dur = b[0] - a[0];
      return a[1] + (b[1] - a[1]) * easeVal(dur === 0 ? 1 : (t - a[0]) / dur, b[2]);
    }
  }
  return kf[kf.length - 1][1];
}

// 解析名称列表 [x,y,z] → ['x','y','z']
function parseNameList(s) {
  const inner = s.trim().replace(/^\[/, '').replace(/\]$/, '');
  return inner.split(',').map(x => x.trim()).filter(Boolean);
}

// 解析表达式列表 [e1,e2,e3] → ['e1','e2','e3']（跳过括号内逗号）
function parseExprList(s) {
  const inner = s.trim();
  if (!inner.startsWith('[') || !inner.endsWith(']')) return [inner];
  const body = inner.slice(1, -1);
  const parts = [];
  let depth = 0, cur = '';
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '(') depth++;
    else if (c === ')') depth--;
    if (c === ',' && depth === 0) { parts.push(cur.trim()); cur = ''; }
    else cur += c;
  }
  if (cur.trim()) parts.push(cur.trim());
  return parts;
}

// 给属性赋值；返回 true 表示是属性名，false 表示非属性（临时变量）
function assignAttr(name, v, out, scope) {
  const val = (typeof v === 'number') ? v : (() => { throw new Error('属性 ' + name + ' 需要标量值'); })();
  switch (name) {
    case 'x': out.pos[0] = val; scope.x = val; break;
    case 'y': out.pos[1] = val; scope.y = val; break;
    case 'z': out.pos[2] = val; scope.z = val; break;
    case 'r': out.color[0] = val; scope.r = val; break;
    case 'g': out.color[1] = val; scope.g = val; break;
    case 'b': out.color[2] = val; scope.b = val; break;
    case 'a': out.color[3] = val; scope.a = val; break;
    case 'vx': out.vel[0] = val; scope.vx = val; break;
    case 'vy': out.vel[1] = val; scope.vy = val; break;
    case 'vz': out.vel[2] = val; scope.vz = val; break;
    case 'sc': out.scale = val; scope.sc = val; break;
    case 'glow': out.glow = val > 0.5; scope.glow = val; break;
    case 'light': out.light = val; scope.light = val; break;
    default: return false;
  }
  return true;
}

// 执行公式代码块，返回 { pos, color, vel, scale, glow, light }
function evalFunctionCode(code, env) {
  const out = { pos: [0, 0, 0], color: [1, 1, 1, 1], vel: [0, 0, 0], scale: 1, glow: false, light: 0 };
  const scope = { ...env };
  const stmts = (code || '').split(';').map(s => s.trim()).filter(Boolean);
  for (const stmt of stmts) {
    const eq = stmt.indexOf('=');
    if (eq < 0) throw new Error('表达式缺少 = : ' + stmt);
    const lhs = stmt.slice(0, eq).trim();
    const rhs = stmt.slice(eq + 1).trim();
    if (lhs.startsWith('[')) {
      const names = parseNameList(lhs);
      if (rhs.startsWith('[')) {
        const exprs = parseExprList(rhs);
        if (names.length !== exprs.length) throw new Error('赋值数量不匹配: ' + stmt);
        for (let i = 0; i < names.length; i++) {
          const v = evaluate(exprs[i], scope);
          if (!assignAttr(names[i], v, out, scope)) scope[names[i]] = v;
        }
      } else {
        // 单表达式：支持向量拆包 [x,y,z] = 向量表达式
        const v = evaluate(rhs, scope);
        if (isVec(v) && names.length === 3) {
          const comps = [v.x, v.y, v.z];
          for (let i = 0; i < 3; i++) {
            if (!assignAttr(names[i], comps[i], out, scope)) scope[names[i]] = comps[i];
          }
        } else if (names.length === 1) {
          if (!assignAttr(names[0], v, out, scope)) scope[names[0]] = v;
        } else {
          throw new Error('赋值数量不匹配: ' + stmt);
        }
      }
    } else {
      const v = evaluate(rhs, scope);
      if (!assignAttr(lhs, v, out, scope)) scope[lhs] = v;
    }
  }
  return out;
}
