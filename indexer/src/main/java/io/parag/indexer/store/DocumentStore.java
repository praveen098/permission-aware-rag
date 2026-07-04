package io.parag.indexer.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps the `documents` table in sync. chunks.document_id has a foreign key to
 * documents(id), so the document row must exist before its chunks are inserted.
 * Also mirrors the ACL set into document_acls, the normalized source of truth
 * that chunks.allowed_groups is a denormalized cache of (Day 1 D3).
 */
@Component
public class DocumentStore {

    private final JdbcTemplate jdbc;

    public DocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert or update the document row. */
    public void upsertDocument(
            String id, String sourceType, String space, String title, int version) {
        jdbc.update(
                "INSERT INTO documents (id, source_type, space, title, version, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "  source_type = EXCLUDED.source_type, "
                        + "  space = EXCLUDED.space, "
                        + "  title = EXCLUDED.title, "
                        + "  version = EXCLUDED.version, "
                        + "  updated_at = now()",
                id, sourceType, space, title, version);
    }

    /** Replace the document's ACL rows (source of truth) with the given group ids. */
    public void replaceAcls(String documentId, java.util.List<Integer> groupIds) {
        jdbc.update("DELETE FROM document_acls WHERE document_id = ?", documentId);
        for (Integer gid : groupIds) {
            jdbc.update(
                    "INSERT INTO document_acls (document_id, group_id) VALUES (?, ?) "
                            + "ON CONFLICT DO NOTHING",
                    documentId, gid);
        }
    }

    public void deleteDocument(String documentId) {
        // chunks + document_acls cascade via FK ON DELETE CASCADE.
        jdbc.update("DELETE FROM documents WHERE id = ?", documentId);
    }
}
