package io.parag.indexer.consume;

import io.parag.events.DocEvent;
import io.parag.indexer.chunk.Chunker;
import io.parag.indexer.embed.EmbeddingClient;
import io.parag.indexer.store.ChunkStore;
import io.parag.indexer.store.DocumentStore;
import io.parag.indexer.store.GroupResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Holds the transactional indexing logic. Separated from DocEventConsumer on
 * purpose: @Transactional works via a Spring proxy that only intercepts calls
 * from OUTSIDE the bean. If the consumer called a @Transactional method on
 * itself (self-invocation), the proxy is bypassed and the annotation silently
 * does nothing. By putting the transactional method on a different bean that the
 * consumer calls, the proxy boundary is crossed and the transaction is real.
 * (This is a classic Spring gotcha - see docs/notes/day-03 D1.)
 */
@Service
public class DocumentIndexer {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexer.class);

    private final Chunker chunker;
    private final EmbeddingClient embeddingClient;
    private final ChunkStore chunkStore;
    private final DocumentStore documentStore;
    private final GroupResolver groupResolver;

    public DocumentIndexer(
            Chunker chunker,
            EmbeddingClient embeddingClient,
            ChunkStore chunkStore,
            DocumentStore documentStore,
            GroupResolver groupResolver) {
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.chunkStore = chunkStore;
        this.documentStore = documentStore;
        this.groupResolver = groupResolver;
    }

    /**
     * DOC_CREATED / DOC_UPDATED: document row, ACLs, and chunks all commit
     * together or not at all. The embedding call is inside the transaction; if it
     * throws, everything rolls back and (because the consumer never acks) Kafka
     * redelivers.
     */
    @Transactional
    public void upsert(DocEvent event) {
        String documentId = event.getDocumentId();
        int version = event.getDocVersion();
        var payload = event.getPayload();
        if (payload == null) {
            throw new IllegalStateException("DOC_CREATED/UPDATED without payload: " + documentId);
        }

        String body = payload.getBody();
        String title = payload.getTitle();
        String space = payload.getSpace();
        String sourceType = payload.getSourceType().toString();

        List<String> groupNames = payload.getAllowedGroups().stream().map(Object::toString).toList();
        List<Integer> groupIds = groupResolver.resolve(groupNames);

        documentStore.upsertDocument(documentId, sourceType, space, title, version);
        documentStore.replaceAcls(documentId, groupIds);

        List<String> chunks = chunker.chunk(title + "\n\n" + body);
        if (chunks.isEmpty()) {
            log.info("no chunks for {} (empty body); wrote document row only", documentId);
            return;
        }

        List<float[]> embeddings = embeddingClient.embed(chunks);
        chunkStore.replaceDocumentChunks(documentId, version, chunks, embeddings, groupIds);

        log.info("indexed {} v{}: {} chunks, groups={}",
                documentId, version, chunks.size(), groupIds);
    }
}
