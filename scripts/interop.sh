#!/usr/bin/env bash
# Interop (Android plan, Task I2): the Android peer (JVM, apps/android/interop) and the iPhone/Mac peer
# (Swift, apps/apple/Interop) talk through one Go server. Starts a throwaway Postgres, the SMTP sink
# (tools/smtp-sink) and the server in server-readable mode (an escrow key is generated), all with generated
# config and nothing real, then drives both peers step by step. Every step prints "PASS <step>"; the first
# failure stops the run with "FAIL <step>: <why>". Everything is stopped on exit.
#
# The peers read one command per line on stdin and answer one line on stdout: "OK [values]" or "ERR <why>".
# A: Android signs up "alice_i2" (account A). B: the Swift peer signs up "bob_i2" (account B).
# Later two fresh devices link: a Swift device to A (Android approves) and an Android device to B.
#
# Environment (all optional):
#   INTEROP_PEER_B=swift|kotlin   the peer for B and for the device that links to A (default swift).
#                                 kotlin runs every side on the JVM peer: a check of this script and of the
#                                 Android peer, never a substitute for the Swift run.
#   On Linux the Swift peer has no CryptoKit: it seals escrow copies and link snapshots with the Rust HPKE
#   instead of the app's CryptoKit code, and the two photo steps are skipped. Only macOS runs it all.
#   INTEROP_PORT                  the server's port on 127.0.0.1 (default 18080; admin +1000, mail sink 12525)
#   INTEROP_WORK                  the work directory (default $RUNNER_TEMP/interop or a temp directory)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${INTEROP_WORK:-${RUNNER_TEMP:-$(mktemp -d)}/interop}"
PEER_B="${INTEROP_PEER_B:-swift}"
PORT="${INTEROP_PORT:-18080}"
ADMIN_PORT=$((PORT + 1000))
SMTP_PORT=12525
FIXTURES="$ROOT/apps/android/interop/fixtures"
STEP_TIMEOUT=120

case "$PEER_B" in swift|kotlin) ;; *) echo "INTEROP_PEER_B must be swift or kotlin" >&2; exit 2 ;; esac

rm -rf "$WORK"
mkdir -p "$WORK/data" "$WORK/mail" "$WORK/bin" "$WORK/peers"

