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
 *    succeeds. If anything throws, we don't ack, the transaction rolls back, and
 *    Kafka redelivers - nothing is lost.
 *  - Version gate on content events: if doc_version <= what's stored, skip.
 *
 * All four event types are now handled:
 *  - DOC_CREATED / DOC_UPDATED -> DocumentIndexer.upsert (chunk, embed, store)
 *  - DOC_DELETED               -> DocumentMutator.delete
 *  - ACL_CHANGED               -> DocumentMutator.changeAcl (no re-embedding)
 *
 * Transactional work lives in DocumentIndexer / DocumentMutator (separate beans)
 * so Spring's @Transactional proxy applies.
 */
@Component
public class DocEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DocEventConsumer.class);

    private final DocumentIndexer indexer;
    private final DocumentMutator mutator;
    private final ChunkStore chunkStore;

    public DocEventConsumer(DocumentIndexer indexer, DocumentMutator mutator, ChunkStore chunkStore) {
        this.indexer = indexer;
        this.mutator = mutator;
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
                case "DOC_DELETED" -> mutator.delete(event);
                case "ACL_CHANGED" -> mutator.changeAcl(event);
                default -> log.warn("unknown event type: {}", type);
            }

            // Commit the Kafka offset ONLY after successful processing.
            ack.acknowledge();

        } catch (Exception e) {
            // Do NOT ack: event stays uncommitted, Kafka redelivers it.
            log.error("failed to process {} v{}; will be redelivered: {}",
                    documentId, version, e.getMessage(), e);
            throw e;
        }
    }
}
