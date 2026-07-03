"""Central configuration. Everything overridable via environment / .env."""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # --- Postgres ---
    database_url: str = "postgresql+psycopg://parag:parag_dev@localhost:5432/parag"

    # --- Kafka / Schema Registry ---
    kafka_bootstrap: str = "localhost:9092"
    schema_registry_url: str = "http://localhost:8081"
    doc_events_topic: str = "doc-events"
    indexer_group_id: str = "parag-indexer"

    # --- Embeddings ---
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    embedding_dim: int = 384

    # --- Chunking ---
    chunk_size_tokens: int = 350
    chunk_overlap_tokens: int = 50

    # --- Retrieval ---
    vector_candidates: int = 30   # top-k from vector search (pre-merge)
    keyword_candidates: int = 30  # top-k from full-text search (pre-merge)
    rrf_k: int = 60               # RRF damping constant
    final_top_k: int = 6          # chunks handed to the LLM

    # --- Generation ---
    anthropic_api_key: str = ""
    generation_model: str = "claude-sonnet-4-6"

    class Config:
        env_file = ".env"


settings = Settings()
