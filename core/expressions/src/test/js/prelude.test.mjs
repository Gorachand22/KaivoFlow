// Node test harness for the exact expression prelude that ships in the Android app.
// Run: node core/expressions/src/test/js/prelude.test.mjs
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert';

const here = dirname(fileURLToPath(import.meta.url));
const prelude = readFileSync(join(here, '../../main/resources/js/kaivo_expression_prelude.js'), 'utf8');

/** Mirrors the QuickJS adapter: fresh context, prelude once, JSON in / JSON out. */
function harness() {
  const context = vm.createContext({});
  vm.runInContext(prelude, context);
  return {
    evaluate(code, scope) {
      context.__kaivoDefineScope(JSON.stringify(scope));
      const envelope = JSON.parse(context.__kaivoEval(code));
      if (!envelope.ok) throw new Error(envelope.error);
      return envelope.value === null ? null : JSON.parse(envelope.value);
    },
    raw(code, scope) {
      context.__kaivoDefineScope(JSON.stringify(scope));
      return JSON.parse(context.__kaivoEval(code));
    },
  };
}

const scope = {
  json: { amount: 1250.5, currency: 'INR', merchant: '  Cafe Mocha  ', tags: ['food', 'coffee'], ok: true },
  items: [{ amount: 100 }, { amount: 250 }, { amount: 400 }],
  item: { amount: 1250.5 },
  index: 0,
  vars: { sheetId: 'abc123', threshold: 500 },
  now: '2026-11-13T04:30:00Z',
  today: '2026-11-13',
  run: { id: 'run-1', step: 'Parse SMS', timezone: 'Asia/Kolkata' },
  step: {
    'Parse SMS': [{ text: 'Your a/c debited ₹1250.50' }],
    'Fetch rows': [{ id: 1 }, { id: 2 }, { id: 3 }],
  },
};

let passed = 0;
const failures = [];
function check(name, fn) {
  try {
    fn();
    passed++;
  } catch (e) {
    failures.push(`${name}: ${e.message}`);
  }
}

// ---------------------------------------------------------------- basics
check('bare json path', () => {
  const h = harness();
  assert.equal(h.evaluate('$json.amount', scope), 1250.5);
  assert.equal(h.evaluate('$json.currency', scope), 'INR');
});
check('arithmetic keeps number type', () => {
  const h = harness();
  assert.equal(h.evaluate('$json.amount * 2', scope), 2501);
});
check('string concat', () => {
  const h = harness();
  assert.equal(h.evaluate('$json.currency + " " + $json.amount', scope), 'INR 1250.5');
});
check('missing intermediate path yields null + warning instead of failing the Run', () => {
  const h = harness();
  const env = h.raw('$json.nope.deep', scope);
  assert.equal(env.ok, true);
  assert.equal(env.value, null);
  assert.ok(env.warning && env.warning.includes('does not exist'));
});
check('calling a non-function still fails loudly', () => {
  const h = harness();
  const env = h.raw('$json.amount.nope()', scope);
  assert.equal(env.ok, false);
});
check('object result survives', () => {
  const h = harness();
  assert.deepEqual(h.evaluate('({ a: $json.amount, b: $vars.threshold })', scope), { a: 1250.5, b: 500 });
});
check('array result survives', () => {
  const h = harness();
  assert.deepEqual(h.evaluate('$items.map(i => i.amount)', scope), [100, 250, 400]);
});

