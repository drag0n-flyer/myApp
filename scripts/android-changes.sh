#!/usr/bin/env bash
# Reads changed file paths on stdin, prints one "<module>=true|false" line per Android
# module (and "all"), including modules that depend on a changed one.
# A path outside apps/android/<module>/ that affects the build (root Gradle files,
# the workflow, the Android scripts, the mlsffi crate) marks everything changed.
set -euo pipefail

MODULES=(contracts core net auth mls messaging store calls calls-android webrtc push app)
# module:dependents (direct); closed transitively below
DEPENDENTS=(
  "contracts:core net auth mls messaging store calls calls-android webrtc push app"
  "core:messaging app"
  "net:messaging push app"
  "auth:push app"
  "mls:messaging app"
  "messaging:store push app"
  "store:messaging app"
  "calls:calls-android app"
  "webrtc:calls-android app"
  "calls-android:app"
  "push:app"
)

declare -A changed=()
all=false
while IFS= read -r path; do
  [ -n "$path" ] || continue
  case "$path" in
    apps/android/*/*)
      rest="${path#apps/android/}"; mod="${rest%%/*}"
      case " ${MODULES[*]} " in
        *" $mod "*) changed[$mod]=1 ;;
        *) all=true ;;   # gradle/, other top-level directories
      esac ;;
    apps/android/*|.github/workflows/android.yml|scripts/android-*.sh|scripts/build-mlsffi-android.sh|scripts/check-16kb.sh|scripts/check-android-bundle*.sh|scripts/generate-oss-notices.py|scripts/license-texts/*|docs/security/license-allowlist.md|apps/apple/Packages/SecureMessaging/mlsffi/*) all=true ;;
  esac
done

if [ "$all" = true ]; then for m in "${MODULES[@]}"; do changed[$m]=1; done; fi

# transitive closure
again=1
while [ "$again" = 1 ]; do
  again=0
  for entry in "${DEPENDENTS[@]}"; do
    src="${entry%%:*}"; deps="${entry#*:}"
    [ -n "${changed[$src]:-}" ] || continue
    for d in $deps; do
      if [ -z "${changed[$d]:-}" ]; then changed[$d]=1; again=1; fi
    done
  done
done

any=false
for m in "${MODULES[@]}"; do
  if [ -n "${changed[$m]:-}" ]; then v=true; any=true; else v=false; fi
  echo "${m//-/_}=$v"
done
echo "all=$all"
echo "any=$any"
