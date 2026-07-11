"""Formats /search JSON for the demo. Reads from stdin."""
import json, sys

r = json.load(sys.stdin)
u = r["user"]
name = u["display_name"]
groups = u["group_names"]
print(f"  {name}  groups={groups}")
chunks = r["chunks"]
if not chunks:
    print("  (no results - permissions filter left nothing visible)")
else:
    for c in chunks:
        preview = c["content"][:70].replace("\n", " ")
        doc = c["document_id"]
        idx = c["chunk_index"]
        ag = c["allowed_groups"]
        sc = c["score"]
        print(f"  [{sc:.4f}] {doc}#{idx}  groups={ag}  {preview!r}")
