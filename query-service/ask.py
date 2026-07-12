"""
PARAG /ask endpoint — grounded generation with citations, refusal-safe.

Same retrieval path as /search, then either refuse cleanly (no LLM call) or
hand the permitted chunks to OpenAI for a grounded answer with [DOC-ID]
citations.

Requires OPENAI_API_KEY in the environment.
"""
from __future__ import annotations

import os
from openai import OpenAI
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app import (
    embed,
    hybrid_retrieve,
    resolve_user,
    rrf_merge,
    Chunk,
    UserInfo,
)

OPENAI_MODEL = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
_openai: OpenAI | None = None


def _client() -> OpenAI:
    global _openai
    if _openai is None:
        api_key = os.getenv("OPENAI_API_KEY")
        if not api_key:
            raise HTTPException(
                status_code=500,
                detail="OPENAI_API_KEY not set; /ask disabled. "
                       "Set it in the query-service env and restart.",
            )
        _openai = OpenAI(api_key=api_key)
    return _openai


router = APIRouter()


class AskRequest(BaseModel):
    user_email: str = Field(..., examples=["dev@corp.io"])
    query: str = Field(..., min_length=1)


class AskResponse(BaseModel):
    user: UserInfo
    query: str
    answer: str
    refused: bool = Field(
        ...,
        description="True if no permitted chunks matched or the model refused.",
    )
    citations: list[Chunk] = Field(default_factory=list)


SYSTEM_PROMPT = """You are an internal knowledge assistant. Answer the user's \
question using ONLY the numbered document excerpts provided below. Rules:

1. Base every claim on the excerpts. Do not use prior knowledge.
2. Cite the source of each claim inline as [DOC-ID] (e.g. [CONF-1002]).
3. If the excerpts do not contain enough information to answer, reply exactly: \
"I don't have information on that in the documents you have access to." \
Do NOT speculate, and do NOT hint at documents you can't see.
4. Keep answers under 5 sentences unless the question demands more.
"""


def _format_chunks_for_prompt(chunks: list[dict]) -> str:
    parts = []
    for i, c in enumerate(chunks, start=1):
        parts.append(
            f"[{i}] Document: {c['document_id']} (chunk {c['chunk_index']})\n"
            f"{c['content']}\n"
        )
    return "\n".join(parts)


REFUSAL_TEXT = (
    "I don't have information on that in the documents you have access to."
)


@router.post("/ask", response_model=AskResponse)
async def ask(req: AskRequest) -> AskResponse:
    user = resolve_user(req.user_email)
    if not user:
        raise HTTPException(status_code=404, detail=f"unknown user: {req.user_email}")

    group_ids = list(user["group_ids"])
    user_info = UserInfo(
        email=user["email"],
        display_name=user["display_name"],
        group_ids=group_ids,
        group_names=list(user["group_names"]),
    )

    if not group_ids:
        return AskResponse(
            user=user_info, query=req.query, answer=REFUSAL_TEXT,
            refused=True, citations=[],
        )

    query_vec = await embed(req.query)
    raw = hybrid_retrieve(req.query, query_vec, group_ids)
    merged = rrf_merge(raw)

    # REFUSAL PATH: skip the LLM entirely if no chunks were permitted.
    if not merged:
        return AskResponse(
            user=user_info, query=req.query, answer=REFUSAL_TEXT,
            refused=True, citations=[],
        )

    # SYNTHESIS PATH: hand visible chunks to OpenAI.
    context = _format_chunks_for_prompt(merged)
    user_msg = f"Question: {req.query}\n\nDocument excerpts:\n{context}"

    resp = _client().chat.completions.create(
        model=OPENAI_MODEL,
        max_tokens=300,
        messages=[
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
        ],
    )
    answer = (resp.choices[0].message.content or "").strip()
    refused = REFUSAL_TEXT.lower() in answer.lower()

    citations = [
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
    ]

    return AskResponse(
        user=user_info, query=req.query, answer=answer,
        refused=refused, citations=citations,
    )
