"""
PARAG Query Service.

Permission-aware hybrid retrieval. Takes a user email + query, resolves the
user's groups, and runs a SINGLE SQL query that combines:

  1. Vector similarity (pgvector HNSW, cosine distance)
  2. Keyword full-text search (Postgres tsvector / ts_rank)
  3. The PERMISSION PRE-FILTER: allowed_groups && :user_group_ids

The filter is inside both retrieval branches - so the ranker only ever ranks
over the user's visible universe. Restricted chunks don't get scored and
dropped; they never enter the candidate set at all.

Results from vector and keyword are merged with Reciprocal Rank Fusion (RRF).
Rank-based, so we don't have to normalize cosine similarity against ts_rank.

Endpoint:
  POST /search  { "user_email": "...", "query": "..." }
       -> { "user": {...}, "chunks": [ {document_id, chunk_index, content,
             score, allowed_groups}, ... ] }

Run:
  pip install -r requirements.txt
  uvicorn app:app --port 8100
"""
from __future__ import annotations

import os
from contextlib import asynccontextmanager
from typing import Any

import httpx
import psycopg
from fastapi import FastAPI, HTTPException
from pgvector.psycopg import register_vector
from psycopg.rows import dict_row
from pydantic import BaseModel, Field

# ---- Config ----
DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://parag:parag_dev@localhost:5432/parag",
)
EMBEDDING_URL = os.getenv("EMBEDDING_URL", "http://localhost:8000")

VECTOR_CANDIDATES = 30    # top-k from vector search before merge
KEYWORD_CANDIDATES = 30   # top-k from full-text search before merge
RRF_K = 60                # damping constant (standard is 60)
FINAL_TOP_K = 6           # chunks returned to caller

# ---- Connection ----
_conn: psycopg.Connection | None = None
_http: httpx.AsyncClient | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _conn, _http
    _conn = psycopg.connect(DATABASE_URL, autocommit=True, row_factory=dict_row)
    register_vector(_conn)              # binds pgvector's `vector` <-> Python list
    _http = httpx.AsyncClient(timeout=30.0)
    yield
    if _conn: _conn.close()
    if _http: await _http.aclose()


app = FastAPI(title="PARAG Query Service", lifespan=lifespan)


# ---- Models ----
class SearchRequest(BaseModel):
    user_email: str = Field(..., examples=["dev@corp.io"])
    query: str = Field(..., min_length=1, examples=["what are the salary bands?"])


class Chunk(BaseModel):
    document_id: str
    chunk_index: int
    content: str
    score: float
    allowed_groups: list[int]
    vector_rank: int | None = None
    keyword_rank: int | None = None


class UserInfo(BaseModel):
    email: str
    display_name: str
    group_ids: list[int]
    group_names: list[str]


class SearchResponse(BaseModel):
    user: UserInfo
    query: str
    chunks: list[Chunk]


# ---- Core retrieval ----
def resolve_user(email: str) -> dict[str, Any]:
    """Look up user id + groups. Returns None if user doesn't exist."""
    with _conn.cursor() as cur:
        cur.execute(
            """
            SELECT u.id, u.email, u.display_name,
                   COALESCE(array_agg(g.id ORDER BY g.id) FILTER (WHERE g.id IS NOT NULL), '{}') AS group_ids,
                   COALESCE(array_agg(g.name ORDER BY g.id) FILTER (WHERE g.name IS NOT NULL), '{}') AS group_names
            FROM users u
            LEFT JOIN user_groups ug ON ug.user_id = u.id
            LEFT JOIN groups g ON g.id = ug.group_id
            WHERE u.email = %s
            GROUP BY u.id
            """,
            (email,),
        )
        row = cur.fetchone()
        return row  # None if not found


async def embed(text: str) -> list[float]:
    resp = await _http.post(f"{EMBEDDING_URL}/embed", json={"texts": [text]})
    resp.raise_for_status()
    return resp.json()["embeddings"][0]


