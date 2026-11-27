-- Don Yarmy Engineering: refuse a packing list that breaks the rules.
--
-- A warehouse client stores each van's packing list as JSON. Every item
-- needs a code like BOLT-M12-050, a whole number of 1 or more, and a weight,
-- and the load must stay under the van's limit. A CHECK constraint can't
-- read a JSON list like that. A trigger that calls a JavaScript function
-- can, and its error message says exactly what is wrong.
--
-- Run it in Terminal:  sqlite3 demo.db < packing-lists.sql

.load sqlite-js

-- The sqlite3 command doesn't let a trigger stored in a database call an
-- extension's functions, because opening a database you got from someone
-- else would run its triggers. This database is your own, so allow it.
-- Every later session that writes to the shipments table needs this line,
-- .load sqlite-js and SELECT js_init_table(1); see the end of this file.
PRAGMA trusted_schema = ON;

.output /dev/null

-- Keep the functions in the database's js_functions table, so a later
-- session gets them back with SELECT js_init_table(1).
SELECT js_init_table();

-- check_packing_list(items, limit_kg) returns 1 for a good list. For a bad
-- one it throws an error, which stops the INSERT or UPDATE.
SELECT js_create_scalar('check_packing_list', '(function (args) {
  const [json, limitKg] = args;
  let items;
  try {
    items = JSON.parse(json);
  } catch (e) {
    throw new Error("The packing list is not valid JSON.");
  }
  if (!Array.isArray(items) || items.length === 0) {
    throw new Error("The packing list has no items.");
  }
  let total = 0;
  items.forEach((item, n) => {
    const where = `Item ${n + 1}`;
    if (!/^[A-Z]{3,5}-[A-Z0-9]{2,4}-[0-9]{3}$/.test(item.code)) {
      throw new Error(`${where}: "${item.code}" is not a code like BOLT-M12-050.`);
    }
    if (!Number.isInteger(item.qty) || item.qty < 1) {
      throw new Error(`${where}: ${item.qty} is not a whole number of 1 or more.`);
    }
    if (!(item.kg > 0)) {
      throw new Error(`${where}: the weight must be more than 0 kg.`);
    }
    total += item.qty * item.kg;
  });
  if (total > limitKg) {
    throw new Error(`The load is ${total.toFixed(1)} kg, over the limit of ${limitKg} kg.`);
  }
  return 1;
})');

.output stdout

DROP TABLE IF EXISTS shipments;
CREATE TABLE shipments (
  id INTEGER PRIMARY KEY,
  van TEXT NOT NULL,
  limit_kg REAL NOT NULL,
  items TEXT NOT NULL
);

CREATE TRIGGER shipments_check_insert BEFORE INSERT ON shipments
BEGIN
  SELECT check_packing_list(NEW.items, NEW.limit_kg);
END;

CREATE TRIGGER shipments_check_update
BEFORE UPDATE OF items, limit_kg ON shipments
BEGIN
  SELECT check_packing_list(NEW.items, NEW.limit_kg);
END;

-- A good list: 200 bolts and 4 brackets, 13.0 kg.
INSERT INTO shipments (van, limit_kg, items) VALUES ('Van 1', 900,
  '[{"code": "BOLT-M12-050", "qty": 200, "kg": 0.045},
    {"code": "BRKT-L90-200", "qty": 4, "kg": 1.0}]');

-- A code in the wrong form.
INSERT INTO shipments (van, limit_kg, items) VALUES ('Van 2', 900,
  '[{"code": "bolt m12", "qty": 10, "kg": 0.045}]');

-- Half a bracket.
INSERT INTO shipments (van, limit_kg, items) VALUES ('Van 2', 900,
  '[{"code": "BOLT-M12-050", "qty": 10, "kg": 0.045},
    {"code": "BRKT-L90-200", "qty": 1.5, "kg": 1.0}]');

-- 40 steel beams for a small van.
INSERT INTO shipments (van, limit_kg, items) VALUES ('Van 3', 900,
  '[{"code": "BEAM-I20-600", "qty": 40, "kg": 26.5}]');

-- Changing a stored list is checked too.
UPDATE shipments SET items = '[]' WHERE van = 'Van 1';

-- Only the good list was stored.
SELECT id, van, json_array_length(items) AS items FROM shipments;

-- In a later session, load the stored function before writing:
--   .load sqlite-js
--   PRAGMA trusted_schema = ON;
--   SELECT js_init_table(1);
-- Without them, every INSERT into shipments fails with
-- "no such function: check_packing_list", so a program without sqlite-js
-- can't slip in a bad list.
