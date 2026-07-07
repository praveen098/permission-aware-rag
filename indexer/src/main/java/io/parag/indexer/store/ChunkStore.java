package io.parag.indexer.store;

import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.List;

/**
 * Writes a document's chunks to Postgres. All operations for one document run in
 * a single transaction so the store is never left half-updated.
 *
 * The "upsert a document" pattern (Day 4 uses it for DOC_UPDATED too):
 *   delete-all-chunks-for-doc, then insert the new set.
 * Simpler and always-correct versus diffing which chunks changed, and cheap at
 * our per-document chunk counts. We document the trade-off in docs/notes/day-03.
 */
@Component
public class ChunkStore {

    private final JdbcTemplate jdbc;

    public ChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replace all chunks for a document with a fresh set, inside one transaction.
     *
     * @param documentId   source id (e.g. CONF-1001)
     * @param docVersion   version these chunks were produced from
     * @param contents     chunk texts, in order
     * @param embeddings   parallel list of 384-dim vectors (embeddings.get(i) is for contents.get(i))
     * @param allowedGroups group ids permitted to read this document
     */
    @Transactional
    public void replaceDocumentChunks(
            String documentId,
            int docVersion,
            List<String> contents,
            List<float[]> embeddings,
            List<Integer> allowedGroups) {

        if (contents.size() != embeddings.size()) {
            throw new IllegalArgumentException(
                    "contents and embeddings size mismatch: "
                            + contents.size() + " vs " + embeddings.size());
        }

        // 1. Remove any existing chunks for this document (idempotent re-index).
        jdbc.update("DELETE FROM chunks WHERE document_id = ?", documentId);

        // 2. Insert the new chunk set.
        Integer[] groupsBoxed = allowedGroups.toArray(new Integer[0]);

        for (int i = 0; i < contents.size(); i++) {
            final int chunkIndex = i;
            final PGvector vector = new PGvector(embeddings.get(i));

            jdbc.update(connection -> {
                Array groupsArray = connection.createArrayOf("int", groupsBoxed);
                var ps = connection.prepareStatement(
                        "INSERT INTO chunks "
                                + "(document_id, doc_version, chunk_index, content, embedding, allowed_groups) "
                                + "VALUES (?, ?, ?, ?, ?, ?)");
                ps.setString(1, documentId);
                ps.setInt(2, docVersion);
                ps.setInt(3, chunkIndex);
                ps.setString(4, contents.get(chunkIndex));
                ps.setObject(5, vector);
                ps.setArray(6, groupsArray);
                return ps;
            });
        }
    }

    /** For DOC_DELETED. Removes all chunks for a document. */
    @Transactional
    public void deleteDocument(String documentId) {
        jdbc.update("DELETE FROM chunks WHERE document_id = ?", documentId);
    }

    /**
     * ACL_CHANGED: update the permission array on every chunk of a document
     * WITHOUT touching the embeddings.
     *
     * This is the whole point of the denormalized allowed_groups design (Day 1
     * D3). A permission change - e.g. removing 'leadership' so {3,4} becomes {3}
     * - is a single metadata UPDATE. No re-chunking, no re-embedding, no calls to
     * the embedding service. Revocation propagates in one cheap statement, and
     * takes effect on the very next query because retrieval filters on this array.
     *
     * Returns the number of chunks updated (0 means the document isn't indexed
     * yet - see the consumer for how that's handled).
     */
    @Transactional
    public int updateAllowedGroups(String documentId, List<Integer> allowedGroups) {
        Integer[] groupsBoxed = allowedGroups.toArray(new Integer[0]);
        return jdbc.update(connection -> {
            Array groupsArray = connection.createArrayOf("int", groupsBoxed);
            var ps = connection.prepareStatement(
                    "UPDATE chunks SET allowed_groups = ? WHERE document_id = ?");
            ps.setArray(1, groupsArray);
            ps.setString(2, documentId);
            return ps;
        });
    }

    /** Current stored version for a document, or -1 if absent. Used for idempotency. */
    public int currentVersion(String documentId) {
        List<Integer> versions = jdbc.query(
                "SELECT MAX(doc_version) AS v FROM chunks WHERE document_id = ?",
                (rs, i) -> {
                    int v = rs.getInt("v");
                    return rs.wasNull() ? -1 : v;
                },
                documentId);
        if (versions.isEmpty() || versions.get(0) == null) {
            return -1;
        }
        return versions.get(0);
    }
}