PIDS=""
PGDATA="$WORK/pgdata"
# A Unix socket path is at most 107 bytes, so the socket gets a short directory of its own.
PGSOCK="$(mktemp -d /tmp/interop-pg.XXXXXX)"
PG_STARTED=0
cleanup() {
  status=$?
  # Closing the peers' stdin ends them; then everything else is stopped.
  for fd in 3 5 7 9; do eval "exec $fd>&-" 2>/dev/null || true; done
  for pid in $PIDS; do kill "$pid" 2>/dev/null || true; done
  if [ "$PG_STARTED" = 1 ]; then pg_ctl -D "$PGDATA" -m fast stop >/dev/null 2>&1 || true; fi
  rm -rf "$PGSOCK"
  if [ "$status" != 0 ]; then
    echo "--- appserver log (tail) ---" >&2
    tail -n 30 "$WORK/appserver.log" >&2 2>/dev/null || true
    for log in "$WORK"/peers/*.log; do
      [ -f "$log" ] || continue
      echo "--- $(basename "$log") (tail) ---" >&2
      tail -n 30 "$log" >&2 || true
    done
  fi
  exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL $1: $2"; exit 1; }
pass() { echo "PASS $1"; }

# 1. Postgres, listening on a Unix socket only.
if ! command -v initdb >/dev/null 2>&1; then
  if [ "$(uname)" = Darwin ]; then
    brew list postgresql@18 >/dev/null 2>&1 || brew install postgresql@18
    PATH="$(brew --prefix postgresql@18)/bin:$PATH"
  else
    for d in /usr/lib/postgresql/*/bin; do [ -x "$d/initdb" ] && PATH="$d:$PATH"; done
  fi
  export PATH
fi
initdb -D "$PGDATA" --auth=trust --encoding=UTF8 >/dev/null
pg_ctl -D "$PGDATA" -l "$WORK/postgres.log" -o "-k $PGSOCK -c listen_addresses=''" -w start >/dev/null
PG_STARTED=1
createdb -h "$PGSOCK" app

# 2. The server and the mail sink.
(cd "$ROOT/server" && go build -o "$WORK/bin/appserver" ./cmd/appserver)
(cd "$ROOT/tools/smtp-sink" && go build -o "$WORK/bin/smtp-sink" .)
"$WORK/bin/smtp-sink" -listen "127.0.0.1:$SMTP_PORT" -out "$WORK/mail" >"$WORK/smtp-sink.log" 2>&1 &
PIDS="$PIDS $!"

# 3. Throwaway config: new conversations are server-readable, so every message also has an escrow copy.
export APP_ENV_FILE=/dev/null
export APP_HOST=localhost
export APP_LISTEN="127.0.0.1:$PORT"
export APP_ADMIN_LISTEN="127.0.0.1:$ADMIN_PORT"
export APP_DATABASE_URL="postgres:///app?host=$PGSOCK"
export APP_SMTP_URL="smtp://127.0.0.1:$SMTP_PORT"
export APP_DATA_DIR="$WORK/data"
"$WORK/bin/appserver" migrate
"$WORK/bin/appserver" keygen escrow >"$WORK/escrow.pem"
cat >"$WORK/policy.json" <<'EOF'
{
  "requiresInvite": true,
  "defaultMode": "auto",
  "maxMode": "high",
  "defaultIPHiding": "nonContacts",
  "encryption": {"newConversations": "server_readable"},
  "abuseContact": "abuse@example.org",
  "retention": {"inboxDays": 30, "attachmentDays": 30, "escrowDays": 365},
  "limits": {"attachmentBytes": 104857600, "attachmentBytesPerDay": 1073741824}
}
EOF
export APP_ESCROW_KEY_FILE="$WORK/escrow.pem"
export APP_POLICY_FILE="$WORK/policy.json"
INVITE=$("$WORK/bin/appserver" invite create -uses 10 | sed 's#.*/invite/##')

"$WORK/bin/appserver" serve >"$WORK/appserver.log" 2>&1 &
PIDS="$PIDS $!"
ready=0
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$ADMIN_PORT/readyz" >/dev/null 2>&1; then ready=1; break; fi
  sleep 0.5
done
[ "$ready" = 1 ] || fail server "appserver did not become ready in 30 s"

# 4. The peers. Both use the host build of the Rust MLS library (mlsffi).
CRATE="$ROOT/apps/apple/Packages/SecureMessaging/mlsffi"
(cd "$CRATE" && cargo build --release --lib --quiet)
MLS_LIB_DIR="${CARGO_TARGET_DIR:-$CRATE/target}/release"

(cd "$ROOT/apps/android" && ./gradlew --quiet :interop:installDist)
KOTLIN_PEER="$ROOT/apps/android/interop/build/install/interop/bin/interop"
if [ "$PEER_B" = swift ]; then
  SWIFT_PEER="$("$ROOT/apps/apple/Interop/build.sh" "$MLS_LIB_DIR")"
fi

export INTEROP_BASE_URL="http://localhost:$PORT"
export INTEROP_HOST="localhost:$PORT"
export INTEROP_MAIL_DIR="$WORK/mail"
export INTEROP_INVITE="$INVITE"
export JAVA_OPTS="-Djna.library.path=$MLS_LIB_DIR"

# start <name> <kind> <write fd> <read fd>: the peer reads a FIFO on fd <write fd> and answers on <read fd>.
start() {
  local name=$1 kind=$2 wfd=$3 rfd=$4
  local dir="$WORK/peers/$name"
  mkdir -p "$dir/data"
  mkfifo "$dir/in" "$dir/out"
  if [ "$kind" = kotlin ]; then
    INTEROP_DATA="$dir/data" INTEROP_DEVICE="$name" "$KOTLIN_PEER" <"$dir/in" >"$dir/out" 2>"$WORK/peers/$name.log" &
  else
    INTEROP_DATA="$dir/data" INTEROP_DEVICE="$name" "$SWIFT_PEER" <"$dir/in" >"$dir/out" 2>"$WORK/peers/$name.log" &
  fi
  PIDS="$PIDS $!"
  eval "exec $wfd>\"\$dir/in\""
  eval "exec $rfd<\"\$dir/out\""
}

# The fds of each peer: A (Android, account A), B (account B), N1 (fresh, links to A), N2 (fresh, links to B).
fds() {
  case $1 in
    A) echo "3 4" ;; B) echo "5 6" ;; N1) echo "7 8" ;; N2) echo "9 10" ;;
  esac
}

# tell <peer> <command...>: sends one command without waiting for the answer.
tell() {
  local p=$1; shift
  set -- $(fds "$p") "$@"
  local wfd=$1; shift 2
  printf '%s\n' "$*" >&"$wfd"
}

