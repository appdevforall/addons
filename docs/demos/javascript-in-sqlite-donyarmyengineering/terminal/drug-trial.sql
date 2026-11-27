-- Don Yarmy Engineering: does the drug work, or was it chance?
--
-- A clinical-research client gave 20 patients a new blood-pressure drug and
-- 20 a placebo, and measured how far each patient's systolic pressure fell
-- after 8 weeks, in mmHg. (The numbers are made up.) The drug group fell
-- further on average, but small groups vary a lot, so the question is
-- whether a difference this large could easily happen by chance.
--
-- SQLite can average each group, and has no test for that question. Three
-- JavaScript functions answer it two ways:
-- - welch_test, an aggregate, computes Welch's t-test: how many standard
--   errors apart the two averages are (t), and the test's degrees of
--   freedom (df).
-- - t_p_value turns t and df into a p-value: the chance of a difference at
--   least this large if the drug did nothing.
-- - permutation_p checks the same thing by brute force. It shuffles the
--   drug and placebo labels 10,000 times and counts how often the shuffled
--   groups differ as much as the real ones. A fixed seed makes the shuffles
--   the same on every run.
--
-- Run it in Terminal:  sqlite3 demo.db < drug-trial.sql
-- To test your own groups, replace the INSERT. The group names must be
-- 'drug' and 'placebo'.

.load sqlite-js
.headers on
.mode column

.output /dev/null

-- welch_test(group, value) returns JSON: {"difference", "t", "df"}.
SELECT js_create_aggregate('welch_test',
  'const groups = { drug: [], placebo: [] };',
  '(function (args) {
    const [group, value] = args;
    if (!(group in groups)) throw new Error(`unknown group ${group}`);
    groups[group].push(value);
  })',
  '(function () {
    const describe = (xs) => {
      const n = xs.length;
      const mean = xs.reduce((sum, x) => sum + x, 0) / n;
      const variance = xs.reduce((sum, x) => sum + (x - mean) ** 2, 0) / (n - 1);
      return { n, mean, se2: variance / n };
    };
    if (groups.drug.length < 2 || groups.placebo.length < 2) {
      throw new Error("each group needs at least 2 patients");
    }
    const d = describe(groups.drug);
    const p = describe(groups.placebo);
    const se2 = d.se2 + p.se2;
    return JSON.stringify({
      difference: d.mean - p.mean,
      t: (d.mean - p.mean) / Math.sqrt(se2),
      df: se2 ** 2 / (d.se2 ** 2 / (d.n - 1) + p.se2 ** 2 / (p.n - 1)),
    });
  })');

-- t_p_value(t, df): the two-sided p-value of Student's t distribution,
-- from the regularized incomplete beta function, as in Numerical Recipes.
SELECT js_create_scalar('t_p_value', '(function (args) {
  const [t, df] = args;
  const logGamma = (z) => {
    const c = [76.18009172947146, -86.50532032941677, 24.01409824083091,
      -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5];
    let y = z;
    let series = 1.000000000190015;
    for (const k of c) series += k / ++y;
    const tmp = z + 5.5 - (z + 0.5) * Math.log(z + 5.5);
    return -tmp + Math.log(2.5066282746310005 * series / z);
  };
  const fraction = (a, b, x) => {
    const tiny = 1e-300;
    const fix = (v) => (Math.abs(v) < tiny ? tiny : v);
    let c = 1;
    let d = 1 / fix(1 - (a + b) * x / (a + 1));
    let h = d;
    for (let m = 1; m <= 300; m++) {
      const m2 = 2 * m;
      let aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
      d = 1 / fix(1 + aa * d);
      c = fix(1 + aa / c);
      h *= d * c;
      aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
      d = 1 / fix(1 + aa * d);
      c = fix(1 + aa / c);
      h *= d * c;
      if (Math.abs(d * c - 1) < 1e-15) break;
    }
    return h;
  };
  const a = df / 2;
  const b = 0.5;
  const x = df / (df + t * t);
  if (x >= 1) return 1;
  const front = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b)
    + a * Math.log(x) + b * Math.log(1 - x));
  return x < (a + 1) / (a + b + 2)
    ? front * fraction(a, b, x) / a
    : 1 - front * fraction(b, a, 1 - x) / b;
})');

