# Day 4 — Making Permissions Live: Update, Delete, and Revocation

Day 3 indexed documents. Day 4 makes the index *live*: it reacts to documents
changing, being removed, and - the centerpiece - permissions changing. This is
the feature that separates PARAG from a static "chat with your docs" demo.

## What got built

- `ChunkStore.updateAllowedGroups(...)` - the revocation primitive: one UPDATE of
  the `allowed_groups` array, no embeddings touched.
- `DocumentMutator` - transactional handlers for DOC_DELETED and ACL_CHANGED
  (separate bean so @Transactional applies - same proxy rule as Day 3).
- `DocEventConsumer` - the two stubs are now wired to real handlers.
- `scripts/emit_event.py` - publish update / delete / acl-revoke / acl-grant
  events on demand, to watch the pipeline react.

## D1. ACL_CHANGED: revocation without re-embedding (the crown jewel)

The scenario: someone is removed from the `leadership` group at 10:00. They must
lose access to leadership-only documents *immediately* - not tonight, not after a
re-index.

In PARAG, an ACL change to CONF-3001 (was `{finance, leadership}` = `{3,4}`)
becomes a single statement:

```sql
UPDATE chunks SET allowed_groups = '{3}' WHERE document_id = 'CONF-3001';
```

That's it. **No re-chunking. No embedding-service call. No vector writes.** The
embeddings - the expensive part - are completely untouched, because the *meaning*
of the text didn't change, only *who can see it*. On the very next query, the
retrieval filter (`allowed_groups && user_groups`) sees `{3}` and anyone who was
reaching this doc via `leadership` no longer matches.

**This is the entire payoff of the Day 1 D3 decision** to denormalize
`allowed_groups` onto chunks. If permissions lived only in a normalized
`document_acls` table joined at query time, this would still work - but the reason
we *can* keep the denormalized copy cheaply is that updating it is O(chunks of one
doc), decoupled from embedding. Content changes (expensive: re-embed) and
permission changes (cheap: array update) are on separate paths. Say that in an
interview and you've shown senior-level data modeling judgment.

**Interview framing:** "How do you revoke access instantly in a RAG system
without reprocessing the corpus?" -> "Permissions are denormalized onto the
retrieval unit as an indexed array, separate from the embedding. A permission
change is a metadata UPDATE that takes effect on the next query; embeddings never
move. Content changes and ACL changes are independent paths."

## D2. DOC_UPDATED: content changed (the expensive path)

When the *body* changes, the meaning changes, so we must re-embed. This reuses the
Day 3 upsert path: delete the doc's chunks, re-chunk, re-embed, re-insert - all in
one transaction, guarded by the version gate (v2 > v1, so it proceeds; a
redelivered v2 is skipped). This is the contrast that makes D1 land: update =
re-embed (costly), acl-change = array update (cheap).

## D3. DOC_DELETED: cleanup

Delete the `documents` row; `chunks` and `document_acls` cascade via
`ON DELETE CASCADE`. We also delete chunks explicitly for clarity. One
transaction. After this, the document is gone from all future queries.

## D4. Out-of-order safety

Events for one document are ordered within a partition (keyed by document_id,
Day 1 D4). But an ACL_CHANGED could still arrive before the DOC_CREATED it refers
to in edge cases (e.g. replays). `changeAcl` handles this: it always updates
`document_acls` (source of truth) even if 0 chunks exist yet, so when the content
event lands later it already carries the right ACL. Defensive, idempotent.

## Defense questions

1. Someone is removed from `leadership` at 10:00. Walk the exact statement that
   runs and explain why NO embedding work happens. Why is that cheap and why does
   it take effect immediately?
2. Contrast DOC_UPDATED and ACL_CHANGED: which re-embeds, which doesn't, and why
   is that the *correct* split rather than just an optimization?
3. `updateAllowedGroups` returns the count of chunks changed. What does 0 mean,
   and why do we still update `document_acls` in that case?
4. An ACL_CHANGED is redelivered by Kafka (processed twice). Why is that safe -
   i.e., why is "replace the group set to {3}" naturally idempotent, where
   "remove group 4" would not be?

You explained the `{2,4}` filter in your own words already - Q1 here is the same
idea in motion. That's the interview centerpiece; own it cold.

## Your tasks

1. **Build:**
   ```bash
   cd indexer && mvn -q compile
   ```

2. **Restart the indexer** (it now handles all four event types):
   ```bash
   mvn spring-boot:run
   ```
   (Embedding service + Docker stack + corpus already indexed from Day 3.)

3. **Watch revocation happen live.** In another terminal:
   ```bash
   cd scripts && source .venv/bin/activate

   # Before: CONF-3001 is {3,4} (finance, leadership)
   docker exec parag-postgres psql -U parag -c \
     "SELECT document_id, allowed_groups FROM chunks WHERE document_id='CONF-3001';"

   # Revoke leadership:
   python emit_event.py acl-revoke

   # After (near-instantly): {3,4} -> {3}
   docker exec parag-postgres psql -U parag -c \
     "SELECT document_id, allowed_groups FROM chunks WHERE document_id='CONF-3001';"
   ```
   Watch the indexer log: `acl changed for CONF-3001: groups now [3] (1 chunks
   updated, no re-embedding)`. You just revoked access with zero embedding work.

4. **Watch delete:**
   ```bash
   python emit_event.py delete   # removes JIRA-881
   docker exec parag-postgres psql -U parag -c \
     "SELECT count(*) FROM chunks WHERE document_id='JIRA-881';"   # -> 0
   ```

5. **Watch update (re-embed):**
   ```bash
   python emit_event.py update   # CONF-1002 -> v2, re-embeds
   ```
   Indexer log shows `indexed CONF-1002 v2: N chunks` - the re-embed path.

6. Commit:
   ```
   Day 4: make permissions live - update, delete, ACL revocation

   - ChunkStore.updateAllowedGroups: revocation via single array UPDATE,
     no re-embedding (payoff of denormalized allowed_groups)
   - DocumentMutator: transactional DOC_DELETED + ACL_CHANGED handlers
   - DocEventConsumer: all four event types wired
   - scripts/emit_event.py to drive update/delete/acl events
   - notes in docs/notes/day-04-live-permissions.md
   ```

Day 5 hardens this (edge cases, an integration test proving revocation), then
Day 6 builds the query side - where these `allowed_groups` finally get *used* to
filter retrieval. Everything you've built so far has been leading to that filter.
