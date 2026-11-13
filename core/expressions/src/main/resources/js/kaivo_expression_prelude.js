/**
 * KaivoFlow expression runtime — the sandboxed surface users see inside {{ }}.
 *
 * This file is the single source of truth: the Android QuickJS adapter ships it as a resource,
 * and the Node test harness (src/test/js) exercises exactly this file, so behaviour cannot drift
 * between the app and the tests.
 *
 * Contract with the host:
 *   __kaivoDefineScope(scopeJsonString) -> seeds the global scope for the next evaluation
 *   __kaivoEval(codeString)             -> returns a JSON string: {"ok":true,"value":...}
 *                                          or {"ok":false,"error":"...","stack":"..."}
 *
 * The host absolutely never injects host objects: only JSON goes in, only JSON comes out.
 */
(function () {
  'use strict';
  if (globalThis.__kaivoInstalled) return;
  globalThis.__kaivoInstalled = true;

  var MAX_DEPTH = 64;

  function safeStringify(value) {
    var seen = [];
    try {
      return JSON.stringify(value, function (key, val) {
        if (val === undefined) return null;
        if (typeof val === 'function') return null;
        if (typeof val === 'bigint') return Number(val);
        if (val !== null && typeof val === 'object') {
          if (seen.indexOf(val) !== -1) return null;
          if (seen.length > MAX_DEPTH) return null;
          seen.push(val);
        }
        if (typeof val === 'number' && !isFinite(val)) return null;
        return val;
      });
    } catch (e) {
      return null;
    }
  }

  function toArray(v) {
    if (v === null || v === undefined) return [];
    return Array.isArray(v) ? v : [v];
  }

  function pad(n) { return (n < 10 ? '0' : '') + n; }

  var helpers = {
    // ---- item access -------------------------------------------------------
    $first: function (list) { var a = toArray(list || $items); return a.length ? a[0] : null; },
    $last: function (list) { var a = toArray(list || $items); return a.length ? a[a.length - 1] : null; },
    $all: function () { return toArray($items); },
    $count: function (list) { return toArray(list === undefined ? $items : list).length; },
    $item: function (i) { return toArray($items)[i] || null; },

    // ---- logic / coercion --------------------------------------------------
    $if: function (cond, a, b) { return cond ? a : b; },
    $not: function (v) { return !v; },
    $equals: function (a, b) { return JSON.stringify(a) === JSON.stringify(b); },
    $isEmpty: function (v) {
      if (v === null || v === undefined) return true;
      if (typeof v === 'string') return v.trim().length === 0;
      if (Array.isArray(v)) return v.length === 0;
      if (typeof v === 'object') return Object.keys(v).length === 0;
      return false;
    },
    $number: function (v) {
      if (typeof v === 'number') return v;
      var n = parseFloat(String(v === null || v === undefined ? '' : v).replace(/[^0-9.\-]/g, ''));
      return isNaN(n) ? null : n;
    },
    $string: function (v) {
      if (v === null || v === undefined) return '';
      if (typeof v === 'object') return safeStringify(v);
      return String(v);
    },
    $boolean: function (v) {
      if (typeof v === 'boolean') return v;
      if (typeof v === 'number') return v !== 0;
      if (typeof v === 'string') return ['true', '1', 'yes', 'on'].indexOf(v.trim().toLowerCase()) !== -1;
      return !!v;
    },
    $keys: function (o) { return o ? Object.keys(o) : []; },
    $values: function (o) { return o ? Object.keys(o).map(function (k) { return o[k]; }) : []; },
    $merge: function () {
      var out = {};
      for (var i = 0; i < arguments.length; i++) {
        var src = arguments[i];
        if (!src || typeof src !== 'object') continue;
        for (var k in src) if (Object.prototype.hasOwnProperty.call(src, k)) out[k] = src[k];
      }
      return out;
    },
    $sum: function (list) {
      return toArray(list).reduce(function (a, b) { var n = helpers.$number(b); return a + (n === null ? 0 : n); }, 0);
    },
    $unique: function (list) {
      var seen = [], out = [];
      toArray(list).forEach(function (v) {
        var key = typeof v === 'object' ? safeStringify(v) : String(v);
        if (seen.indexOf(key) === -1) { seen.push(key); out.push(v); }
      });
      return out;
    },
    $sortBy: function (list, field, desc) {
      var copy = toArray(list).slice();
      copy.sort(function (a, b) {
        var av = field ? (a || {})[field] : a;
        var bv = field ? (b || {})[field] : b;
        if (av === bv) return 0;
        var r = av > bv ? 1 : -1;
        return desc ? -r : r;
      });
      return copy;
    },
    $filter: function (list, field, value) {
      return toArray(list).filter(function (it) { return (it || {})[field] === value; });
    },
    $map: function (list, field) {
      return toArray(list).map(function (it) {
        return field.split('.').reduce(function (acc, part) { return acc === null || acc === undefined ? acc : acc[part]; }, it);
      });
    },

    // ---- text --------------------------------------------------------------
    $lower: function (s) { return String(s === undefined || s === null ? '' : s).toLowerCase(); },
    $upper: function (s) { return String(s === undefined || s === null ? '' : s).toUpperCase(); },
    $trim: function (s) { return String(s === undefined || s === null ? '' : s).trim(); },
    $replace: function (s, find, repl) { return String(s === undefined || s === null ? '' : s).split(find).join(repl); },
    $replaceAll: function (s, pattern, repl) { return String(s === undefined || s === null ? '' : s).replace(new RegExp(pattern, 'g'), repl); },
    $split: function (s, sep) { return String(s === undefined || s === null ? '' : s).split(sep === undefined ? ',' : sep); },
    $join: function (list, sep) { return toArray(list).join(sep === undefined ? ', ' : sep); },
    $substring: function (s, start, end) {
      var str = String(s === undefined || s === null ? '' : s);
      return end === undefined ? str.substring(start) : str.substring(start, end);
    },
    $length: function (v) {
      if (v === null || v === undefined) return 0;
      if (typeof v === 'string' || Array.isArray(v)) return v.length;
      if (typeof v === 'object') return Object.keys(v).length;
      return 0;
    },
    $includes: function (s, needle) { return String(s === undefined || s === null ? '' : s).indexOf(needle) !== -1; },
    $startsWith: function (s, prefix) { return String(s === undefined || s === null ? '' : s).indexOf(prefix) === 0; },
    $endsWith: function (s, suffix) {
      var str = String(s === undefined || s === null ? '' : s);
      return str.lastIndexOf(suffix) === str.length - suffix.length;
    },
    $match: function (s, pattern, flags) {
      var m = String(s === undefined || s === null ? '' : s).match(new RegExp(pattern, flags || ''));
      return m ? (m.length > 1 ? m.slice(1) : m[0]) : null;
    },
    $slug: function (s) {
      return String(s === undefined || s === null ? '' : s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    },
    $truncate: function (s, n) {
      var str = String(s === undefined || s === null ? '' : s);
      return str.length > n ? str.substring(0, n) + '…' : str;
    },
    $json: function (v) { return safeStringify(v); },   // note: shadowed by $json data below when data exists
    $parseJson: function (s) { try { return JSON.parse(s); } catch (e) { return null; } },

    // ---- numbers -----------------------------------------------------------
    $round: function (n, digits) {
      var d = digits === undefined ? 0 : digits;
      var f = Math.pow(10, d);
      return Math.round((helpers.$number(n) || 0) * f) / f;
    },
    $floor: function (n) { return Math.floor(helpers.$number(n) || 0); },
    $ceil: function (n) { return Math.ceil(helpers.$number(n) || 0); },
    $abs: function (n) { return Math.abs(helpers.$number(n) || 0); },
    $min: function () { return Math.min.apply(null, Array.prototype.slice.call(arguments).map(function (v) { return helpers.$number(v); })); },
    $max: function () { return Math.max.apply(null, Array.prototype.slice.call(arguments).map(function (v) { return helpers.$number(v); })); },
    $random: function (min, max) {
      if (min === undefined) return Math.random();
      var lo = helpers.$number(min), hi = helpers.$number(max);
      if (hi === undefined || hi === null) { hi = lo; lo = 0; }
      return Math.floor(Math.random() * (hi - lo + 1)) + lo;
    },

    // ---- dates -------------------------------------------------------------
    $dateAdd: function (dateLike, amount, unit) {
      var d = dateLike instanceof Date ? new Date(dateLike.getTime()) : new Date(dateLike);
      var n = helpers.$number(amount) || 0;
      switch (String(unit || 'days').toLowerCase()) {
        case 'ms': case 'millisecond': case 'milliseconds': d.setMilliseconds(d.getMilliseconds() + n); break;
        case 's': case 'second': case 'seconds': d.setSeconds(d.getSeconds() + n); break;
        case 'm': case 'minute': case 'minutes': d.setMinutes(d.getMinutes() + n); break;
        case 'h': case 'hour': case 'hours': d.setHours(d.getHours() + n); break;
        case 'w': case 'week': case 'weeks': d.setDate(d.getDate() + n * 7); break;
        case 'mo': case 'month': case 'months': d.setMonth(d.getMonth() + n); break;
        case 'y': case 'year': case 'years': d.setFullYear(d.getFullYear() + n); break;
        default: d.setDate(d.getDate() + n);
      }
      return d;
    },
    $dateDiff: function (a, b, unit) {
      var ms = new Date(b).getTime() - new Date(a).getTime();
      var map = { ms: 1, s: 1000, m: 60000, h: 3600000, d: 86400000, w: 604800000 };
      return Math.floor(ms / (map[String(unit || 'd').toLowerCase()] || 86400000));
    },
    $formatDate: function (dateLike, pattern) {
      var d = dateLike instanceof Date ? dateLike : new Date(dateLike);
      if (isNaN(d.getTime())) return '';
      var p = pattern || 'YYYY-MM-DD';
      var map = {
        YYYY: String(d.getFullYear()), MM: pad(d.getMonth() + 1), DD: pad(d.getDate()),
        HH: pad(d.getHours()), mm: pad(d.getMinutes()), ss: pad(d.getSeconds()),
        MMM: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][d.getMonth()]
      };
      return p.replace(/YYYY|MMM|MM|DD|HH|mm|ss/g, function (k) { return map[k]; });
    }
  };

  globalThis.__kaivoDefineScope = function (scopeJson) {
    var scope = scopeJson ? JSON.parse(scopeJson) : {};

    // Data variables are defined first so helpers such as $json() (stringify) stay reachable
    // under their own name: users overwhelmingly mean the data one, so it wins.
    globalThis.$json = scope.json === undefined ? {} : scope.json;
    globalThis.$items = scope.items || [];
    globalThis.$item = scope.item || globalThis.$json;
    globalThis.$index = scope.index === undefined ? 0 : scope.index;
    globalThis.$vars = scope.vars || {};
    globalThis.$run = scope.run || {};
    globalThis.$now = scope.now;
    globalThis.$today = scope.today;

    var stepMap = scope.step || {};
    var stepFn = function (name, field) {
      if (name === undefined) return stepMap;
      var entries = stepMap[name];
      if (entries === undefined) return null;
      var value = entries;
      if (!Array.isArray(value)) value = [value];
      var out = value.length === 1 ? value[0] : value;
      if (field) {
        return Array.isArray(out)
          ? out.map(function (o) { return helpers.$map([o], field)[0]; })
          : out[field];
      }
      return out;
    };
    // n8n-style $node["Name"].json also works, KaivoFlow style is $step("Name").
    globalThis.$step = stepFn;
    globalThis.$node = new Proxy({}, {
      get: function (_t, name) {
        var v = stepFn(String(name));
        return { json: v };
      }
    });

    Object.keys(helpers).forEach(function (key) {
      // Keep the data binding where it collides with a helper of the same name.
      if (key === '$json' || key === '$item') return;
      globalThis[key] = helpers[key];
    });

    // Convenience date wrappers available as methods on the string bindings.
    globalThis.__kaivoNow = function () { return new Date(scope.now); };
  };

  /**
   * A missing nested field is the single most common expression mistake
   * (`{{ $json.transaction.amount }}` on an item that has no transaction).
   * KaivoFlow treats that as "no value" — the Run continues and the Step log gets a hint —
   * instead of failing an entire automation because one item lacked a key.
   * Genuine errors (typos, calling a non-function, syntax) still fail loudly.
   */
  function isMissingPathError(e) {
    if (!e || !(e instanceof TypeError)) return false;
    var msg = String(e.message || '');
    return /Cannot read propert(y|ies) of (undefined|null)/.test(msg) ||
           /Cannot destructure/.test(msg) ||
           /of undefined|null\)?$/.test(msg) && /undefined/.test(msg);
  }

  globalThis.__kaivoEval = function (code) {
    try {
      var raw = (0, eval)(code);              // indirect eval: global scope, no host leakage
      if (raw instanceof Date) raw = raw.toISOString();
      var json = safeStringify(raw);
      return JSON.stringify({ ok: true, value: json === undefined ? null : json });
    } catch (e) {
      if (isMissingPathError(e)) {
        return JSON.stringify({
          ok: true,
          value: null,
          warning: 'A field in this expression does not exist on the current item: ' +
                   String(e && e.message ? e.message : e)
        });
      }
      return JSON.stringify({
        ok: false,
        error: (e && e.message) ? String(e.message) : String(e),
        stack: (e && e.stack) ? String(e.stack) : ''
      });
    }
  };
})();