-- permutation_p(group, value, shuffles, seed): the share of shuffles whose
-- difference is at least as large as the real one.
SELECT js_create_aggregate('permutation_p',
  'const values = []; const isDrug = []; let shuffles = 0; let seed = 0;',
  '(function (args) {
    isDrug.push(args[0] === "drug");
    values.push(args[1]);
    shuffles = args[2];
    seed = args[3];
  })',
  '(function () {
    // mulberry32: a small random number generator that repeats its
    // numbers for the same seed, which Math.random() does not.
    let s = seed >>> 0;
    const random = () => {
      s = (s + 0x6d2b79f5) >>> 0;
      let r = Math.imul(s ^ (s >>> 15), 1 | s);
      r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
      return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
    };
    const difference = (labels) => {
      let drug = 0, nDrug = 0, placebo = 0, nPlacebo = 0;
      labels.forEach((d, i) => {
        if (d) { drug += values[i]; nDrug++; } else { placebo += values[i]; nPlacebo++; }
      });
      return drug / nDrug - placebo / nPlacebo;
    };
    if (!isDrug.includes(true) || !isDrug.includes(false)) {
      throw new Error("both groups need patients");
    }
    const observed = Math.abs(difference(isDrug));
    const labels = isDrug.slice();
    let asLarge = 0;
    for (let k = 0; k < shuffles; k++) {
      for (let i = labels.length - 1; i > 0; i--) {
        const j = Math.floor(random() * (i + 1));
        [labels[i], labels[j]] = [labels[j], labels[i]];
      }
      if (Math.abs(difference(labels)) >= observed - 1e-9) asLarge++;
    }
    return (asLarge + 1) / (shuffles + 1);
  })');

.output stdout

DROP TABLE IF EXISTS trial;
CREATE TABLE trial (
  patient INTEGER PRIMARY KEY,
  grp TEXT NOT NULL CHECK (grp IN ('drug', 'placebo')),
  drop_mmhg REAL NOT NULL
);
INSERT INTO trial (grp, drop_mmhg) VALUES
  ('drug', 5.5), ('drug', 14.8), ('drug', 13.7), ('drug', 17.4),
  ('drug', 0.0), ('drug', 5.4), ('drug', 11.8), ('drug', 16.0),
  ('drug', 10.7), ('drug', 28.3), ('drug', 14.7), ('drug', 0.3),
  ('drug', 6.4), ('drug', 10.9), ('drug', 0.4), ('drug', 19.2),
  ('drug', 2.6), ('drug', 5.2), ('drug', -1.0), ('drug', 9.9),
  ('placebo', 1.5), ('placebo', 9.5), ('placebo', 7.6), ('placebo', 15.8),
  ('placebo', 7.8), ('placebo', -14.7), ('placebo', 6.0), ('placebo', -1.8),
  ('placebo', 17.3), ('placebo', 9.6), ('placebo', -2.1), ('placebo', -10.9),
  ('placebo', -3.8), ('placebo', 8.8), ('placebo', 5.8), ('placebo', -0.3),
  ('placebo', 7.4), ('placebo', 6.3), ('placebo', -0.5), ('placebo', 5.8);

-- The two groups.
SELECT grp, count(*) AS patients, round(avg(drop_mmhg), 2) AS mean_drop
FROM trial GROUP BY grp;

-- Welch's t-test.
WITH test AS (SELECT welch_test(grp, drop_mmhg) AS r FROM trial)
SELECT round(json_extract(r, '$.difference'), 2) AS difference,
       round(json_extract(r, '$.t'), 3) AS t,
       round(json_extract(r, '$.df'), 1) AS df,
       round(t_p_value(json_extract(r, '$.t'), json_extract(r, '$.df')), 4)
         AS p
FROM test;

-- The same question by shuffling.
SELECT round(permutation_p(grp, drop_mmhg, 10000, 7329), 4) AS shuffled_p
FROM trial;
