# PARAG — Permission-Aware RAG

Enterprise knowledge assistant over Confluence-style pages and Jira-style tickets, where **every user only gets answers derived from documents they are allowed to read**. Same product category as Atlassian Rovo, Glean, and Microsoft 365 Copilot.

Naive RAG leaks data: embed the whole corpus into one vector store and an intern asking *"what is our comp structure?"* gets chunks from an HR document they could never open. PARAG solves this with **ACL-filtered retrieval** (permissions applied *before* ranking, not after) and **Kafka-driven incremental indexing** so both content and permission changes propagate in seconds, without batch re-embedding.

## Architecture

```
                       ┌──────────────────┐
  corpus / simulator ──► Kafka: doc-events │  (Avro, keyed by document_id)
                       └────────┬─────────┘
                                │ DOC_CREATED / DOC_UPDATED / DOC_DELETED / ACL_CHANGED
                                ▼
                        ┌──────────────┐     chunk → embed → upsert
                        │   Indexer    ├──────────────────────────┐
                        │  (consumer)  │  ACL_CHANGED: update     │
                        └──────────────┘  allowed_groups only     ▼
                                                        ┌───────────────────┐
                                                        │ Postgres+pgvector │
                                                        │ chunks(embedding, │
                                                        │  tsv, allowed_    │
                                                        │  groups[])        │
                                                        └─────────▲─────────┘
                                                                  │ single query:
                                                                  │ permission filter
┌──────┐   query    ┌───────────────┐   groups   ┌────────────┐  │ + hybrid retrieval
│ User ├───────────►│ FastAPI query ├───────────►│ Retrieval  ├──┘
└──────┘            │   service     │            │ (RRF merge)│
                    └───────┬───────┘            └─────┬──────┘
                            │        top-k chunks      │
                            ▼                          ▼
                     Claude generation with citations, or "no access" refusal
```

**Key design decisions** (full rationale in `docs/notes/`):

1. **Pre-filter, not post-filter.** The permission predicate (`allowed_groups && user_groups`) is part of the retrieval SQL, so ranking happens over the user's visible universe. Post-filtering top-k breaks recall and can leak via score behavior.
2. **Denormalized `allowed_groups[]` on chunks.** ACL changes are metadata-only updates — no re-embedding, no re-chunking. Permission revocation propagates in one `UPDATE`.
3. **Single Avro schema, four event types, keyed by `document_id`.** Per-document ordering guaranteed within a partition; `doc_version` gives idempotency against redelivery and stale events.
4. **Hybrid retrieval** — pgvector HNSW (cosine) + Postgres full-text, merged with Reciprocal Rank Fusion.
5. **Leak-rate is a hard metric.** The eval suite includes adversarial queries that must return "no access"; the target is zero, not "low".

## Quickstart

```bash
# 1. Infra: Kafka (KRaft) + Schema Registry + Postgres/pgvector
docker compose up -d

# 2. Python env
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env

# 3. (Day 2+) Seed corpus and start the indexer
#    python -m scripts.seed_corpus
#    python -m src.app.indexer
```

## Repo layout

```
db/init.sql           schema: users/groups/documents/acls/chunks
schemas/doc_event.avsc  Avro contract for the doc-events topic
src/app/              config, indexer, retrieval, api (built day by day)
scripts/              corpus generator, producers, utilities
docs/notes/           daily design notes and decision rationale
```

## Status

Built over a 10-day sprint. Day-by-day log in `docs/notes/`.

- [x] Day 1 — architecture, infra, DB schema, event contract
- [x] Day 2 — Spring indexer scaffold, chunker, embedding service, corpus generator
- [x] Day 3 — end-to-end ingestion: consume → chunk → embed → write chunks
- [x] Day 4 — live permissions: DOC_UPDATED, DOC_DELETED, ACL_CHANGED revocation
- [x] Day 5 — hardening: revocation integration test, chunker unit tests, runbook
- [x] Day 6 — permission-aware hybrid retrieval (query service)
- [x] Day 7 — grounded generation with citations, refusal-safe
- [ ] Day 8 — leak-test suite: automated proof that permissions hold
- [ ] Day 8 — eval harness: quality, leak-rate, latency, cost
- [ ] Day 9 — hardening, docs
- [ ] Day 10 — demo