# answer <peer> <step>: reads the peer's answer; "OK ..." sets ANSWER to the values, anything else fails <step>.
answer() {
  local p=$1 step=$2 rfd line
  rfd=$(fds "$p" | cut -d' ' -f2)
  if ! IFS= read -r -t "$STEP_TIMEOUT" -u "$rfd" line; then fail "$step" "peer $p did not answer"; fi
  case "$line" in
    OK) ANSWER="" ;;
    "OK "*) ANSWER="${line#OK }" ;;
    *) fail "$step" "peer $p: $line" ;;
  esac
}

# ask <peer> <step> <command...>: tell, then answer.
ask() {
  local p=$1 step=$2; shift 2
  tell "$p" "$@"
  answer "$p" "$step"
}

# same <step> <what> <a> <b>
same() { [ "$3" = "$4" ] || fail "$1" "$2 differ: '$3' vs '$4'"; }

start A kotlin 3 4
start B "$PEER_B" 5 6
EMOJI=$(printf '\xf0\x9f\x91\x8d')

# Sign-up: Android signs up A, the other peer signs up B.
ask A signup signup alice_i2 alice-i2@example.org Alice
ACC_A=$ANSWER
ask B signup signup bob_i2 bob-i2@example.org Bob
ACC_B=$ANSWER
[ -n "$ACC_A" ] && [ -n "$ACC_B" ] && [ "$ACC_A" != "$ACC_B" ] || fail signup "account IDs '$ACC_A' '$ACC_B'"
pass signup

# Each adds the other by username.
ask A contacts add bob_i2
same contacts "B's account ID" "$ANSWER" "$ACC_B"
ask B contacts add alice_i2
same contacts "A's account ID" "$ANSWER" "$ACC_A"
pass contacts

# Android -> other: text.
ask A text send "$ACC_B" hello from android
MSG_A=$ANSWER
ask B text wait-text "$ACC_A" hello from android
same text "message IDs" "$ANSWER" "$MSG_A"
pass text

# Other -> Android: a reply, a reaction and an edit.
ask B reply reply "$ACC_A" "$MSG_A" hi from apple
MSG_B=$ANSWER
ask A reply wait-reply "$ACC_B" "$MSG_A" hi from apple
same reply "message IDs" "$ANSWER" "$MSG_B"
pass reply
ask B reaction react "$ACC_A" "$MSG_A" "$EMOJI"
ask A reaction wait-reaction "$ACC_B" "$MSG_A" "$EMOJI"
pass reaction
ask B edit edit "$ACC_A" "$MSG_B" hi from apple, edited
ask A edit wait-edit "$ACC_B" "$MSG_B" hi from apple, edited
pass edit

# Delivered and read receipts both ways.
ask A delivered wait-status "$ACC_B" "$MSG_A" delivered
ask B delivered wait-status "$ACC_A" "$MSG_B" delivered
pass delivered
ask B read read "$ACC_A"
ask A read wait-status "$ACC_B" "$MSG_A" read
ask A read read "$ACC_B"
ask B read wait-status "$ACC_A" "$MSG_B" read
pass read

# A photo attachment both ways: the receiver's decrypted bytes equal the sender's. On Linux the Swift peer has
# no CryptoKit, so it can neither encrypt nor decrypt an attachment: those two steps are skipped there.
if [ "$PEER_B" = swift ] && [ "$(uname)" != Darwin ]; then
  echo "SKIP photo-android-to-other (the Swift peer needs CryptoKit: macOS only)"
  echo "SKIP photo-other-to-android (the Swift peer needs CryptoKit: macOS only)"
else
  ask A photo-android-to-other send-photo "$ACC_B" "$FIXTURES/photo.jpg"
  set -- $ANSWER; SENT_ID=$1; SENT_SHA=$2
  ask B photo-android-to-other wait-photo "$ACC_A" "$SENT_ID"
  same photo-android-to-other "photo SHA-256" "$ANSWER" "$SENT_SHA"
  pass "photo-android-to-other (the Android peer sends the file as it is: the app's PhotoPrep is not exercised)"
  ask B photo-other-to-android send-photo "$ACC_A" "$FIXTURES/photo.jpg"
  set -- $ANSWER; SENT_ID=$1; SENT_SHA=$2
  ask A photo-other-to-android wait-photo "$ACC_B" "$SENT_ID"
  same photo-other-to-android "photo SHA-256" "$ANSWER" "$SENT_SHA"
  pass photo-other-to-android
