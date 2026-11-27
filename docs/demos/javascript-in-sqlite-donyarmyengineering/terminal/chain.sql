-- Don Yarmy Engineering: how much chain to buy for a footbridge.
--
-- A client is building a footbridge whose deck hangs from two chains. A
-- chain hangs in a curve called a catenary, y = a * cosh(x / a). The chain's
-- length is 2 * a * sinh(span / (2 * a)), but first a has to be found from
-- the span and the sag, the drop at the middle:
--   sag = a * (cosh(span / (2 * a)) - 1)
-- That equation has no formula for a, so the function below solves it with
-- Newton's method: guess, correct the guess, and repeat until it stops
-- changing. SQLite has cosh() and sinh(), but in SQL that loop takes a
-- recursive query that is hard to read. In JavaScript it's a plain loop.
--
-- Run it in Terminal:  sqlite3 demo.db < chain.sql
-- To price your own bridge, change the rows in the INSERT and the price.

.load sqlite-js
.headers on
.mode column

.output /dev/null

-- chain_length(span, sag): the length of chain, in the units of its
-- arguments, that hangs across span and drops sag at the middle.
SELECT js_create_scalar('chain_length', '(function (args) {
  const [span, sag] = args;
  if (!(span > 0 && sag > 0)) throw new Error("span and sag must be more than 0");
  const half = span / 2;
  // A parabola is close to a catenary, so its a is a good first guess.
  let a = span * span / (8 * sag);
  for (let i = 0; i < 1000; i++) {
    const x = half / a;
    // cosh(x) - 1, written so that it stays exact when x is small
    const coshm1 = 2 * Math.sinh(x / 2) ** 2;
    const error = a * coshm1 - sag;
    const slope = coshm1 - x * Math.sinh(x);
    const next = a - error / slope;
    if (!Number.isFinite(next)) break;
    if (Math.abs(next - a) < 1e-12 * a) {
      const length = 2 * next * Math.sinh(half / next);
      // A chain is never longer than down to the sag and back up.
      if (length < span + 2 * sag) return length;
      break;
    }
    a = next;
  }
  throw new Error("no answer found for this span and sag");
})');

.output stdout

DROP TABLE IF EXISTS bridge_options;
CREATE TABLE bridge_options (span_m REAL, sag_m REAL);
-- One 40 m span, three possible sags. More sag needs more chain but pulls
-- less on the anchors.
INSERT INTO bridge_options VALUES (40, 2), (40, 3), (40, 4);

-- Two chains, 5% spare for the fittings, sold in whole metres at 38.50 a
-- metre.
SELECT sag_m,
       round(chain_length(span_m, sag_m), 3) AS one_chain_m,
       CAST(ceil(2 * chain_length(span_m, sag_m) * 1.05) AS INTEGER) AS buy_m,
       printf('%.2f', ceil(2 * chain_length(span_m, sag_m) * 1.05) * 38.50)
         AS cost
FROM bridge_options;
