# Day 3 — First Event End-to-End: Consume → Chunk → Embed → Store

Today the pieces connect. The indexer now consumes a `DOC_CREATED` event, chunks
the body, embeds the chunks, and writes rows to Postgres - transactionally, and
safely against redelivery.

## What got built

- `DocEventConsumer` - the `@KafkaListener`. Receives a generated `DocEvent`,
  routes by type. DOC_CREATED/UPDATED fully wired; DELETE and ACL_CHANGED are
  stubbed TODOs (your Day 4).
- `ChunkStore` - transactional "replace all chunks for this document" (delete +
  insert) plus a `currentVersion()` used for idempotency.
- `DocumentStore` - keeps the `documents` row and `document_acls` in sync (the FK
  target and the ACL source of truth).
- `GroupResolver` - maps group names from events to the integer ids chunks store.
- `PgVectorConfig` - registers the `vector` type so embeddings bind to the column.

## D1. The whole flow, and why it's transactional

`DocumentIndexer.upsert` does four writes: document row, ACL rows, (delete old
chunks), insert new chunks. All four are inside one `@Transactional` method.
Either the document is fully indexed or not at all - never a document row with no
chunks, or chunks with a stale ACL. This matters because a query relies on all of
them being consistent; a half-write would be a correctness bug (and possibly a
leak, if chunks landed with the wrong `allowed_groups`).

**Spring gotcha worth knowing (a real interview question):** the transactional
method lives on a *separate* bean (`DocumentIndexer`), not on the consumer.
`@Transactional` works through a proxy that only intercepts calls from *outside*
the bean. If the consumer called a `@Transactional` method on itself
(self-invocation via `this.`), the proxy is bypassed and the annotation silently
does nothing - the writes wouldn't roll back together, and you'd never get an
error telling you so. Putting the method on a bean the consumer *calls* crosses
the proxy boundary, so the transaction is real. This is exactly the kind of
"looks-correct-but-silently-broken" trap that shows up in production.

## D2. Durability: what happens when the embedding service is down?

This is the Day 2 Q3 question, now answered *in code*. Trace it:

1. Consumer pulls a `DOC_CREATED` off Kafka. **Offset not yet committed** -
   config has `enable-auto-commit=false`, `ack-mode=manual_immediate`.
2. `handleUpsert` runs, reaches `embeddingClient.embed(...)`, which throws because
   the service is down.
3. The exception propagates. We **never reach `ack.acknowledge()`**. The DB
   transaction rolls back (nothing written).
4. Because the offset was never committed, **Kafka redelivers the same event**
   later. When the embedding service is back, it processes cleanly.

So: **the event is not lost.** It's retried until it succeeds. This is the
at-least-once guarantee, and it's the direct payoff of manual offset commit. You
watched Kafka hold 7 messages through full `down`/`up` cycles - same durability,
seen from the broker side.

The flip side of at-least-once is *duplicates* (an event might be processed more
than once if a crash happens between DB commit and offset commit). That's why we
also need:

## D3. Idempotency: the version gate

Before processing, the consumer reads `chunkStore.currentVersion(documentId)`. If
the event's `doc_version <= stored`, it's stale or a duplicate - skip and ack.
Combined with the `UNIQUE (document_id, doc_version, chunk_index)` constraint as a
backstop, re-processing the same event can't create duplicate chunks. Two layers,
exactly like Day 1's answer.

## D4. Why "delete all + re-insert" instead of diffing chunks?

On an update we blow away the document's chunks and write the new set, rather than
computing which chunks changed. At our scale (a handful of chunks per doc) this is
simpler and always-correct. Diffing would save writes on huge documents but adds
real complexity (matching old chunks to new, handling reorderings). Name the
trade-off: "I chose replace-all for correctness and simplicity; I'd revisit if
documents were large and updates frequent." That's the senior framing.

## Defense questions

1. Walk the exact sequence when the embedding service is down mid-event. Does the
   event get lost? Which line do we never reach, and what does Kafka do next?
2. Why is `handleUpsert` `@Transactional`? Give a concrete bad state that could
   exist if the four writes weren't atomic - and say which one would be a security
   bug, not just a consistency bug.
3. We ack AFTER the DB commit, not before. What breaks if we acked first
   (immediately on receiving the event) and then the DB write failed?
4. The version gate skips events where `doc_version <= stored`. Why `<=` and not
   `<`? What real case does the "equal" part cover?

You explained the *project* clearly in your own words already - these are the same
skill pointed at the mechanics. #1 and #2 are the ones interviewers actually ask.

## Your tasks

1. **Build:**
   ```bash
   cd indexer && mvn -q compile
   ```
   New deps: none beyond Day 2. This compiles the consumer + stores against the
   generated `DocEvent`.

2. **Run the full pipeline.** You need three things up: Docker stack, the
   embedding service, and the corpus already on the topic (it is). Then start the
   indexer:
   ```bash
   # terminal A: embedding service (if not already running)
   cd embedding-service && source .venv/bin/activate && uvicorn app:app --port 8000

   # terminal B: the indexer
   cd indexer && mvn spring-boot:run
   ```
   Watch terminal B: you should see "received DOC_CREATED ...", then
   "indexed CONF-1001 v1: N chunks, groups=[...]" for each of the 7 documents.

3. **SEE YOUR DATA.** This is the payoff - your first rows:
   ```bash
   docker exec -it parag-postgres psql -U parag -c \
     "SELECT document_id, doc_version, chunk_index, allowed_groups,
             left(content, 60) AS preview
      FROM chunks ORDER BY document_id, chunk_index;"
   ```
   You should see chunks for all 7 docs, each with its `allowed_groups`. Confirm
   the restricted docs (CONF-2002, CONF-3001, JIRA-905) carry only their
   finance/hr/leadership group ids - those ids are what the leak filter will use.

   Count check:
   ```bash
   docker exec -it parag-postgres psql -U parag -c \
     "SELECT document_id, count(*) FROM chunks GROUP BY document_id ORDER BY 1;"
   ```

4. Commit:
   ```
   Day 3: first event end-to-end - consume, chunk, embed, store

   - DocEventConsumer @KafkaListener: DOC_CREATED wired end-to-end,
     manual ack after DB commit (at-least-once), version gate for idempotency
   - ChunkStore: transactional replace-all-chunks + currentVersion
   - DocumentStore: documents + document_acls upkeep
   - GroupResolver, PgVectorConfig
   - DELETE / ACL_CHANGED stubbed for Day 4
   - notes in docs/notes/day-03-ingest-pipeline.md
   ```

When you can run that SELECT and see your documents sitting in Postgres as
chunks with embeddings and permission arrays, the core ingestion half of the
project is real. Day 4 makes it *live*: DOC_UPDATED re-indexing, DOC_DELETED
cleanup, and the ACL_CHANGED revocation path - the permission story that makes
this project special.
