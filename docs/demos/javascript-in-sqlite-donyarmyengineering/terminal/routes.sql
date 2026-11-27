-- Don Yarmy Engineering: how much fuel does the great circle save?
--
-- A charter airline client wants fuel estimates for its routes. The
-- shortest way between two airports is the great circle, which bends
-- toward the pole on a flat map. The line that looks straight on the usual
-- (Mercator) map is a rhumb line: one compass heading all the way, and a
-- longer trip. Three JavaScript functions compare them:
-- - gc_km: the great-circle distance (the haversine formula).
-- - rhumb_km: the distance along a constant heading.
-- - gc_point: a point part of the way along the great circle, for drawing
--   the route on a map.
-- Both distances treat the Earth as a sphere, which is within about 0.5% of
-- its real shape. SQLite has sin(), cos() and atan2(), so one formula could
-- be written in SQL. As named functions, they read like the formulas and
-- every query can reuse them.
--
-- Run it in Terminal:  sqlite3 demo.db < routes.sql
-- To use your own routes, change the airports, the routes and the burn
-- rate. The fuel figures are estimates for comparison, not flight plans.

.load sqlite-js
.headers on
.mode column

.output /dev/null

SELECT js_create_scalar('gc_km', '(function (args) {
  const [lat1, lon1, lat2, lon2] = args.map((d) => d * Math.PI / 180);
  const h = Math.sin((lat2 - lat1) / 2) ** 2
    + Math.cos(lat1) * Math.cos(lat2) * Math.sin((lon2 - lon1) / 2) ** 2;
  return 2 * 6371.0088 * Math.asin(Math.sqrt(h));
})');

SELECT js_create_scalar('rhumb_km', '(function (args) {
  const [lat1, lon1, lat2, lon2] = args.map((d) => d * Math.PI / 180);
  const dLat = lat2 - lat1;
  // The short way around: across the date line when that is shorter.
  let dLon = lon2 - lon1;
  if (Math.abs(dLon) > Math.PI) dLon -= Math.sign(dLon) * 2 * Math.PI;
  const stretch = Math.log(Math.tan(Math.PI / 4 + lat2 / 2)
    / Math.tan(Math.PI / 4 + lat1 / 2));
  const q = Math.abs(stretch) > 1e-12 ? dLat / stretch : Math.cos(lat1);
  return 6371.0088 * Math.sqrt(dLat * dLat + q * q * dLon * dLon);
})');

-- gc_point(lat1, lon1, lat2, lon2, f) returns 'lat, lon' at fraction f of
-- the way from the first point to the second.
SELECT js_create_scalar('gc_point', '(function (args) {
  const f = args[4];
  const [lat1, lon1, lat2, lon2] = args.slice(0, 4).map((d) => d * Math.PI / 180);
  const toXyz = (lat, lon) =>
    [Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)];
  const p = toXyz(lat1, lon1);
  const q = toXyz(lat2, lon2);
  const angle = Math.acos(Math.min(1, p[0] * q[0] + p[1] * q[1] + p[2] * q[2]));
  const a = Math.sin((1 - f) * angle) / Math.sin(angle);
  const b = Math.sin(f * angle) / Math.sin(angle);
  const [x, y, z] = [0, 1, 2].map((i) => a * p[i] + b * q[i]);
  const lat = Math.atan2(z, Math.hypot(x, y)) * 180 / Math.PI;
  const lon = Math.atan2(y, x) * 180 / Math.PI;
  return `${lat.toFixed(1)}, ${lon.toFixed(1)}`;
})');

.output stdout

DROP TABLE IF EXISTS airports;
CREATE TABLE airports (code TEXT PRIMARY KEY, city TEXT, lat REAL, lon REAL);
-- Coordinates from OurAirports (ourairports.com), in degrees.
INSERT INTO airports VALUES
  ('SFO', 'San Francisco', 37.6198, -122.3748),
  ('JFK', 'New York', 40.6394, -73.7793),
  ('LHR', 'London', 51.4707, -0.4599),
  ('NRT', 'Tokyo', 35.7686, 140.3887),
  ('SYD', 'Sydney', -33.9461, 151.1770),
  ('GRU', 'São Paulo', -23.4313, -46.4700),
  ('JNB', 'Johannesburg', -26.1401, 28.2468);

DROP TABLE IF EXISTS routes;
CREATE TABLE routes (origin TEXT, destination TEXT);
INSERT INTO routes VALUES
  ('SFO', 'JFK'), ('JFK', 'LHR'), ('SFO', 'NRT'),
  ('LHR', 'NRT'), ('SYD', 'SFO'), ('GRU', 'JNB');

-- The client's jet burns about 1.8 kg of fuel per km (a made-up figure).
DROP VIEW IF EXISTS legs;
CREATE TEMP VIEW legs AS
SELECT r.origin || '-' || r.destination AS route,
       gc_km(a.lat, a.lon, b.lat, b.lon) AS gc,
       rhumb_km(a.lat, a.lon, b.lat, b.lon) AS rhumb
FROM routes AS r
JOIN airports AS a ON a.code = r.origin
JOIN airports AS b ON b.code = r.destination;

SELECT route,
       CAST(round(gc) AS INTEGER) AS great_circle_km,
       CAST(round(rhumb) AS INTEGER) AS rhumb_km,
       CAST(round((rhumb - gc) * 1.8) AS INTEGER) AS fuel_saved_kg
FROM legs ORDER BY rhumb - gc DESC;

-- Where the great circle from San Francisco to Tokyo goes: north, toward
-- the Aleutian Islands, though both cities are near 36 degrees north.
WITH RECURSIVE steps(f) AS (SELECT 0.0 UNION ALL SELECT f + 0.25 FROM steps WHERE f < 1)
SELECT CAST(f * 100 AS INTEGER) || '%' AS part_of_trip,
       gc_point(a.lat, a.lon, b.lat, b.lon, f) AS lat_lon
FROM steps, airports AS a, airports AS b
WHERE a.code = 'SFO' AND b.code = 'NRT';
