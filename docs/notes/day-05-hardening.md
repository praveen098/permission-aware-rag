# Day 5 — Hardening: The Test That Proves The Point

Day 4 made permissions live. Day 5 *proves* they work correctly - and locks the
core promise in code so it can't silently regress. This is the day the project
stops being "runs on my machine" and starts being defensible.

## What got built

- **`AclRevocationIntegrationTest`** - the single most important test in the repo.
  Real Postgres (Testcontainers), real schema, exercises `ChunkStore` directly.
  Asserts an ACL change updates the group array **and** the embedding is
  byte-identical. If this test ever fails, the "no re-embedding on revocation"
  promise is broken.
- **`ChunkerTest`** - fast unit tests for chunking logic. No infra needed.
- **Producer delivery callback** in `corpus_generator.py` - closes the fire-and-
  forget bug that let "published" print for messages the broker never accepted.
- **`docs/runbook.md`** - one-page ops manual for the recurring issues we hit
  (port squatters, silent topic loss, password mismatch, etc). Turns hard-won
  lessons into a checklist.

## D1. Why an *integration* test for revocation, not a unit test

You could mock `ChunkStore` and assert its `updateAllowedGroups` was called with
the right array. That test would pass trivially - because it wouldn't touch a
real database. What it *wouldn't* prove is:

- The pgvector column really doesn't change when we `UPDATE allowed_groups`.
- The int-array binding round-trips correctly (GIN index unchanged, etc).
- The FK cascades don't cause chunks to accidentally get deleted.

The whole point of PARAG is a *database-level* claim about how permissions and
embeddings are decoupled. A mock can't verify that; only real SQL against real
pgvector can. So we spin up a fresh Postgres per test run via Testcontainers.

**Interview line:** "The revocation guarantee is a database contract, so I test
it with a real database. Testcontainers spins up pgvector/pg16 for the JUnit
run - same image as production - and the test reads the embedding before and
after the ACL change and asserts they're byte-identical."

## D2. Tests as documentation

`aclChangeUpdatesGroupsAndDoesNotTouchEmbedding` reads like a spec:
seed a chunk with `{finance, leadership}`, change to `{finance}`, assert the
embedding didn't move. A reviewer who reads only this test file understands
what the project claims and how it proves it. That's tests-as-documentation,
and it's the strongest signal in a portfolio repo - it says "I know what my
system is *for*, not just what it *does*."

## D3. Why we test idempotency explicitly

`aclChangeIsIdempotentUnderReplay` runs the same ACL change twice and asserts
the result matches. It looks trivial, but it's the executable version of the
Day 1 D4 argument: because we send full replacement sets (`allowed_groups =
["finance"]`), not deltas (`remove "leadership"`), replaying is naturally safe.
A "remove leadership" delta applied twice on a set that no longer contains
leadership does nothing bad here, but with more complex deltas it would - the
test locks in the design choice.

## D4. The fire-and-forget lesson, encoded

The producer callback in `corpus_generator.py` fixes a specific bug we hit
twice this week: the topic was missing, `produce()` succeeded (it only queues
locally), and the script printed "published N documents" while the broker
never saw them. The fix is a delivery callback that tracks *acknowledged*
deliveries separately from *queued* messages, and exits non-zero on failure.

**Real-world lesson worth carrying to a job:** any client library with an async
`produce`/`send` method has this trap. Success from the call means "handed off
to the client's internal buffer," not "the server accepted it." If you don't
register a callback, you're trusting an echo, not a receipt.

## Defense questions

1. Why is `AclRevocationIntegrationTest` an *integration* test rather than a
   unit test with a mocked `ChunkStore`? What specifically could a mock never
   prove?
2. In `aclChangeIsIdempotentUnderReplay`, we assert the state after running the
   ACL change twice equals the state after running it once. Why doesn't this
   test need to check the *embedding* stayed the same? (Hint: what would even
   change the embedding on an ACL path?)
3. The corpus generator now uses a delivery callback. Give a concrete scenario
   in which the *old* fire-and-forget code would have printed "Done. Published
   7 documents" while zero documents actually landed on the topic.
4. In the runbook, "Postgres password mismatch after volume reuse" is a real
   trap. Explain in one sentence why setting `POSTGRES_PASSWORD` in
   docker-compose is not enough to guarantee that password is the one Postgres
   accepts.

Q1 is the interview-critical one: "why an integration test?" is a genuine
design question. Own the answer: *because the guarantee is a database-level
contract, and a mock can't prove a database-level contract.*

## Your tasks

1. **Run the fast tests first:**
   ```bash
   cd indexer && mvn -q -Dtest=ChunkerTest test
   ```
   ~5 seconds. Confirms your test wiring works.

2. **Run the integration test:** (Docker must be running; Testcontainers pulls
   pgvector/pg16 the first time - ~30s):
   ```bash
   mvn -q -Dtest=AclRevocationIntegrationTest test
   ```
   Green: the revocation promise is proven. Red: the core feature broke;
   fix before doing anything else.

3. **Run everything:**
   ```bash
   mvn -q test
   ```

4. Re-run the corpus with the new callback to see the difference in output:
   ```bash
   cd ../scripts && source .venv/bin/activate
   python corpus_generator.py
   # Look for "Delivered 7/7" instead of the old "Published 7"
   ```

5. Commit:
   ```
   Day 5: hardening - integration test for revocation promise, chunker unit
   tests, delivery callback, runbook

   - AclRevocationIntegrationTest: proves ACL change updates allowed_groups
     byte-identical to prior embedding (Testcontainers/pgvector)
   - ChunkerTest: fast unit coverage
   - corpus_generator delivery callback: broker-ack, not queue-ack
   - docs/runbook.md: playbook for recurring local-dev issues
   ```

The ingestion half of the project is now not just built - it's *provably*
correct. Day 6 opens the query side: the SQL that USES `allowed_groups &&
user_groups` to filter retrieval before ranking. Everything you've built has
been leading to that filter.