// ---------------------------------------------------------------- helpers
check('$first / $last / $count', () => {
  const h = harness();
  assert.equal(h.evaluate('$first().amount', scope), 100);
  assert.equal(h.evaluate('$last().amount', scope), 400);
  assert.equal(h.evaluate('$count()', scope), 3);
});
check('$sum over items', () => {
  const h = harness();
  assert.equal(h.evaluate('$sum($items.map(i => i.amount))', scope), 750);
});
check('$number parses currency text', () => {
  const h = harness();
  assert.equal(h.evaluate('$number("Rs 1,250.50")', scope), 1250.5);
});
check('$number on garbage is null', () => {
  const h = harness();
  assert.equal(h.evaluate('$number("no digits")', scope), null);
});
check('$if ternary helper', () => {
  const h = harness();
  assert.equal(h.evaluate('$if($json.amount > $vars.threshold, "big", "small")', scope), 'big');
});
check('$trim / $upper / $slug', () => {
  const h = harness();
  assert.equal(h.evaluate('$trim($json.merchant)', scope), 'Cafe Mocha');
  assert.equal(h.evaluate('$upper($json.currency)', scope), 'INR');
  assert.equal(h.evaluate('$slug("Cafe Mocha & Bar")', scope), 'cafe-mocha-bar');
});
check('$replace replaces every occurrence', () => {
  const h = harness();
  assert.equal(h.evaluate('$replace("a-b-c", "-", "/")', scope), 'a/b/c');
});
check('$isEmpty variants', () => {
  const h = harness();
  assert.equal(h.evaluate('$isEmpty("   ")', scope), true);
  assert.equal(h.evaluate('$isEmpty([])', scope), true);
  assert.equal(h.evaluate('$isEmpty({})', scope), true);
  assert.equal(h.evaluate('$isEmpty(0)', scope), false);
});
check('$round / $abs / $min / $max', () => {
  const h = harness();
  assert.equal(h.evaluate('$round(1250.555, 2)', scope), 1250.56);
  assert.equal(h.evaluate('$abs(-7)', scope), 7);
  assert.equal(h.evaluate('$min(4, 9, 1)', scope), 1);
  assert.equal(h.evaluate('$max(4, 9, 1)', scope), 9);
});
check('$sortBy / $unique / $filter', () => {
  const h = harness();
  assert.deepEqual(h.evaluate('$sortBy($items, "amount", true).map(i => i.amount)', scope), [400, 250, 100]);
  assert.deepEqual(h.evaluate('$unique([1,1,2,2,3])', scope), [1, 2, 3]);
  assert.deepEqual(h.evaluate('$filter($items, "amount", 250)', scope), [{ amount: 250 }]);
});
check('$merge shallow merges objects', () => {
  const h = harness();
  assert.deepEqual(h.evaluate('$merge($json, { extra: 1 })', scope).extra, 1);
});
check('$parseJson round trip', () => {
  const h = harness();
  assert.equal(h.evaluate('$parseJson("{\\"a\\":1}").a', scope), 1);
});
check('$json text helper is shadowed by data but $string works', () => {
  const h = harness();
  assert.equal(h.evaluate('typeof $string', scope), 'function');
  assert.equal(h.evaluate('$string(true)', scope), 'true');
});

// ---------------------------------------------------------------- steps
check('$step by name returns items', () => {
  const h = harness();
  assert.equal(h.evaluate('$step("Parse SMS").text', scope), 'Your a/c debited ₹1250.50');
  assert.equal(h.evaluate('$step("Fetch rows").length', scope), 3);
});
check('$step with field selects that field', () => {
  const h = harness();
  assert.deepEqual(h.evaluate('$step("Fetch rows", "id")', scope), [1, 2, 3]);
});
check('$step unknown name is null', () => {
  const h = harness();
  assert.equal(h.evaluate('$step("Nope")', scope), null);
});
check('$node legacy alias still works', () => {
  const h = harness();
  assert.equal(h.evaluate('$node["Parse SMS"].json.text', scope), 'Your a/c debited ₹1250.50');
});

// ---------------------------------------------------------------- dates
check('$now and $today are bound', () => {
  const h = harness();
  assert.equal(h.evaluate('$now', scope), '2026-11-13T04:30:00Z');
  assert.equal(h.evaluate('$today', scope), '2026-11-13');
});
check('$dateAdd months', () => {
  const h = harness();
  assert.equal(h.evaluate('$formatDate($dateAdd("2026-01-31", 1, "months"), "YYYY-MM-DD")', scope), '2026-03-03');
});
check('$dateDiff in days', () => {
  const h = harness();
  assert.equal(h.evaluate('$dateDiff("2026-11-01", "2026-11-13", "d")', scope), 12);
});
check('$formatDate pattern', () => {
  const h = harness();
  assert.equal(h.evaluate('$formatDate("2026-11-13T04:30:00Z", "DD/MM/YYYY HH:mm")', scope).length, 16);
});

// ---------------------------------------------------------------- safety
check('syntax error returns ok:false not a crash', () => {
  const h = harness();
  const env = h.raw('$json.amount +', scope);
  assert.equal(env.ok, false);
  assert.ok(env.error.length > 0);
});
check('runtime error returns ok:false', () => {
  const h = harness();
  const env = h.raw('notDefinedAnywhere()', scope);
  assert.equal(env.ok, false);
  assert.ok(env.error.includes('notDefinedAnywhere'));
});
check('undefined result becomes null', () => {
  const h = harness();
  assert.equal(h.evaluate('undefined', scope), null);
});
check('circular structure does not explode', () => {
  const h = harness();
  const env = h.raw('(function(){ const a = {}; a.self = a; return a; })()', scope);
  assert.equal(env.ok, true);
});
check('no host object leaks into the sandbox', () => {
  const h = harness();
  assert.equal(h.evaluate('typeof process', scope), 'undefined');
  assert.equal(h.evaluate('typeof require', scope), 'undefined');
});
check('NaN and Infinity serialise as null', () => {
  const h = harness();
  assert.equal(h.evaluate('0/0', scope), null);
  assert.equal(h.evaluate('1/0', scope), null);
});

console.log(`\nexpression prelude: ${passed} passed, ${failures.length} failed`);
if (failures.length) {
  failures.forEach((f) => console.error('  ✗ ' + f));
  process.exit(1);
}
