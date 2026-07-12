# Day 1 — Architecture & Design Decisions

These are the notes you learn from and teach from. Every decision below is one an
interviewer can probe. Own them.

## D1. Pre-filter vs post-filter (the heart of the project)

**Post-filter (wrong):** retrieve global top-k by similarity, then drop chunks the
user can't read.

Why it fails:
- **Recall collapse.** If the top 10 global matches are all in an HR doc the user
  can't see, they get zero results even though relevant *visible* docs exist at
  rank 11+. You'd have to over-fetch unboundedly to compensate.
- **Side-channel leak.** Latency, result counts, or "3 results were hidden"
  behavior tells an attacker that restricted content matching their query
  *exists*. Existence is itself information ("layoffs Q3" returning suspicious
  emptiness).
- **One bug from disaster.** The filter is a separate step that can be skipped,
  reordered, or forgotten in a refactor.

**Pre-filter (ours):** the permission predicate is part of the retrieval query
itself — `WHERE allowed_groups && :user_group_ids` — so the ANN/keyword search
ranks only over the user's visible universe. There is no moment where forbidden
chunks exist in application memory.

Real-world echo: Glean and Rovo both build permission-trimmed retrieval; this is
also why they sync ACLs continuously rather than at query time.

## D2. Where do permissions live at query time?

We resolve user → group ids at query start (one indexed join), then pass the int
array into retrieval. Alternative — joining `document_acls` inside the retrieval
query — is normalized and always-correct but adds a join against a large table on
the hot path. Which leads to:

## D3. Denormalized `allowed_groups[]` on chunks

- Chunks carry a copy of the doc's allowed groups as an `INT[]` with a GIN index.
  Filter is a fast array-overlap (`&&`) that composes with both HNSW and
  full-text queries.
- **The payoff:** an ACL change is `UPDATE chunks SET allowed_groups = ... WHERE
  document_id = ...`. No re-chunking, no re-embedding, no vector churn.
  Permission revocation propagates in seconds, decoupled from the (expensive)
  embedding path.
- **The cost (say this unprompted in interviews — trade-offs are your senior-tier
  gap):** it's a cache of `document_acls`, and caches can drift. The indexer is
  the single writer keeping them in sync; the eval suite (Day 8) includes a
  consistency check. Classic normalize-vs-denormalize: we pay write-path
  complexity to keep the read path (every query) fast and simple.

## D4. Event design

- **One topic, one Avro schema, four event types** (`DOC_CREATED/UPDATED/DELETED`,
  `ACL_CHANGED`) rather than a schema per type. Ordering across types *for the
  same document* matters (create → acl-change → update must replay correctly);
  one topic keyed by `document_id` guarantees per-document ordering within a
  partition. Schema-per-topic would lose that.
- **`doc_version` monotonic per document.** Consumer rule: if incoming
  `doc_version` < stored version, drop the event. This gives idempotency under
  Kafka redelivery (at-least-once) and safety under producer retries. Same
  mindset as your GWAF correlation-id work: assume duplicates, design for them.
- **ACL events carry the full replacement set, not a diff.** Replaying
  `{allowed: [hr, leadership]}` twice is harmless; replaying `{remove: hr}` twice
  needs careful state. Full-state events are naturally idempotent.
- **Events are self-contained** (group *names*, full body). Consumer never calls
  back to the source system — same "fat event" pattern Confluent recommends to
  avoid read-time coupling.

## D5. Storage: why pgvector, not a dedicated vector DB

Pinecone/Qdrant/Weaviate do metadata filtering too, but Postgres gives us the
identity tables, ACL source of truth, full-text search, and vectors in **one
system with transactions** — chunk upsert + ACL update commit atomically. At this
scale (thousands of docs), HNSW in pgvector is plenty. Say honestly: at 100M+
chunks or heavy QPS you'd revisit — that's the "when would you change your mind"
answer interviewers want.

## D6. Hybrid retrieval preview (built Day 6)

Vector search catches paraphrase ("how do I get reimbursed" → travel policy);
keyword search catches exact tokens vector models mangle (ticket ids, error
codes, "COSM-12226"). Merge with **Reciprocal Rank Fusion**:
`score(d) = Σ 1/(k + rank_i(d))`, k≈60. Rank-based, so no score-scale
normalization problem between cosine similarity and ts_rank.

## Defense questions — answer these before Day 2

1. A teammate says: "post-filtering is simpler, just fetch top-50 and filter."
   Give two failure modes, including one that leaks information without leaking
   content.
2. An employee is removed from the `finance` group at 10:00. Walk the exact path
   (tables/fields touched) by which their 10:01 query stops seeing finance docs.
   What re-embedding happens? Why is a stale answer here worse than stale content?
3. Kafka redelivers a `DOC_UPDATED` event you already processed. What stops
   duplicate chunks? (Two mechanisms in our design.)
4. Why key events by `document_id` instead of round-robin partitioning? What
   breaks if an ACL_CHANGED for doc X lands on a different partition than its
   DOC_CREATED?
   
```
Day 1: architecture, infra, and contracts for permission-aware RAG

- docker-compose: Kafka (KRaft) + Schema Registry + Postgres/pgvector
- DB schema with denormalized allowed_groups[] on chunks (GIN indexed)
  so ACL changes propagate without re-embedding
- Avro contract for doc-events: single schema, keyed by document_id
  for per-document ordering, doc_version for idempotency
- Design rationale in docs/notes/day-01-architecture.md
```
