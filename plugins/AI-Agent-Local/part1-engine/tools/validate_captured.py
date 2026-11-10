#!/usr/bin/env python3
"""validate_captured.py CAPTURE.jsonl: validate the last JSON line of every captured engine run, exactly as emitted, against the schema of its `format` (part1-rows-v1 from
schemas/ (a copy of descriptor-v02's), part1-pinprobe-v1, part1-staticprobe-v1, part1-gpuprobe-v1). The two documented exceptions of the rows schema are counted, not hidden: an exit 2 (or the exit 1 for an unreadable CPU list) raised before --tag was parsed
(no `tag`) and a helper-tag row. Exit 1 when any other line is invalid. usage: python3 -I -B tools/validate_captured.py CAPTURE.jsonl"""
import json, os, sys, collections
import jsonschema
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCH = {"part1-rows-v1": os.path.join(ROOT, "schemas", "part1-rows-v1.schema.json")}
for k in ("pinprobe", "staticprobe", "gpuprobe"):
    SCH["part1-%s-v1" % k] = os.path.join(ROOT, "part1-%s-v1.schema.json" % k)
V = {k: jsonschema.Draft202012Validator(json.load(open(v))) for k, v in SCH.items()}
cnt = collections.Counter(); bad = []
for ln in open(sys.argv[1]):
    run = json.loads(ln)
    lines = [x for x in run["stdout"].splitlines() if x.strip()]
    js = [x for x in lines if x.startswith("{")]
    if not js:
        cnt["no_json_line"] += 1; continue
    if lines[-1] != js[-1] or len(js) != 1:
        bad.append(("json line is not the single last stdout line", run["args"])); continue
    rec = json.loads(js[-1]); fmt = rec.get("format")
    if fmt not in V:
        bad.append(("unknown format %r" % fmt, run["args"])); continue
    errs = [e.message for e in V[fmt].iter_errors(rec)]
    if not errs:
        cnt["valid:" + fmt] += 1; continue
    if fmt == "part1-rows-v1" and "tag" not in rec and rec.get("exit_code") in (1, 2):   # exit 1: the unreadable CPU list, raised before the options (and --tag) are parsed (P1-03J)
        r2 = dict(rec, tag="C2-read-k0")
        if not list(V[fmt].iter_errors(r2)):
            cnt["documented:no_tag_usage_error"] += 1; continue
    if fmt == "part1-rows-v1" and "helper" in json.dumps(run["args"]).lower():
        cnt["documented:helper_tag_row"] += 1; continue
    bad.append((errs[:2], run["args"], run["rc"]))
for k, v in sorted(cnt.items()): print("%-40s %d" % (k, v))
print("invalid:", len(bad))
for b in bad: print(" ", json.dumps(b)[:400])
sys.exit(1 if bad else 0)
