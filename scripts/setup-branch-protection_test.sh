#!/usr/bin/env bash
# Tests for setup-branch-protection.sh (--dry-run only; never calls the GitHub API).
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fails=0
check() { if "${@:2}"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

out="$(GH_TOKEN= sh "$here/setup-branch-protection.sh" --dry-run 2>/dev/null)"; rc=$?
check "dry-run exits 0" test "$rc" = 0
check "dry-run prints a ruleset that matches the plan" python3 -I -c '
import json, sys
r = json.loads(sys.argv[1])
assert r["name"] == "main-protection", r["name"]
assert r["target"] == "branch" and r["enforcement"] == "active"
assert r["conditions"]["ref_name"]["include"] == ["~DEFAULT_BRANCH"]
assert r["bypass_actors"] == []
rules = {x["type"]: x.get("parameters", {}) for x in r["rules"]}
for t in ("deletion", "non_fast_forward", "required_linear_history", "pull_request", "required_status_checks"):
    assert t in rules, t
pr = rules["pull_request"]
assert pr["required_approving_review_count"] == 1
assert pr["require_code_owner_review"] is True
assert pr["dismiss_stale_reviews_on_push"] is True
checks = sorted(c["context"] for c in rules["required_status_checks"]["required_status_checks"])
assert checks == ["app-id", "check", "gitleaks", "test"], checks
assert all(c["integration_id"] == 15368 for c in rules["required_status_checks"]["required_status_checks"])
' "$out"
check "unknown flag rejected" sh -c '! sh "$1" --bogus >/dev/null 2>&1' _ "$here/setup-branch-protection.sh"

echo "fails=$fails"
exit "$fails"