fi

# Server-readable mode: the escrow copies from each side open with the admin export.
ask A escrow conversation "$ACC_B"
CONV=$ANSWER
ask B escrow conversation "$ACC_A"
same escrow "conversation IDs" "$ANSWER" "$CONV"
"$WORK/bin/appserver" escrow export --conversation "$CONV" --reason "interop test" >"$WORK/escrow.jsonl" \
  || fail escrow "escrow export failed"
grep -q "\"senderAccountID\":\"$ACC_A\"" "$WORK/escrow.jsonl" || fail escrow "no escrow copy from A"
grep -q "\"senderAccountID\":\"$ACC_B\"" "$WORK/escrow.jsonl" || fail escrow "no escrow copy from B"
grep -q "hello from android" "$WORK/escrow.jsonl" || fail escrow "A's text is not in the export"
grep -q "hi from apple" "$WORK/escrow.jsonl" || fail escrow "B's text is not in the export"
pass escrow

# Safety numbers are equal on both sides.
ask A safety-number safety "$ACC_B"
SAFETY_A=$ANSWER
ask B safety-number safety "$ACC_A"
[ -n "$SAFETY_A" ] || fail safety-number "empty"
same safety-number "safety numbers" "$ANSWER" "$SAFETY_A"
pass "safety-number (identities only: Android's memberKeys are fake-sig placeholders until MLS M7)"

# Call signaling: each side seals a ring and an offer with a recorded SDP; the other opens it and the offer
# passes the fingerprint check.
call_steps() {
  local from=$1 to=$2 from_acc=$3 to_acc=$4 sdp=$5 name=$6 call_id
  call_id="call-$name-$$"
  ask "$from" "call-ring-$name" seal "$to_acc" ring "$call_id"
  ask "$to" "call-ring-$name" open "$from_acc" "$ANSWER"
  same "call-ring-$name" "opened ring" "$ANSWER" "ring $call_id"
  pass "call-ring-$name"
  ask "$from" "call-offer-$name" seal "$to_acc" offer "$call_id" "$sdp"
  ask "$to" "call-offer-$name" open "$from_acc" "$ANSWER"
  same "call-offer-$name" "opened offer" "$ANSWER" "offer $call_id fingerprint-ok"
  pass "call-offer-$name"
}
if [ "$PEER_B" = swift ]; then OTHER_SDP="$FIXTURES/ios-webrtc-offer.sdp"; else OTHER_SDP="$FIXTURES/chrome-offer.sdp"; fi
call_steps A B "$ACC_A" "$ACC_B" "$FIXTURES/chrome-offer.sdp" android-to-other
call_steps B A "$ACC_B" "$ACC_A" "$OTHER_SDP" other-to-android

# Linking: Android approves a new device of the other platform (it joins A), and the other platform approves
# a new Android device (it joins B). Android's account flow imports the snapshot (its contacts then include the
# peer); the iPhone's does not yet (plan 7), so for a Swift device only the snapshot's arrival is checked.
# check_link <step> <expected account> <expected contact>: reads ANSWER = "<accountID> imported|received <contacts>".
check_link() {
  set -- "$1" "$2" "$3" $ANSWER
  same "$1" "linked account" "$4" "$2"
  case "${5:-}" in
    imported)
      case ",${6:-}," in *",$3,"*) ;; *) fail "$1" "snapshot contacts '${6:-}' miss $3" ;; esac
      pass "$1"
      ;;
    received)
      pass "$1 (linked; snapshot sealed, sent and opened)"
      echo "SKIP $1-snapshot-import (iPhone app does not import the link snapshot yet, plan 7)"
      ;;
    *) fail "$1" "unexpected answer '$ANSWER'" ;;
  esac
}
start N1 "$PEER_B" 7 8
ask N1 link-android-approves link-code
QR=$ANSWER
tell N1 link-wait
ask A link-android-approves approve "$QR"
answer N1 link-android-approves
check_link link-android-approves "$ACC_A" "$ACC_B"

start N2 kotlin 9 10
ask N2 link-other-approves link-code
QR=$ANSWER
tell N2 link-wait
ask B link-other-approves approve "$QR"
answer N2 link-other-approves
check_link link-other-approves "$ACC_B" "$ACC_A"

for p in A B N1 N2; do tell "$p" quit; done
echo "interop: all steps passed ($PEER_B peer for B)"
