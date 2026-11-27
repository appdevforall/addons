-- Don Yarmy Engineering: find customers who were typed in twice.
--
-- A client's customer list was typed in by hand, so one person can appear
-- as "José Núñez", "Nunez, Jose" and "JOSE  NUNEZ". SQLite's lower() changes
-- only A to Z, and SQLite has no way to remove accents or to say how alike
-- two names are. Two JavaScript functions do both.
--
-- Run it in Terminal:  sqlite3 demo.db < duplicates.sql
-- To use your own list, replace the INSERT with your names.

.load sqlite-js
.headers on

-- Creating a function prints 0. Hide that.
.output /dev/null

-- name_key('Núñez, José ') returns 'jose nunez': no accents, no
-- apostrophes, lower case, "Last, First" turned around, and the words
-- sorted, so their order doesn't matter. Letters of every alphabet are kept,
-- with the vowel signs of scripts such as Hindi's, so names in those scripts
-- don't all become the same key.
SELECT js_create_scalar('name_key', '(function (args) {
  // NFKD splits an accented letter into the letter and its accent, and turns
  // ligatures and full-width letters into plain ones. Only the accents of
  // Latin, Greek and Cyrillic letters (U+0300 to U+036F) are removed.
  let s = String(args[0]).normalize("NFKD").replace(/[\u0300-\u036f]/g, "");
  // Letters with no accent to remove, written the way they are usually typed.
  const plain = { "ø": "o", "ł": "l", "ß": "ss", "æ": "ae", "œ": "oe", "đ": "d" };
  s = s.toLowerCase().replace(/[øłßæœđ]/g, (c) => plain[c]);
  s = s.replace(/[''\u2019]/g, "");
  const comma = s.indexOf(",");
  if (comma >= 0) s = s.slice(comma + 1) + " " + s.slice(0, comma);
  return s.replace(/[^\p{L}\p{M}\p{N}]+/gu, " ").trim().split(" ").sort().join(" ");
})');

-- similarity(a, b) is 1 for the same text and less for each letter that
-- must be added, removed or changed to turn one into the other (the edit
-- distance), down to 0.
SELECT js_create_scalar('similarity', '(function (args) {
  const [a, b] = args;
  const row = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    let diagonal = row[0];
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const above = row[j];
      const change = a[i - 1] === b[j - 1] ? 0 : 1;
      row[j] = Math.min(above + 1, row[j - 1] + 1, diagonal + change);
      diagonal = above;
    }
  }
  return 1 - row[b.length] / Math.max(a.length, b.length, 1);
})');

.output stdout

DROP TABLE IF EXISTS customers;
CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT);
INSERT INTO customers (name) VALUES
  ('José Núñez'), ('Nunez, Jose'), ('JOSE  NUNEZ '),
  ('Ann Lee'), ('Anne Lee'), ('Lee, Ann'),
  ('Grace O''Brien'), ('Grace OBrien'),
  ('Bob Stone'), ('Robert Stone');

-- Each name and its key.
SELECT id, name, name_key(name) AS key FROM customers;

-- Pairs whose keys are at least 85% alike: probably the same person.
WITH k AS (SELECT id, name_key(name) AS key FROM customers)
SELECT a.id AS id, b.id AS same_as,
       round(similarity(a.key, b.key), 2) AS score
FROM k AS a JOIN k AS b ON a.id < b.id
WHERE a.key <> '' AND similarity(a.key, b.key) >= 0.85
ORDER BY a.id, b.id;
