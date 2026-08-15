/* =========================================================================
 * 缓动求值
 * ======================================================================= */

function cubicBezier(t, x1, y1, x2, y2) {
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
 * 迷你表达式求值器
 * ======================================================================= */

const FUNCS = {
  sin: 1, cos: 1, tan: 1, sqrt: 1, abs: 1, exp: 1, log: 1, ln: 1,
  floor: 1, ceil: 1, round: 1, pow: 2, min: 2, max: 2, clamp: 3, lerp: 3,
};
const FUNC_IMPL = {
  sin: a => Math.sin(a), cos: a => Math.cos(a), tan: a => Math.tan(a),
  sqrt: a => Math.sqrt(a), abs: a => Math.abs(a), exp: a => Math.exp(a),
  log: a => Math.log(a), ln: a => Math.log(a),
  floor: a => Math.floor(a), ceil: a => Math.ceil(a), round: a => Math.round(a),
  pow: (a, b) => Math.pow(a, b), min: (a, b) => Math.min(a, b), max: (a, b) => Math.max(a, b),
  clamp: (a, b, c) => Math.min(Math.max(a, b), c), lerp: (a, b, c) => a + (b - a) * c,
};
const PREC = { '+': 1, '-': 1, '*': 2, '/': 2, '%': 2, '^': 3 };

function tokenize(expr) {
  const tokens = [];
  let i = 0;
  while (i < expr.length) {
    const c = expr[i];
    if (c === ' ' || c === '\t' || c === '\n') { i++; continue; }
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
      if (!(tk.name in vars)) throw new Error('未知变量: ' + tk.name);
      output.push(vars[tk.name]);
    } else if (tk.t === 'func') stack.push(tk.name);
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
    if (typeof o === 'number') s.push(o);
    else if (o in FUNCS) { const args = []; for (let k = 0; k < FUNCS[o]; k++) args.unshift(s.pop()); s.push(FUNC_IMPL[o](...args)); }
    else if (o in PREC) {
      const b = s.pop(), a = s.pop();
      if (o === '+') s.push(a + b); else if (o === '-') s.push(a - b); else if (o === '*') s.push(a * b);
      else if (o === '/') s.push(a / b); else if (o === '%') s.push(a % b); else if (o === '^') s.push(Math.pow(a, b));
    }
  }
  return s[s.length - 1];
}
