#!/usr/bin/env bash
# Day 7 demo: hit /ask as each seed user and see grounded answers with
# citations, or clean refusals when nothing is visible.
#
# Requires ANTHROPIC_API_KEY to be set in the query service's env.
set -euo pipefail
BASE="${BASE:-http://localhost:8100}"
HERE="$(cd "$(dirname "$0")" && pwd)"

ask() {
  local user="$1" q="$2" label="$3"
  echo
  echo "=== $label ==="
  echo "user:  $user"
  echo "query: $q"
  echo "---"
  curl -s -X POST "$BASE/ask" \
    -H 'Content-Type: application/json' \
    -d "{\"user_email\":\"$user\",\"query\":\"$q\"}" \
    | python3 "$HERE/_format_ask.py"
}

# THE HEADLINE COMPARISON.
ask "dev@corp.io"   "what are the salary bands for senior engineers"  "LEAK TEST: intern asks about salaries (must REFUSE)"
ask "meera@corp.io" "what are the salary bands for senior engineers"  "SAME QUERY, but as CFO (real answer with citations)"

# Grounded answers on visible docs.
ask "asha@corp.io"  "how do I fix kafka consumer lag?"                 "Engineer asks a runbook question"
ask "ravi@corp.io"  "how many weeks of parental leave do primary caregivers get?"  "HR manager asks an HR question"
ask "dev@corp.io"   "how do I set up my dev environment?"              "Intern asks about onboarding (all-staff doc)"

# Nothing visible on this topic - refusal.
ask "dev@corp.io"   "what is our Q3 revenue forecast?"                 "Intern asks about Q3 forecast (must REFUSE, don't hint)"

echo
echo "Done."
