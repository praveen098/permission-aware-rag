package io.parag.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * PARAG indexer service.
 *
 * Consumes document-lifecycle events from the {@code doc-events} Kafka topic
 * (Avro, via Confluent Schema Registry), chunks document bodies, obtains
 * embeddings from the Python embedding service, and upserts chunks into
 * Postgres/pgvector - keeping the denormalized {@code allowed_groups} in sync
 * so permission changes propagate without re-embedding.
 *
 * The @KafkaListener that ties this together is written on Day 4-5.
 */
@SpringBootApplication
public class IndexerApplication {
    public static void main(String[] args) {
        SpringApplication.run(IndexerApplication.class, args);
    }
}
