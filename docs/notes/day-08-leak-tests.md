# Day 8 — The Leak-Test Suite: Executable Security Proof

The demo on Day 7 showed a leak *not* happening. This suite proves it doesn't
happen — programmatically, across 48 assertions, in about 10 seconds.

That single green run is what turns "here's a cool demo" into "here's a
project with a testable security claim." It's the artifact reviewers pause on.

## What got built

- **`tests/test_permission_leaks.py`** — parameterized suite over
  (unauthorized user × adversarial query). Every combination asserts zero
  restricted documents in the response.
- **`tests/conftest.py`** — shared fixtures, HTTP client, and the `--ask`
  opt-in flag that gates the API-cost tests.
- **`tests/README.md`** — the runbook for "what does green mean, when do we
  ship-block on red."

## D1. Why parameterized, not one big test

pytest's `@pytest.mark.parametrize` explodes one test into N: 2 users × 11
adversarial queries × 2 assertions per pair = 44 named tests, plus 4 fixed
non-regression tests = 48 total. When one fails, the test name tells you
exactly which user + which query broke:

```
FAILED test_search_never_returns_restricted_docs[what are the salary bands...-dev@corp.io]
```

That's the difference between "something leaked, dig in the debugger" and
"Dev leaked on the salary-bands probe, look at that combination first."
Parametrization is the *log resolution* of your test suite.

## D2. Two invariants, not one

The suite asserts two independent things about `/search` results:

1. **No restricted-doc IDs appear** — the specific-leak assertion.
2. **Every returned chunk's `allowed_groups` overlaps the user's groups** —
   the general-invariant assertion.

Why both? (1) is easy to write and catches the exact leaks we care about
today. (2) catches leaks we haven't imagined yet — including from *new*
documents added later. If a new restricted doc is introduced and (1) doesn't
know its name, (2) still catches any chunk that's leaking.

**Interview line:** "I test the property, not just the examples."

## D3. Non-regression: also proving we don't over-block

`test_search_authorized_user_sees_restricted_docs` asserts that Meera (CFO)
*does* get CONF-2002 when she asks about compensation. Without this, "zero
leaks" is trivially satisfied by returning nothing for everyone. Over-block
is a real failure mode — if a naive fix (say, dropping every doc that ever
had `leadership` in its ACL) passes the leak test but breaks legitimate
access, we'd never know.

Security tests without corresponding permission tests are incomplete. Say
that.

## D4. `test_unknown_user_is_404` catches a subtle leak

If querying with an unknown email returned empty results, that would be
indistinguishable from "user exists but has no visible docs" — silently
telling a probing attacker whether an email exists in our identity table.
Returning 404 makes user-existence a separate signal that requires an
authenticated check to see. Small detail, real-world security concern.

## D5. Why the `/ask` tests are opt-in

`/ask` tests hit OpenAI. Every run costs a fraction of a cent, but on CI it
accumulates and you don't want the leak suite to be a hidden cost driver.
The `--ask` flag makes cost explicit: default runs are free, developers add
the flag when they want the end-to-end assertion.

Same principle any production team follows: fast, free, deterministic tests
in the default gate; slower or costlier suites opt-in.

## Defense questions

1. Why parameterize over adversarial queries instead of one test with a
   for-loop? Name a specific debugging failure mode the parameterized version
   handles better.
2. `test_search_chunks_respect_user_groups` asserts a general invariant
   (`allowed_groups & user_groups is non-empty`) rather than checking against
   the list of known restricted docs. Give one leak scenario the invariant
   catches that a doc-list assertion would miss.
3. Why do we test that Meera *gets* CONF-2002, not just that Dev *doesn't*?
   Give a concrete regression scenario the CFO-side test catches.
4. `/ask` tests are behind a `--ask` flag. Suggest one production trade-off
   this design pattern (fast+free default, expensive opt-in) trades away.

Q2 is the *senior-tier* interview question of this project: it's the
difference between test-what-you-know and test-the-property.

## Your tasks

1. **Make sure the query service is up** with the corpus indexed and
   `OPENAI_API_KEY` set (if running `--ask`).

2. **Install pytest and run the free suite:**
   ```bash
   cd tests
   pip install -r requirements.txt
   pytest -v
   ```
   Expected: `48 passed`, taking under 30 seconds.

3. **Watch a failure look like it should.** In one terminal (BEFORE running
   the tests again), publish an ACL_CHANGED that would leak CONF-3001 to
   everyone:
   ```bash
   cd scripts && source .venv/bin/activate
   python emit_event.py acl-grant   # adds all-staff to CONF-3001
   ```
   Then re-run the suite. You should see Dev's queries about "revenue
   forecast" fail. THIS is the point of the suite — a permission mistake
   surfaces immediately, with a specific failing test name. Revoke:
   ```bash
   python emit_event.py acl-revoke
   ```

4. **Optionally run `/ask` tests** (uses ~6 API calls, well under a cent):
   ```bash
   pytest -v --ask
   ```

5. Commit:
   ```
   Day 8: leak-test suite - executable security proof

   - 48 parametrized assertions across unauthorized users x adversarial queries
   - two invariants: no restricted doc IDs surface + every returned chunk's
     allowed_groups overlaps user_groups (catches leaks from unseen docs)
   - non-regression: CFO must actually get restricted docs (over-block guard)
   - unknown-user test asserts 404 (avoids user-existence side channel)
   - opt-in --ask flag for LLM-hitting assertions (cost-explicit)
   ```
