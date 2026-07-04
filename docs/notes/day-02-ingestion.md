# Day 2 — Ingestion: Chunking, Embeddings, and the Service Split

Today's build: the Spring Boot indexer scaffold (Java 17 / Maven, mirroring your
SMDP stack), a `Chunker`, an `EmbeddingClient`, the Python embedding service, and
a corpus generator that publishes Avro `DOC_CREATED` events to Kafka.

The `@KafkaListener` that wires it together is Day 4-5 — deliberately. Today is
about the pieces it will call.

## D1. Why chunk at all, and how big?

Embeddings represent a *bounded* span of text as one vector. Embed a whole 5-page
doc and the vector is a blurry average - it matches everything weakly and nothing
strongly. Chunk too small (one sentence) and you shred context: "it defaults to
false" is useless without the sentence naming *what* defaults.

The budget (~350 tokens, ~50 overlap) is the usual sweet spot for this: big
enough to hold a coherent idea, small enough that the vector is sharp, with
overlap so a fact straddling a boundary survives intact in at least one chunk.

**Three strategies, and why we chose the middle one:**
- **Fixed-width** (every N tokens): trivial, but slices mid-sentence and mid-word.
  Fast, low quality.
- **Paragraph-aware greedy packing (ours):** split on blank lines, greedily pack
  paragraphs to the budget, carry an overlap tail. Respects natural boundaries,
  dependency-free. Good quality/complexity balance.
- **Semantic chunking** (embed sentences, cut where similarity drops): best
  boundaries, but needs an embedding pass *just to chunk* - slow and circular.
  Overkill here; name it as the "if I had more time" option.

**Known approximation to own out loud:** we count "tokens" as whitespace words,
not real model tokens. Within ~25% for English, keeps the indexer dependency-free.
A production version uses the model's actual tokenizer. Stating the limitation
before the interviewer finds it is the senior move.

## D2. Why is embedding a *separate service*?

The indexer is **I/O-bound** (Kafka poll, Postgres write - mostly waiting). The
embedding step is **CPU/GPU-bound** (matrix math). Glue them in one process and
they contend: a burst of embeddings starves the consumer's I/O threads, lag grows.

Splitting them means:
- Each scales on its own axis. Embedding slow? Run 3 replicas behind the HTTP
  endpoint without touching the consumer. Lag high? Scale consumers to the
  partition count without paying for idle model copies.
- Clean language boundary: Python owns ML (sentence-transformers is native there;
  no clean Java equivalent without ONNX-runtime pain), Java owns the streaming/
  transactional path (your strength, and the right tool for Kafka + JDBC).

This is the standard shape of production RAG: a stateless embedding tier the rest
of the system calls. "Why two services instead of one?" -> independent scaling +
right-tool-per-language. Solid system-design answer.

**Trade-off (say it unprompted):** the split adds a network hop and a failure mode
(embedding service down). We handle it with timeouts and by *not* committing the
Kafka offset until the whole chunk+embed+write succeeds - so a failed embed just
redelivers later. That's the Day 4 idempotency story connecting back.

## D3. Normalized embeddings + cosine

The service calls `encode(..., normalize_embeddings=True)`, so every vector is
unit length. For unit vectors, cosine similarity equals the dot product, and it
lines up with the `vector_cosine_ops` HNSW index on `chunks.embedding`. Keeping
the distance metric consistent end-to-end (model output ↔ index ↔ query) matters:
mismatched metrics are a silent retrieval-quality bug.

## D4. The corpus is designed for the leak test

Six docs, and the ACLs are not random:
- `CONF-1001` (all-staff): the intern *should* retrieve this.
- `CONF-2002` comp bands (hr, leadership), `CONF-3001` Q3 forecast
  (finance, leadership), `JIRA-905` revenue automation (finance, leadership):
  the intern must **never** retrieve these.

On Day 8 the leak test asks Dev-the-intern queries like "what are the salary
bands?" and asserts zero chunks from the restricted docs come back. Designing the
adversarial cases *into* the corpus now is why the eval is meaningful later.

