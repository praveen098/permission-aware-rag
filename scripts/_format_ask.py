"""Formats /ask JSON for the demo. Reads from stdin."""
import json
import sys
import textwrap

r = json.load(sys.stdin)
u = r["user"]
name = u["display_name"]
groups = u["group_names"]
print(f"  {name}  groups={groups}")
if r["refused"]:
    print(f"  REFUSED: {r['answer']}")
else:
    wrapped = textwrap.fill(r["answer"], width=90, initial_indent="  ", subsequent_indent="  ")
    print(wrapped)
    cites = r.get("citations") or []
    if cites:
        srcs = ", ".join(f"{c['document_id']}#{c['chunk_index']}" for c in cites)
        print(f"  cited: {srcs}")
