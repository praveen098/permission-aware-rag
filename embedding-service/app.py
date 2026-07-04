"""
PARAG embedding service.

A deliberately tiny FastAPI app whose only job is: text in -> vectors out.
Kept separate from the Java indexer so the (CPU-heavy) embedding tier can scale
independently of the (I/O-heavy) Kafka consumer. See docs/notes/day-02 D2.

Model: sentence-transformers/all-MiniLM-L6-v2 -> 384-dimensional vectors,
normalized so cosine similarity == dot product (matches the pgvector
vector_cosine_ops index on the chunks table).

Run:
    pip install -r requirements.txt
    uvicorn app:app --host 0.0.0.0 --port 8000
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field
from sentence_transformers import SentenceTransformer

MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2"
EMBEDDING_DIM = 384

# Loaded once at startup (model load is slow; requests must not pay for it).
_model: SentenceTransformer | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    _model = SentenceTransformer(MODEL_NAME)
    yield
    _model = None


app = FastAPI(title="PARAG Embedding Service", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: list[str] = Field(..., min_length=1)


class EmbedResponse(BaseModel):
    model: str
    dim: int
    embeddings: list[list[float]]


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model": MODEL_NAME, "loaded": _model is not None}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest) -> EmbedResponse:
    # normalize_embeddings=True => unit vectors => cosine == dot product.
    vectors = _model.encode(
        req.texts,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    return EmbedResponse(
        model=MODEL_NAME,
        dim=EMBEDDING_DIM,
        embeddings=[v.tolist() for v in vectors],
    )
