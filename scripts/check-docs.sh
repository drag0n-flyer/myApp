#!/usr/bin/env bash
# Docs checks. Usage: scripts/check-docs.sh [PATH...]   (files or directories; default: docs)
# Set ONLINE=1 to also check external links (weekly CI job); default is offline.
# Runs in order, stopping at the first failing step:
#   1. markdownlint-cli2 (npx, pinned)
#   2. lychee: relative links and anchors (needs the lychee binary on PATH)
#   3. docs/legal checks (below), when a docs/legal directory is given
#   4. scripts/check-threat-model.py, when that file exists
# docs/legal also: the lawyer-review banner is present, and every bullet of
# server design section 9 appears (case-insensitive) in privacy-policy.md section 2.
set -u
cd "$(dirname "$0")/.."
status=0
dirs=("$@"); [ ${#dirs[@]} -eq 0 ] && dirs=(docs)
step() { echo "== $*"; }
md_globs=()
for d in "${dirs[@]}"; do
  if [ -d "$d" ]; then md_globs+=("${d%/}/**/*.md"); else md_globs+=("$d"); fi
done
step markdownlint
npx --yes markdownlint-cli2@0.18.1 "${md_globs[@]}" 2>&1 || exit 1
step lychee
if ! command -v lychee >/dev/null; then echo "lychee not found on PATH"; exit 1; fi
lychee_args=(--no-progress --config lychee.toml)
[ "${ONLINE:-0}" = 1 ] || lychee_args+=(--offline)
lychee "${lychee_args[@]}" "${dirs[@]}" || exit 1
step legal
BANNER='> **Draft — needs lawyer review before use.**'
DESIGN=docs/superpowers/specs/2026-10-27-server-design.md
for d in "${dirs[@]}"; do
  case "$d" in docs/legal|docs/legal/) ;; *) continue ;; esac
  for f in docs/legal/privacy-policy.md docs/legal/terms.md; do
    grep -qF "$BANNER" "$f" || { echo "$f: banner missing"; status=1; }
  done
  sec2=$(awk '/^## 2\./{f=1;next} /^## /{f=0} f' docs/legal/privacy-policy.md | tr 'A-Z' 'a-z')
  bullets=$(awk '/^## 9\. /{f=1;next} /^## /{f=0} f && /^- /{sub(/^- /,"");print}' "$DESIGN")
  [ -n "$bullets" ] || { echo "no bullets found in $DESIGN section 9"; status=1; }
  while IFS= read -r b; do
    [ -z "$b" ] && continue
    printf '%s' "$sec2" | grep -qiF -- "$b" || { echo "privacy-policy.md section 2 lacks design section 9 bullet: $b"; status=1; }
  done <<< "$bullets"
done
[ $status -eq 0 ] || exit $status
if [ -f scripts/check-threat-model.py ]; then
  step threat-model
  python3 scripts/check-threat-model.py || exit 1
fi
exit 0
