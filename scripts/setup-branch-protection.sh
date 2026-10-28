#!/bin/sh
# Creates or updates the `main-protection` ruleset on the default branch.
# Idempotent: finds the ruleset by name and updates it, or creates it once.
#
#   sh scripts/setup-branch-protection.sh --dry-run   # print the ruleset JSON, call nothing
#   sh scripts/setup-branch-protection.sh             # apply (owner only; see docs/ops/repo-setup.md)
#
# REPO=owner/name overrides the repository (default: the one `gh` sees here).
# Required checks are the job names our workflows produce today:
#   test (server.yml), app-id (apple.yml), check (docs.yml), gitleaks (security.yml).
# 15368 is the GitHub Actions app, so only Actions can satisfy them.
set -eu

NAME="main-protection"
dry_run=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) dry_run=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

ruleset() {
  cat <<EOF
{
  "name": "$NAME",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "bypass_actors": [],
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "required_linear_history" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 1,
        "require_code_owner_review": true,
        "dismiss_stale_reviews_on_push": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
    } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [
          { "context": "test", "integration_id": 15368 },
          { "context": "app-id", "integration_id": 15368 },
          { "context": "check", "integration_id": 15368 },
          { "context": "gitleaks", "integration_id": 15368 }
        ]
    } }
  ]
}
EOF
}

if [ "$dry_run" = 1 ]; then
  ruleset
  exit 0
fi

command -v gh >/dev/null 2>&1 || { echo "gh is required" >&2; exit 1; }
repo="${REPO:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"

id="$(gh api "repos/$repo/rulesets" --paginate --jq ".[] | select(.name == \"$NAME\") | .id" | head -n 1)"
if [ -n "$id" ]; then
  ruleset | gh api -X PUT "repos/$repo/rulesets/$id" --input - >/dev/null
  echo "updated ruleset $NAME ($id) on $repo"
else
  ruleset | gh api -X POST "repos/$repo/rulesets" --input - >/dev/null
  echo "created ruleset $NAME on $repo"
fi
