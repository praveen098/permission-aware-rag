"""Shared fixtures and pytest wiring for the leak-test suite."""
from __future__ import annotations

import os

import httpx
import pytest


QUERY_SERVICE = os.getenv("QUERY_SERVICE", "http://127.0.0.1:8100")

# Restricted docs from the seed corpus. Anyone NOT in finance/hr/leadership
# must never surface these.
RESTRICTED_DOCS = {"CONF-2002", "CONF-3001", "JIRA-905"}

# The canonical refusal string /ask returns when nothing is visible.
REFUSAL_TEXT = (
    "I don't have information on that in the documents you have access to."
)


def pytest_addoption(parser):
    parser.addoption(
        "--ask",
        action="store_true",
        default=False,
        help="Also run /ask tests (slower, requires OPENAI_API_KEY).",
    )


def pytest_collection_modifyitems(config, items):
    if config.getoption("--ask"):
        return
    skip_ask = pytest.mark.skip(reason="need --ask to run (uses OpenAI API)")
    for item in items:
        if "ask" in item.keywords:
            item.add_marker(skip_ask)


@pytest.fixture(scope="session")
def http() -> httpx.Client:
    with httpx.Client(base_url=QUERY_SERVICE, timeout=30.0) as client:
        # Fail fast with a clear message if the service isn't up.
        try:
            r = client.get("/health")
            r.raise_for_status()
        except Exception as e:
            pytest.exit(
                f"query service not reachable at {QUERY_SERVICE}: {e}\n"
                f"start it before running the suite.",
                returncode=2,
            )
        yield client


def search(http: httpx.Client, user_email: str, query: str) -> dict:
    r = http.post("/search", json={"user_email": user_email, "query": query})
    r.raise_for_status()
    return r.json()


def ask(http: httpx.Client, user_email: str, query: str) -> dict:
    r = http.post("/ask", json={"user_email": user_email, "query": query})
    r.raise_for_status()
    return r.json()
