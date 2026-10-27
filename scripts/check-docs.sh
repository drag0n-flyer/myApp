#!/usr/bin/env bash
# Docs checks, standard tools only. Usage: scripts/check-docs.sh [DIR...]
# Checks, per Markdown file under each DIR (default: docs):
#   * relative links point to existing files
# docs/legal also: the lawyer-review banner is present, and every bullet of
# server design section 9 appears (case-insensitive) in privacy-policy.md section 2.
set -u
cd "$(dirname "$0")/.."
status=0
dirs=("$@"); [ ${#dirs[@]} -eq 0 ] && dirs=(docs)
BANNER='> **Draft — needs lawyer review before use.**'
DESIGN=docs/superpowers/specs/2026-10-27-server-design.md
for d in "${dirs[@]}"; do
  while IFS= read -r f; do
    dir=$(dirname "$f")
    while IFS= read -r link; do
      link=${link%%#*}
      case "$link" in ''|http*|mailto:*|/*) continue ;; esac
      [ -e "$dir/$link" ] || { echo "$f: broken link $link"; status=1; }
    done < <(grep -o ']([^)]*)' "$f" | sed 's/^](//; s/)$//')
  done < <(find "$d" -name '*.md' | sort)
done
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
exit $status
