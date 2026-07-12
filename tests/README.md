# Leak-Test Suite

The executable version of PARAG's core promise: **restricted content never
reaches an unauthorized user**, no matter the query, and no LLM ever sees it.

These tests hit a running `query-service` (`/search` and `/ask`) with the seed
corpus indexed. They target every restricted document (`CONF-2002`,
`CONF-3001`, `JIRA-905`) with the most adversarial queries a caller could
plausibly send:

- Direct queries by content ("salary bands", "Q3 forecast").
- Prompt-injection attempts embedded in the query.
- Semantically similar but not-exact wording ("compensation", "revenue outlook").

For each unauthorized user (Dev the intern, plus edge cases), we assert:

1. `/search` returns **zero** chunks from any restricted document.
2. `/ask` returns `refused: True` **and** an empty `citations` list.
3. The answer is the exact canned refusal string — no partial leaks, no
   "3 documents were filtered" hints.

For each authorized user (Meera the CFO), we assert the same queries return
the expected chunks — proving the filter isn't over-blocking either.

## Running

```bash
# Prereqs: Docker stack + embedding service + query service (with OPENAI_API_KEY)
# all running. Corpus indexed at the current ACL state.
pip install pytest httpx
cd tests
pytest -v
```

`pytest -v --ask` also runs the (slower, costs API credits) `/ask` assertions.
Without the flag, only the free `/search` assertions run.

## What "green" means

If this suite passes, PARAG's security story is proven, not just demoed. If it
ever goes red, ship halted — a green leak suite is the gate before any code
change that touches retrieval, generation, or the ACL model.
