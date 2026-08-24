/* i18n 完整性校验：动态键枚举 + 静态 t()/tf()/错误键 扫描 + zh/en 键集合一致性 */
const fs = require('fs');
const path = require('path');
const DIR = path.join(__dirname, '..', 'js');

const src = {};
for (const f of fs.readdirSync(DIR)) {
  if (f.endsWith('.js')) src[f] = fs.readFileSync(path.join(DIR, f), 'utf8');
}
/* constants.js/blocks-ui.js 顶层依赖 THREE/DOM —— 与其它测试一致提供桩 */
const makeEl = () => ({
  children: [], textContent: '', value: '', className: '', dataset: {}, id: '',
  style: {}, classList: { add(){}, remove(){}, toggle(){}, contains(){ return false; } },
  appendChild(c) { return c; }, append(c) { return c; }, insertBefore(n) { return n; },
  addEventListener(){}, removeEventListener(){}, remove(){},
  set innerHTML(v) { this._html = v; }, get innerHTML() { return this._html || ''; },
  querySelector(){ return null; }, querySelectorAll(){ return []; },
  closest(){ return null; }, matches(){ return false; },
  getBoundingClientRect() { return { left: 0, top: 0, width: 100, height: 100 }; },
});
global.document = {
  getElementById() { return null; },
  querySelectorAll() { return []; }, querySelector() { return null; },
  createElement() { return makeEl(); }, createTextNode(t) { return { textContent: String(t), nodeType: 3 }; },
  body: makeEl(),
};
global.window = { addEventListener(){}, innerWidth: 1200, innerHeight: 800 };
global.localStorage = { getItem(){ return null; }, setItem(){}, removeItem(){} };
const stub = () => function Stub() {};
global.THREE = new Proxy({}, { get: (_, p) => (p === Symbol.toPrimitive ? undefined : stub()) });
/* 与 test-blocks-ui 一致的源清单（blocks-ui 顶层会执行拖拽初始化，需 easing/float-window/undo） */
const ORDER = ['langs.js', 'i18n.js', 'easing.js', 'constants.js', 'blocks.js', 'float-window.js', 'undo.js', 'blocks-ui.js'];
ORDER.forEach(f => { if (!(f in src)) throw new Error('缺少源文件 ' + f); });
eval(ORDER.map(f => src[f]).join('\n')
  + '\n;globalThis.__I18NCTX = { LANGS, STMT_BLOCKS, FUNC_BLOCKS, PALETTE_GROUPS, OP_LABELS, TYPE_LABEL, BUILTIN_VAR_INFO, STMT_SLOTS, UV_MODES, PROP_LABELS, FUNCTION_PRESETS };');
const T = globalThis.__I18NCTX;
const problems = [];

/* 1) 键字符集必须为短 ASCII；zh/en 键集合一致；en 无缺失 */
const KEY_RE = /^[A-Za-z0-9_.]+$/;
for (const [lang, table] of Object.entries(T.LANGS)) {
  for (const k of Object.keys(table)) {
    if (!KEY_RE.test(k)) problems.push(`[${lang}] 键名非英文短键: ${k}`);
    if (/[\u4e00-\u9fff\u3400-\u4dbf]/.test(k)) problems.push(`[${lang}] 键名含中文: ${k}`);
  }
}
const zk = new Set(Object.keys(T.LANGS.zh)), ek = new Set(Object.keys(T.LANGS.en));
for (const k of zk) if (!ek.has(k)) problems.push(`en 缺失: ${k} (=${T.LANGS.zh[k]})`);
for (const k of ek) if (!zk.has(k)) problems.push(`zh 缺失: ${k} (=${T.LANGS.en[k]})`);

/* 2) 动态前缀键必须存在（两语言） */
const need = [];
for (const k of Object.keys(T.STMT_BLOCKS)) { need.push(`blk.stmt.${k}.label`, `blk.stmt.${k}.desc`); }
for (const name of Object.keys(T.FUNC_BLOCKS)) {
  need.push(`blk.func.${name}.desc`);
  for (const a of T.FUNC_BLOCKS[name].args) if (String(a[0]).startsWith('blk.')) need.push(a[0]);
}
for (const g of T.PALETTE_GROUPS) need.push(g.label);
for (const op of Object.values(T.OP_LABELS)) need.push(op);
for (const v of Object.values(T.TYPE_LABEL)) need.push(v);
for (const v of Object.values(T.BUILTIN_VAR_INFO)) need.push(v);
for (const rows of Object.values(T.STMT_SLOTS)) for (const sl of rows) if (String(sl[0]).startsWith('blk.')) need.push(sl[0]);
for (const m of Object.keys(T.UV_MODES)) need.push(`uv.mode.${m}`);
for (const p of Object.keys(T.PROP_LABELS)) need.push(`prop.${p}`);
for (const id of Object.keys(T.FUNCTION_PRESETS)) need.push(`fx.preset.${id}`);
for (const p of Object.values(T.FUNCTION_PRESETS).flatMap(x => x.params || [])) need.push(`fx.param.${p.key}`);
for (const k of need) for (const lang of ['zh', 'en']) if (!(k in T.LANGS[lang])) problems.push(`[${lang}] 缺动态键: ${k}`);

/* 3) 静态扫描源码/HTML 中字面量引用的键是否存在
 *    - 跳过整行 // 注释；忽略以 . 结尾的动态前缀片段（如 'blk.stmt.' + k），由第 2 步按枚举校验 */
const CALL_RE = /\b(?:t|tf|_et|_etf)\(\s*'([A-Za-z0-9_.]+)'/g;
const HTML = fs.readFileSync(path.join(__dirname, '..', 'index.html'), 'utf8');
for (const [f, s] of [...Object.entries(src), ['index.html', HTML]]) {
  const re = f === 'index.html'
    ? /data-i18n(?:-title|-placeholder)?="([A-Za-z0-9_.]+)"/g
    : CALL_RE;
  for (const line of s.split('\n')) {
    if (/^\s*\/\//.test(line)) continue;
    let m;
    while ((m = re.exec(line))) {
      const k = m[1];
      if (f !== 'index.html' && k.endsWith('.')) continue; // 动态前缀，见第 2 步
      if (!(k in T.LANGS.zh)) problems.push(`${f}: 引用不存在的键 ${k}`);
      else if (!(k in T.LANGS.en)) problems.push(`${f}: en 缺失键 ${k}`);
    }
  }
}

/* 4) 占位符连续性：{0}..{n} 不缺号 */
for (const [lang, table] of Object.entries(T.LANGS)) {
  for (const [k, v] of Object.entries(table)) {
    const ph = [...String(v).matchAll(/\{(\d+)\}/g)].map(x => +x[1]);
    const maxIdx = ph.length ? Math.max(...ph) : -1;
    if (new Set(ph).size !== maxIdx + 1) problems.push(`[${lang}] ${k} 占位符不连续: ${v}`);
  }
}

if (problems.length) { console.log('FAIL\n' + problems.join('\n')); process.exit(1); }
console.log(`OK: zh=${zk.size} en=${ek.size} 键；${need.length} 个动态键 + 静态引用全部命中`);