## Defense questions — we'll hit these while building, not as a gate

1. Your `Chunker` uses 50-token overlap. What breaks with **zero** overlap? What
   wastes storage and money with overlap set to *half* the chunk size?
2. Someone says "just embed the whole document, skip chunking - simpler." Give the
   retrieval-quality reason that's wrong, in one sentence.
3. The embedding service is down for 30s. Walk what happens to an in-flight
   `DOC_CREATED` event. Does it get lost? (Tie your answer to the Kafka offset
   commit.)
4. Why normalize embeddings to unit length before storing them?

You nailed the Day 1 idempotency question with the word "idempotent" unprompted -
that's the level. #3 here is the same muscle: "what happens to the event when a
downstream step fails."

## Your tasks today

1. **Verify the Spring project builds:**
   ```bash
   cd indexer
   mvn -q compile        # first run downloads deps + generates Avro classes
   ```
   If `mvn` isn't installed: `brew install maven`. Confirm Java 17:
   `java -version` should show 17.x. The build generates `DocEvent.java` etc.
   from the .avsc under `target/generated-sources/avro` - look for them.

2. **Start the embedding service** (separate terminal):
   ```bash
   cd embedding-service
   python -m venv .venv && source .venv/bin/activate
   pip install -r requirements.txt      # first run downloads the model (~90MB)
   uvicorn app:app --port 8000
   curl -s localhost:8000/health        # {"status":"ok",...}
   curl -s -X POST localhost:8000/embed -H 'Content-Type: application/json' \
     -d '{"texts":["kafka consumer lag","how do I get reimbursed"]}' | head -c 200
   ```

3. **Publish the corpus:**
   ```bash
   cd scripts
   pip install -r ../requirements.txt
   python corpus_generator.py
   # then confirm the events landed:
   docker exec parag-kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 --topic doc-events \
     --from-beginning --max-messages 6 --timeout-ms 5000 | wc -l
   ```
   (The Avro bytes won't be human-readable on the console - we're just counting 6.)

3b. **Optional - the JUnit test for the Chunker** (drop in
    `indexer/src/test/java/io/parag/indexer/chunk/ChunkerTest.java`):
   ```java
   package io.parag.indexer.chunk;
   import org.junit.jupiter.api.Test;
   import java.util.List;
   import static org.junit.jupiter.api.Assertions.*;

   class ChunkerTest {
       @Test void blankInputYieldsNoChunks() {
           assertTrue(new Chunker(50, 10).chunk("  ").isEmpty());
       }
       @Test void shortDocIsOneChunk() {
           List<String> c = new Chunker(50, 10).chunk("one small paragraph here");
           assertEquals(1, c.size());
       }
       @Test void longDocSplitsWithOverlap() {
           StringBuilder sb = new StringBuilder();
           for (int i = 0; i < 8; i++) sb.append("Paragraph ").append(i)
               .append(" has several words of filler content here.\n\n");
           List<String> c = new Chunker(30, 8).chunk(sb.toString());
           assertTrue(c.size() > 1, "expected multiple chunks");
       }
   }
   ```
   Run: `mvn -q test`.

4. Commit:
   ```
   Day 2: ingestion pieces - Spring indexer scaffold, chunker,
   embedding service, corpus generator

   - Spring Boot (Java 17) module: Avro codegen from doc_event.avsc,
     Spring Kafka consumer config (manual ack), pgvector deps
   - Chunker: paragraph-aware greedy packing with token overlap
   - EmbeddingClient -> Python FastAPI embedding service (all-MiniLM-L6-v2,
     384-dim, normalized); split out for independent scaling
   - corpus_generator publishes 6 Avro DOC_CREATED events with ACL patterns
     designed as Day 8 leak-test targets
   - notes in docs/notes/day-02-ingestion.md
   ```

When the corpus is on the topic and the embedding service answers `/health`,
Day 3 wires the first real ingestion: consume one event end-to-end, chunk, embed,
and write chunks to Postgres - your first rows in the `chunks` table.