def hybrid_retrieve(query_text: str, query_vec: list[float], group_ids: list[int]) -> list[dict]:
    """
    ONE SQL statement, two ranked lists, merged in Python by RRF.

    Both branches carry the permission filter `allowed_groups && %(groups)s::int[]`
    so restricted chunks never enter the candidate set. This is the pre-filter
    guarantee: ranking happens only over the user's visible universe.
    """
    sql = """
    WITH
      vec AS (
        SELECT id, document_id, chunk_index, content, allowed_groups,
               ROW_NUMBER() OVER (ORDER BY embedding <=> %(qvec)s::vector) AS rnk
        FROM chunks
        WHERE allowed_groups && %(groups)s::int[]   -- PERMISSION PRE-FILTER
        ORDER BY embedding <=> %(qvec)s::vector
        LIMIT %(vec_k)s
      ),
      kw AS (
        SELECT id, document_id, chunk_index, content, allowed_groups,
               ROW_NUMBER() OVER (ORDER BY ts_rank(tsv, plainto_tsquery('english', %(qtext)s)) DESC) AS rnk
        FROM chunks
        WHERE allowed_groups && %(groups)s::int[]   -- PERMISSION PRE-FILTER (same filter, both branches)
          AND tsv @@ plainto_tsquery('english', %(qtext)s)
        ORDER BY ts_rank(tsv, plainto_tsquery('english', %(qtext)s)) DESC
        LIMIT %(kw_k)s
      )
    SELECT
      COALESCE(v.id, k.id)                    AS id,
      COALESCE(v.document_id, k.document_id)  AS document_id,
      COALESCE(v.chunk_index, k.chunk_index)  AS chunk_index,
      COALESCE(v.content, k.content)          AS content,
      COALESCE(v.allowed_groups, k.allowed_groups) AS allowed_groups,
      v.rnk AS vector_rank,
      k.rnk AS keyword_rank
    FROM vec v
    FULL OUTER JOIN kw k USING (id)
    """
    with _conn.cursor() as cur:
        cur.execute(sql, {
            "qvec": query_vec,
            "qtext": query_text,
            "groups": group_ids,
            "vec_k": VECTOR_CANDIDATES,
            "kw_k": KEYWORD_CANDIDATES,
        })
        return cur.fetchall()


def rrf_merge(candidates: list[dict]) -> list[dict]:
    """Reciprocal Rank Fusion: score(d) = sum(1 / (k + rank_i)) over both branches."""
    scored = []
    for c in candidates:
        s = 0.0
        if c["vector_rank"] is not None:
            s += 1.0 / (RRF_K + c["vector_rank"])
        if c["keyword_rank"] is not None:
            s += 1.0 / (RRF_K + c["keyword_rank"])
        c2 = dict(c)
        c2["score"] = s
        scored.append(c2)
    scored.sort(key=lambda r: r["score"], reverse=True)
    return scored[:FINAL_TOP_K]


# ---- HTTP ----
@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/search", response_model=SearchResponse)
async def search(req: SearchRequest) -> SearchResponse:
    user = resolve_user(req.user_email)
    if not user:
        raise HTTPException(status_code=404, detail=f"unknown user: {req.user_email}")

    group_ids = list(user["group_ids"])
    if not group_ids:
        # User has no groups -> no visible chunks. Return empty cleanly.
        return SearchResponse(
            user=UserInfo(
                email=user["email"], display_name=user["display_name"],
                group_ids=[], group_names=list(user["group_names"]),
            ),
            query=req.query, chunks=[],
        )

    query_vec = await embed(req.query)
    raw = hybrid_retrieve(req.query, query_vec, group_ids)
    merged = rrf_merge(raw)

    return SearchResponse(
        user=UserInfo(
            email=user["email"], display_name=user["display_name"],
            group_ids=group_ids, group_names=list(user["group_names"]),
        ),
        query=req.query,
        chunks=[
            Chunk(
                document_id=c["document_id"],
                chunk_index=c["chunk_index"],
                content=c["content"],
                score=round(c["score"], 6),
                allowed_groups=list(c["allowed_groups"]),
                vector_rank=c["vector_rank"],
                keyword_rank=c["keyword_rank"],
            )
            for c in merged
        ],
    )
