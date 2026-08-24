const fs = require('fs');
const src = fs.readFileSync('js/langs.js','utf8') + '\n' + fs.readFileSync('js/i18n.js','utf8') + '\n' + fs.readFileSync('js/easing.js','utf8');
eval(src);
let fail = 0;
function ok(n,c){ console.log((c?'PASS ':'FAIL ')+n); if(!c) fail++; }
ok('neg scalar', evaluate('-3', {}) === -3);
ok('neg var', evaluate('-x', {x:5}) === -5);
ok('neg expr', evaluate('-(2+3)', {}) === -5);
ok('neg prec: -2^2 = -4', evaluate('-2^2', {}) === -4);
ok('neg prec: (-2)^2 = 4', evaluate('(-2)^2', {}) === 4);
ok('a * -b', evaluate('3 * -2', {}) === -6);
ok('a - -b', evaluate('3 - -2', {}) === 5);
ok('neg vec', evaluate('-vec(1,2,3)', {}) !== undefined && evaluate('-vec(1,2,3)', {}).x === -1);
ok('double neg', evaluate('--3', {}) === 3);
ok('sin(-x)', Math.abs(evaluate('sin(-pi/2)', {}) + 1) < 1e-9);
console.log(fail ? 'RESULT: FAIL' : 'RESULT: PASS');
