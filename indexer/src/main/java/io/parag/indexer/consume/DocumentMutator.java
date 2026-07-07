package io.parag.indexer.consume;

import io.parag.events.DocEvent;
import io.parag.indexer.store.ChunkStore;
import io.parag.indexer.store.DocumentStore;
import io.parag.indexer.store.GroupResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional handlers for the two "make permissions live" event types.
 * Separate bean (like DocumentIndexer) so @Transactional actually applies.
 */
@Service
public class DocumentMutator {

    private static final Logger log = LoggerFactory.getLogger(DocumentMutator.class);

    private final ChunkStore chunkStore;
    private final DocumentStore documentStore;
    private final GroupResolver groupResolver;

    public DocumentMutator(ChunkStore chunkStore, DocumentStore documentStore, GroupResolver groupResolver) {
        this.chunkStore = chunkStore;
        this.documentStore = documentStore;
        this.groupResolver = groupResolver;
    }

    /**
     * DOC_DELETED: remove the document and everything hanging off it. The
     * documents row is deleted; chunks and document_acls cascade via their
     * ON DELETE CASCADE foreign keys. One transaction.
     */
    @Transactional
    public void delete(DocEvent event) {
        String documentId = event.getDocumentId();
        chunkStore.deleteDocument(documentId);   // explicit for clarity; cascade would also cover it
        documentStore.deleteDocument(documentId);
        log.info("deleted {} (chunks + document + acls)", documentId);
    }

    /**
     * ACL_CHANGED: replace the permission set on the document's chunks and its
     * document_acls rows - WITHOUT re-embedding. The embeddings are untouched;
     * only allowed_groups changes. Takes effect on the next query.
     *
     * If the document isn't indexed yet (0 chunks updated), we still update the
     * document_acls source of truth, so when the document's content event
     * arrives later it already has the right ACL. (Out-of-order safety: an
     * ACL_CHANGED can legitimately arrive before the DOC_CREATED it refers to.)
     */
    @Transactional
    public void changeAcl(DocEvent event) {
        String documentId = event.getDocumentId();
        if (event.getAcl() == null) {
            throw new IllegalStateException("ACL_CHANGED without acl payload: " + documentId);
        }

        List<String> groupNames = event.getAcl().getAllowedGroups().stream()
                .map(Object::toString).toList();
        List<Integer> groupIds = groupResolver.resolve(groupNames);

        // Source of truth first.
        documentStore.replaceAcls(documentId, groupIds);
        // Then the denormalized cache on the chunks (no re-embedding).
        int updated = chunkStore.updateAllowedGroups(documentId, groupIds);

        log.info("acl changed for {}: groups now {} ({} chunks updated, no re-embedding)",
                documentId, groupIds, updated);
    }
}
