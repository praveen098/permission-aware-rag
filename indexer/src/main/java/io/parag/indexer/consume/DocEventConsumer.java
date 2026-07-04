package io.parag.indexer.consume;

import io.parag.events.DocEvent;
import io.parag.indexer.store.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes document lifecycle events and keeps Postgres in sync.
 *
 * DURABILITY / IDEMPOTENCY (Day 1 D4, Day 2 D2, Day 3 D2/D3):
 *  - Manual ack (application.yml: enable-auto-commit=false,
 *    ack-mode=manual_immediate). We call ack.acknowledge() ONLY after processing
 *    succeeds. If anything throws (embedding service down, DB error), we don't
 *    ack, the transaction rolls back, and Kafka redelivers - nothing is lost.
 *  - Version gate: if the event's doc_version <= what's stored, skip. Makes
 *    redelivery of an already-processed event a no-op.
 *
 * The transactional work lives in DocumentIndexer (a separate bean) so Spring's
 * @Transactional proxy actually applies - calling a @Transactional method on
 * `this` would silently bypass it.
 *
 * Day 3 implements DOC_CREATED end-to-end. DOC_UPDATED shares the same upsert
 * path. DOC_DELETED / ACL_CHANGED are stubbed - that's your Day 4 build.
 */
@Component
public class DocEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocEventConsumer.class);

    private final DocumentIndexer indexer;
    private final ChunkStore chunkStore;

    public DocEventConsumer(DocumentIndexer indexer, ChunkStore chunkStore) {
        this.indexer = indexer;
        this.chunkStore = chunkStore;
    }

    @KafkaListener(topics = "${parag.doc-events-topic:doc-events}", groupId = "parag-indexer")
    public void onEvent(DocEvent event, Acknowledgment ack) {
        String documentId = event.getDocumentId();
        int version = event.getDocVersion();
        String type = event.getEventType().toString();
        log.info("received {} for {} v{}", type, documentId, version);

        try {
            switch (type) {
                case "DOC_CREATED", "DOC_UPDATED" -> {
                    int stored = chunkStore.currentVersion(documentId);
                    if (version <= stored) {
                        log.info("skipping {} v{} - already at v{}", documentId, version, stored);
                    } else {
                        indexer.upsert(event);
                    }
                }
                case "DOC_DELETED" ->
                    log.warn("DOC_DELETED not yet implemented (Day 4)");
                case "ACL_CHANGED" ->
                    log.warn("ACL_CHANGED not yet implemented (Day 4)");
                default -> log.warn("unknown event type: {}", type);
            }

            ack.acknowledge();

        } catch (Exception e) {
            log.error("failed to process {} v{}; will be redelivered: {}",
                    documentId, version, e.getMessage(), e);
            throw e;
        }
    }
}
