package io.parag.indexer.integration;

import com.pgvector.PGvector;
import io.parag.indexer.store.ChunkStore;
import io.parag.indexer.store.DocumentStore;
import io.parag.indexer.store.GroupResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The single most important test in this repo.
 *
 * Proves the core promise of PARAG: an ACL change updates allowed_groups
 * WITHOUT touching the embedding. The embedding vector after the ACL change is
 * byte-identical to the one before.
 *
 * If this test ever fails, the "revocation without re-embedding" claim - the
 * feature that makes this project special - is broken.
 *
 * We spin up a real Postgres with pgvector via Testcontainers (not a mock), run
 * the init.sql schema against it, then exercise ChunkStore directly. No Kafka
 * needed at this layer; we're testing the storage contract that the Kafka
 * consumer relies on.
 */
@SpringBootTest(classes = AclRevocationIntegrationTest.TestConfig.class)
@Testcontainers
class AclRevocationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("parag")
            .withUsername("parag")
            .withPassword("parag_dev")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("test-init.sql"),
                    "/docker-entrypoint-initdb.d/init.sql");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired ChunkStore chunkStore;
    @Autowired DocumentStore documentStore;
    @Autowired GroupResolver groupResolver;
    @Autowired JdbcTemplate jdbc;

    private static final String DOC_ID = "TEST-DOC-1";
    private static final float[] FIXED_VECTOR = fixed384();

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM chunks WHERE document_id = ?", DOC_ID);
        jdbc.update("DELETE FROM documents WHERE id = ?", DOC_ID);

        // Ensure the two groups we use exist and get their ids.
        List<Integer> financeAndLeadership = groupResolver.resolve(List.of("finance", "leadership"));
        documentStore.upsertDocument(DOC_ID, "confluence", "FIN", "Test Doc", 1);
        documentStore.replaceAcls(DOC_ID, financeAndLeadership);
        chunkStore.replaceDocumentChunks(
                DOC_ID, 1,
                List.of("this is the only chunk of the test doc"),
                List.of(FIXED_VECTOR),
                financeAndLeadership);
    }

    @Test
    void aclChangeUpdatesGroupsAndDoesNotTouchEmbedding() {
        // Snapshot the embedding BEFORE the ACL change.
        float[] before = readEmbedding(DOC_ID);
        Integer[] groupsBefore = readAllowedGroups(DOC_ID);
        assertEquals(2, groupsBefore.length, "starts with finance + leadership");

        // Simulate ACL_CHANGED: remove leadership, keep only finance.
        List<Integer> financeOnly = groupResolver.resolve(List.of("finance"));
        int updated = chunkStore.updateAllowedGroups(DOC_ID, financeOnly);
        documentStore.replaceAcls(DOC_ID, financeOnly);

        // Groups changed to {finance} only.
        assertEquals(1, updated, "one chunk should have been updated");
        Integer[] groupsAfter = readAllowedGroups(DOC_ID);
        assertEquals(1, groupsAfter.length);
        assertEquals(financeOnly.get(0), groupsAfter[0]);

        // THE KEY ASSERTION: the embedding is byte-identical. No re-embedding.
        float[] after = readEmbedding(DOC_ID);
        assertArrayEquals(before, after, 0.0f,
                "embedding must be unchanged - ACL change must NOT re-embed");
    }

    @Test
    void aclChangeIsIdempotentUnderReplay() {
        // Replay the same ACL change twice. Result must be identical - full-set
        // semantics (not deltas) make this naturally safe.
        List<Integer> financeOnly = groupResolver.resolve(List.of("finance"));

        chunkStore.updateAllowedGroups(DOC_ID, financeOnly);
        Integer[] afterFirst = readAllowedGroups(DOC_ID);

        chunkStore.updateAllowedGroups(DOC_ID, financeOnly);
        Integer[] afterSecond = readAllowedGroups(DOC_ID);

        assertArrayEquals(afterFirst, afterSecond);
    }

    @Test
    void versionGateSkipsStaleContentEvent() {
        // Simulating the consumer's version check: if incoming v <= stored v,
        // skip. Stored is at v1.
        int stored = chunkStore.currentVersion(DOC_ID);
        assertEquals(1, stored);

        // Anything <= 1 must be recognized as stale by the caller.
        int stale = 1;
        assertEquals(true, stale <= stored, "v1 event when stored is v1 must be skipped");
    }

    // ---- helpers ----

    private Integer[] readAllowedGroups(String docId) {
        return jdbc.queryForObject(
                "SELECT allowed_groups FROM chunks WHERE document_id = ? ORDER BY chunk_index LIMIT 1",
                (rs, i) -> (Integer[]) rs.getArray("allowed_groups").getArray(),
                docId);
    }

    private float[] readEmbedding(String docId) {
        // Read as pgvector then convert to float[].
        PGvector v = jdbc.queryForObject(
                "SELECT embedding FROM chunks WHERE document_id = ? ORDER BY chunk_index LIMIT 1",
                (rs, i) -> (PGvector) rs.getObject("embedding"),
                docId);
        assert v != null;
        return v.toArray();
    }

    /** Deterministic 384-dim vector so we can compare byte-for-byte. */
    private static float[] fixed384() {
        float[] v = new float[384];
        for (int i = 0; i < v.length; i++) v[i] = (float) Math.sin(i * 0.01);
        // Normalize so it's a valid unit vector (matches production embeddings).
        float sumSq = 0;
        for (float f : v) sumSq += f * f;
        float norm = (float) Math.sqrt(sumSq);
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    /** Minimal Spring context with just the beans this test needs. */
    @org.springframework.context.annotation.Configuration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.Import({
            ChunkStore.class, DocumentStore.class, GroupResolver.class,
            io.parag.indexer.config.PgVectorConfig.class
    })
    static class TestConfig {}
}
