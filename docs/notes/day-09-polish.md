# Day 9 — Polish: The 90-Second Reviewer Experience

Days 1–8 built and proved the project. Day 9 makes the project *legible* to
someone who has 90 seconds.

## Who this day is for

Not you (the builder), not an interviewer (who sees the whole story in a
call). This day is for the **recruiter or hiring manager who lands on the
GitHub URL** — clicks in, reads the top of the README, glances at the
screenshots, decides "worth a callback" or "next."

That decision is made in about 90 seconds. Everything I changed in the
README is optimized for those 90 seconds.

## What changed in the README

1. **The lead paragraph** now says what the project *is* and *does*, in one
   sentence, without jargon. Same product category as products they've heard
   of (Rovo/Glean/Copilot) so they place it instantly.

2. **The intern-vs-CFO block** is now visible without scrolling — a code
   fence with the two queries and two answers side by side. A reader sees
   "same question, different answer, permissions are working" in 10 seconds.

3. **The two leak-suite screenshots** (red-then-green) are embedded right
   after that block. The visual proof of the security claim is above the
   fold. This is the single strongest artifact in the repo.

4. **The architecture diagram** was already there but got tightened. Kafka
   → indexer → Postgres → query service, with the permission filter's
   location called out visually. Same information as before, cleaner shape.

5. **"Not in scope"** at the bottom is new. Deliberately naming what you
   didn't build reads as senior — it says "I made scoping choices, here they
   are." Sounds counterintuitive but recruiters at senior levels look for
   this.

## D1. Why the "not in scope" section matters

A 10-day portfolio project that pretends to be production-ready is a red
flag. A 10-day project that clearly names its boundaries and shows judgment
about what to build in the time reads as senior. Anyone hiring a senior
engineer wants someone who knows what NOT to build, and who can articulate
the trade-off out loud.

The specific items listed (real connectors, scale, reranking, group
hierarchy) are each things a real production version would need. Naming them
tells the reader "I know what a production version would look like — I chose
to prove the core mechanism first."

## D2. Why the screenshots go above the fold

GitHub's README renders inline. A reader lands, sees maybe the first
screen-height without scrolling. Everything in that first screen is
disproportionately valuable. Order matters:

1. **What is this** (one sentence)
2. **What problem** (one paragraph)
3. **The demo** (code fence — proof of behavior)
4. **The proof** (red-then-green screenshots — proof of testability)
5. **Architecture** (diagram — proof of design)

The ordering answers the reviewer's implicit questions in the order they'd
ask them: "what is it?" → "why?" → "does it work?" → "can I trust it?" →
"how is it built?"

## D3. What still isn't polished (and it's OK)

The commit history isn't rewritten. Some commits still say "Screenshots" or
"added tests." That's fine — the README is what a stranger reads; the commit
log is what a senior interviewer might browse *if they liked the README*. In
that order. Improve the top of the funnel first.

## Defense questions

1. What in the README is above the fold on a laptop screen? Why does that
   ordering matter?
2. The "Not in scope" section names four things (real connectors, scale,
   reranking, group hierarchy). Pick one and articulate the trade-off you
   made in one sentence.
3. A recruiter reads the top of the README, doesn't scroll, and closes the
   tab. What did they take away? What did they miss?
4. Why does this project use `Atlassian Rovo, Glean, Microsoft 365 Copilot`
   as a reference triangle in the first sentence? What's the mechanism by
   which that helps a reader?

## Your tasks

1. **Preview the new README on GitHub** — this is the actual test:
   ```bash
   git add README.md docs/notes/day-09-polish.md
   git commit -m "Day 9: README rewrite - reviewer-first ordering, embed leak-suite screenshots"
   git push origin HEAD:main
   ```
   Then open https://github.com/praveen098/permission-aware-rag in your
   browser and read it as if you'd never seen it. Do you understand what the
   project is in the first paragraph? Do the screenshots land? Do you scroll
   because you want to, or because you have to?

2. **Send it to one person who is NOT a technical friend** — a family member,
   a college friend in a different field. Ask them: "in one sentence, what
   does this project do?" If they can't answer, the first paragraph needs
   more work. If they can, the README is doing its job.

3. **Optional but high-ROI: rename the two accidentally-named files still
   floating around**. Search for anything that looks amateurish:
   ```bash
   ls scripts/ | grep -E "\.py$" | sort
   git log --oneline | head -20
   ```
   Nothing to fix if it looks clean.

