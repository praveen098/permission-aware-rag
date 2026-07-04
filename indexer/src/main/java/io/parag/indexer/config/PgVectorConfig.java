package io.parag.indexer.config;

import com.pgvector.PGvector;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * Registers the pgvector type mapping so PGvector objects bind to the `vector`
 * column. Without this, inserting an embedding throws "unknown type vector".
 *
 * pgvector's helper registers per-connection; we do it once at startup against a
 * connection from the pool. For a production pool you'd register on every checked-
 * out connection (a datasource wrapper) - noted in docs/notes/day-03 as a known
 * simplification acceptable for this project's single-writer indexer.
 */
@Component
public class PgVectorConfig {

    private final DataSource dataSource;

    public PgVectorConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void registerVectorType() {
        try (Connection conn = dataSource.getConnection()) {
            PGvector.addVectorType(conn);
        } catch (Exception e) {
            throw new IllegalStateException("failed to register pgvector type", e);
        }
    }
}
