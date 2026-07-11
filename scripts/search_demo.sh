#!/usr/bin/env bash
# Day 6 demo: hit /search as each seed user and see permission-aware retrieval.
set -euo pipefail
BASE="${BASE:-http://localhost:8100}"
HERE="$(cd "$(dirname "$0")" && pwd)"

run() {
  local user="$1" q="$2" label="$3"
  echo
  echo "=== $label ==="
  echo "user:  $user"
  echo "query: $q"
  echo "---"
  curl -s -X POST "$BASE/search" \
    -H 'Content-Type: application/json' \
    -d "{\"user_email\":\"$user\",\"query\":\"$q\"}" \
    | python3 "$HERE/_format_search.py"
}

run "dev@corp.io"   "what are the salary bands for senior engineers"  "LEAK TEST: intern asks about salaries"
run "meera@corp.io" "what are the salary bands for senior engineers"  "SAME QUERY, but as CFO"
run "asha@corp.io"  "kafka consumer lag runbook"                       "Engineer asks a runbook question"
run "ravi@corp.io"  "parental leave policy"                            "HR manager asks an HR question"
run "dev@corp.io"   "dev environment setup"                            "Intern asks about onboarding (all-staff doc)"

echo
echo "Done."
