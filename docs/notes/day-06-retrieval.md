# Day 6 — The Query Side: Where Permissions Finally Get USED

Everything from Days 1-5 was setup. Today the `allowed_groups` array you've
been carefully maintaining does its job: it filters retrieval **before**
ranking, so a user's search never even considers chunks they can't see.

## What got built

- **`query-service/app.py`** - a small FastAPI service with one endpoint,
  `POST /search`, that:
  1. Resolves the user's group ids from `users` + `user_groups`.
  2. Embeds the query via the embedding service.
  3. Runs a SINGLE SQL statement with two ranked CTEs (vector + keyword),
     both permission-pre-filtered.
  4. Merges the two ranked lists with Reciprocal Rank Fusion.
  5. Returns the top-K chunks with citations.

- **`scripts/search_demo.sh`** - exercises 5 scenarios that map to the
  project's whole pitch. Dev asks about salaries → nothing. Meera asks the
  same → the comp doc. That's the leak test made real.

## D1. The one SQL statement, dissected

Look at `hybrid_retrieve`. It's the interview centerpiece of the project.
Structure:

```sql
WITH
  vec AS (SELECT ... FROM chunks
          WHERE allowed_groups && :groups   -- PERMISSION PRE-FILTER
          ORDER BY embedding <=> :qvec::vector
          LIMIT 30),
  kw  AS (SELECT ... FROM chunks
          WHERE allowed_groups && :groups   -- SAME FILTER, second branch
            AND tsv @@ plainto_tsquery('english', :qtext)
          ORDER BY ts_rank(tsv, ...) DESC
          LIMIT 30)
SELECT ... FROM vec FULL OUTER JOIN kw USING (id);
```

Three things to own here:

**(a) The `&&` operator is `array_overlap`.** It's true if the two int arrays
share at least one element. `chunks.allowed_groups && '{5}'` = "chunks whose
allowed_groups contains 5". Backed by the GIN index we created on Day 1.

**(b) The filter is in the WHERE clause, not applied to results afterwards.**
This is the pre-filter guarantee, in code. The vector distance and ts_rank
scoring only run over the pre-filtered rows. Restricted chunks aren't scored
and hidden - they're never considered.

**(c) Both branches carry the same filter.** If one branch had the filter and
the other didn't, one leak path would be open. The invariant to preserve
forever: any new retrieval branch must include the permission filter.

## D2. Reciprocal Rank Fusion, in one line

`score(d) = Σ 1 / (k + rank_i(d))` for each branch `i` that returned `d`.
`k` is a damping constant (60 is the standard from the original TREC paper).

Why RRF and not "just average the scores"? Because vector similarity (cosine,
[-1, 1]) and keyword score (ts_rank, arbitrary positive) live on different
scales. Averaging would let one dominate arbitrarily. RRF only looks at
*rank* (1st, 2nd, ...), so scale doesn't matter. Documents that appear near
the top of BOTH lists win.

**Interview line:** "Hybrid retrieval, merged with RRF - rank-based so I
don't have to normalize cosine similarity against ts_rank across corpora."

## D3. Why one SQL statement, not two round-trips

I could have done two queries (vector, then keyword) from Python and merged
their results client-side. The single-statement version:
- **One database round-trip** instead of two.
- **One consistent snapshot** - the two lists are computed against the same
  point-in-time state. Two round-trips can straddle an ACL change.
- **Both filters go through the same query planner** - if the planner picks
  the GIN index on `allowed_groups` for one, it will for the other.

The `FULL OUTER JOIN ... USING (id)` unifies the two ranked lists by chunk id,
carrying `vec.rnk` and `kw.rnk` per row so RRF has both ranks to sum.

## D4. What "search_demo.sh" proves

Same query, two users, two different answers. That IS the whole product:

```
=== LEAK TEST: intern asks about salaries ===
user:  dev@corp.io       groups=[all-staff]
  (no results — permissions filter left nothing visible)

=== SAME QUERY, but as CFO ===
user:  meera@corp.io     groups=[finance, leadership, all-staff]
  [0.0317] CONF-2002#0  groups=[2,4]  'Compensation Bands (CONFIDENTIAL)...'
```

Read those two blocks side by side. That's Glean's core value proposition,
executing on your laptop. If someone asks in an interview "what's the coolest
thing you built recently," this is the demo you show.

## Defense questions

1. Walk the exact SQL execution for Dev searching "salary bands". Why does
   the vector CTE return zero rows for him? Which index gets used?
2. Someone proposes: "let's simplify - drop the vector branch, keyword search
   is enough." What quality problem does that reintroduce? Give a concrete
   query where keyword-only would fail but hybrid succeeds.
3. Why RRF instead of "add the two scores together"? Give a scenario where
   raw score addition produces a bad ranking that RRF gets right.
4. A junior engineer proposes moving the permission filter out of SQL and
   applying it in Python after retrieving top-30 candidates. Give TWO reasons
   this is wrong - one correctness, one information-leak.

Q4 is the crown-jewel interview question and you've now literally *built* the
answer. Own it.

## Your tasks

1. **Install deps and start the query service:**
   ```bash
   cd query-service
   python3.10 -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt
   uvicorn app:app --port 8100
   ```
   Confirm health: `curl -s localhost:8100/health` -> `{"status":"ok"}`.

2. **Make sure the docs are indexed** (from Days 3-4). Quick check:
   ```bash
   docker exec parag-postgres psql -U parag -c \
     "SELECT count(*) FROM chunks;"
   ```
   Should be > 0. If 0, run the corpus + let the indexer catch up first.

3. **Reset CONF-3001 to full ACLs** so the demo is meaningful (yesterday you
   revoked leadership from it):
   ```bash
   cd scripts && source .venv/bin/activate
   python emit_event.py acl-grant   # restores finance + all-staff... actually
   ```
   Or just re-publish the corpus with `python corpus_generator.py` - the
   version gate skips already-indexed docs; CONF-3001 currently at v1 will be
   skipped but its ACL isn't rewritten by DOC_CREATED unless the doc_version
   advances. Simplest: just accept the current state and observe.

4. **Run the leak-test demo:**
   ```bash
   ./scripts/search_demo.sh
   ```
   You should see Dev get zero results for the salary query, and Meera get
   CONF-2002. That's the whole project working.

5. **Do it yourself with curl** - so the muscle memory is real:
   ```bash
   curl -s -X POST localhost:8100/search \
     -H 'Content-Type: application/json' \
     -d '{"user_email":"dev@corp.io","query":"quarterly revenue forecast"}' \
     | python3 -m json.tool
   ```
   Then swap `dev@corp.io` for `meera@corp.io` and watch the results change.

6. Commit:
   ```
   Day 6: permission-aware hybrid retrieval

   - query-service: POST /search resolves user groups, embeds query, runs a
     single SQL with vector CTE + keyword CTE, both pre-filtered by
     allowed_groups && user_groups; merged with Reciprocal Rank Fusion
   - scripts/search_demo.sh exercises leak-test scenarios (intern vs CFO)
   - notes in docs/notes/day-06-retrieval.md
   ```

Day 7 layers Claude on top: the query service passes the retrieved chunks to
an LLM which generates a grounded answer with citations, or refuses cleanly
when no permitted chunks match. Day 8 turns `search_demo.sh` into an automated
leak-test suite - the executable proof that the permission story holds.
