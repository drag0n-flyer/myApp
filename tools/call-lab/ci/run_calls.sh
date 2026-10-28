#!/bin/sh
# CI test calls (L10a), on one macOS runner. Results are "CI, simulated", never device results.
# Boots coturn and the lab server (APP_LAB_MODE=1), then for each matrix row: applies the dummynet
# profile on lo0, captures lo0, runs two headless CallLabMac processes (callee, then caller with
# the stimulus), counts bytes with pcapbytes.py, runs the score step, and clears the profile.
#
# Usage: run_calls.sh --app <CallLabMac.app> --server <appserver> --stimulus <wav> --out <runs-dir>
#                     [--noise <wav>] [--dry-run]
# Env:   APP_DATABASE_URL (required unless --dry-run), LAB_SECONDS (call length, default 300).
# Needs passwordless sudo for dnctl, pfctl and tcpdump (GitHub's macOS runners have it).
#
# Client B is a second CallLabMac process (`--headless --role callee`), not CallLabiOS in the
# Simulator: the Simulator has no headless launch path into a call and no way to inject the
# stimulus. The choice is written to each run's meta.json as client_b.
#
# Matrix (preset path profile noise_snr_db). The brief's full matrix (2 presets x 2 paths x 4
# profiles + 3 noise runs = 19 calls of 5 minutes) does not fit timeout-minutes 60, so runs are
# cut, never the cap: 7 calls (~37 minutes) plus build and setup. The noise runs are cut as well;
# they need the 11 GB MUSAN archive on the runner.
MATRIX='ultraLow direct clean -
balanced direct clean -
ultraLow relay clean -
balanced relay clean -
ultraLow direct loss5 -
balanced direct cap32 -
balanced direct 3g -'

here=$(cd "$(dirname "$0")" && pwd)
lab=$(cd "$here/.." && pwd)
dry=0; app=; server=; stimulus=; out=; noise=
while [ $# -gt 0 ]; do
    case "$1" in
    --app) app=$2; shift 2 ;;
    --server) server=$2; shift 2 ;;
    --stimulus) stimulus=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --noise) noise=$2; shift 2 ;;
    --dry-run) dry=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
    esac
done
[ -n "$app" ] && [ -n "$server" ] && [ -n "$stimulus" ] && [ -n "$out" ] || {
    echo "usage: run_calls.sh --app <CallLabMac.app> --server <appserver> --stimulus <wav> --out <dir> [--noise <wav>] [--dry-run]" >&2
    exit 64
}
seconds=${LAB_SECONDS:-300}
bin="$app/Contents/MacOS/CallLabMac"

if [ "$dry" -eq 1 ]; then
    echo "$MATRIX" | while read -r preset path profile snr; do
        echo "call $preset $path $profile noise=$snr ${seconds}s"
        sh "$lab/netem/dummynet.sh" --dry-run apply lo0 "$profile" 2>/dev/null | sed 's/^/  /'
    done
    exit 0
fi
[ -n "$APP_DATABASE_URL" ] || { echo "APP_DATABASE_URL is required" >&2; exit 64; }
[ -x "$bin" ] || { echo "no app binary at $bin" >&2; exit 2; }

tmp=$(mktemp -d)
mkdir -p "$out"
pids=
cleanup() {
    for p in $pids; do kill "$p" 2>/dev/null; done
    sudo sh "$lab/netem/dummynet.sh" clear >/dev/null 2>&1
    sudo pkill -f "tcpdump -i lo0" 2>/dev/null
}
trap cleanup EXIT INT TERM

ip=$(ipconfig getifaddr en0 2>/dev/null || true)
locals=127.0.0.1${ip:+,$ip}

# The TURN secret is made fresh for this run and never leaves the runner.
secret=$(openssl rand -hex 24)
turnserver -n --listening-ip=127.0.0.1 --relay-ip=127.0.0.1 --listening-port=3478 \
    --use-auth-secret --static-auth-secret="$secret" --realm=lab.invalid \
    --no-tls --no-dtls --no-cli --allow-loopback-peers --simple-log --log-file="$tmp/turn.log" >/dev/null 2>&1 &
pids="$pids $!"

export APP_HOST=localhost APP_LISTEN=127.0.0.1:8080 APP_ADMIN_LISTEN=127.0.0.1:9090
export APP_DATA_DIR="$tmp/data" APP_TURN_SECRET="$secret" APP_RELAYS=127.0.0.1 APP_LAB_MODE=1
mkdir -p "$APP_DATA_DIR"
"$server" migrate || { echo "migrate failed" >&2; exit 1; }
"$server" serve >"$tmp/server.log" 2>&1 &
pids="$pids $!"
i=0
until curl -fsS http://127.0.0.1:9090/healthz >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -gt 60 ] && { echo "server did not start" >&2; tail -20 "$tmp/server.log" >&2; exit 1; }
    sleep 1
done

# "accountID deviceID token" for each client.
set -- $("$server" lab session --username labcaller --device ci-a --platform mac)
tok_a=$3
set -- $("$server" lab session --username labcallee --device ci-b --platform mac)
acct_b=$1; tok_b=$3
[ -n "$tok_a" ] && [ -n "$tok_b" ] || { echo "lab session failed" >&2; exit 1; }
unset APP_TURN_SECRET

# Waits for pid $1 at most $2 seconds; kills it after that. Returns its exit status (124 on timeout).
wait_for() {
    n=0
    while kill -0 "$1" 2>/dev/null; do
        n=$((n + 1))
        if [ "$n" -gt "$2" ]; then kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; return 124; fi
        sleep 1
    done
    wait "$1"
}

n=0
status=0
echo "$MATRIX" > "$tmp/matrix"
while read -r preset path profile snr; do
    n=$((n + 1))
    name=$(printf '%02d-%s-%s-%s' "$n" "$preset" "$path" "$profile")
    stim=$stimulus
    if [ "$snr" != "-" ]; then
        name="$name-snr$snr"
        [ -n "$noise" ] || { echo "skip $name: no --noise clip" >&2; continue; }
        stim="$tmp/stim-snr$snr.wav"
        python3 "$lab/corpus/mix_noise.py" --clean "$stimulus" --noise "$noise" --snr "$snr" --out "$stim" || continue
    fi
    run="$out/$name"; mkdir -p "$run"
    echo "== $name"
    if [ "$path" = relay ]; then filter='udp port 3478'; legs=2
    else filter='udp and not port 3478 and not port 53 and not port 5353'; legs=1; fi

    sudo sh "$lab/netem/dummynet.sh" apply lo0 "$profile" </dev/null || { status=1; continue; }
    sudo tcpdump -i lo0 -U -s 128 -w "$tmp/$name.pcap" "$filter" </dev/null 2>"$tmp/tcpdump.log" &
    sleep 2

    LAB_HOST=http://127.0.0.1:8080 LAB_TOKEN=$tok_b LAB_SECONDS=$seconds \
        "$bin" --headless --role callee --preset "$preset" --path "$path" --out "$run/callee" </dev/null >"$run/callee.log" 2>&1 &
    callee=$!
    sleep 3
    LAB_HOST=http://127.0.0.1:8080 LAB_TOKEN=$tok_a LAB_PEER=$acct_b LAB_SECONDS=$seconds \
        "$bin" --headless --role caller --preset "$preset" --path "$path" --stimulus "$stim" --out "$run/caller" </dev/null >"$run/caller.log" 2>&1 &
    caller=$!
    wait_for "$caller" $((seconds + 180)); rc_a=$?
    wait_for "$callee" 60; rc_b=$?
    echo "caller exit $rc_a, callee exit $rc_b"

    sudo pkill -INT -f "tcpdump -i lo0" </dev/null 2>/dev/null; sleep 2
    sudo sh "$lab/netem/dummynet.sh" clear </dev/null >/dev/null

    for f in stats.csv events.csv; do [ -f "$run/caller/$f" ] && cp "$run/caller/$f" "$run/$f"; done
    if [ -f "$tmp/$name.pcap" ]; then
        sudo chown "$(id -u)" "$tmp/$name.pcap"
        python3 "$lab/pcap/pcapbytes.py" "$tmp/$name.pcap" --local "$locals" --udp-only >"$run/bytes.json" || rm -f "$run/bytes.json"
        rm -f "$tmp/$name.pcap"
    fi
    # Score step: needs client B's received.wav. No virtual audio device records client B's
    # output on the runner yet, so the scores stay empty (report only) until one is approved.
    if [ -f "$run/callee/received.wav" ]; then
        python3 "$lab/scoring/score.py" visqol --ref "$stim" --deg "$run/callee/received.wav" >"$run/visqol.json" || rm -f "$run/visqol.json"
        python3 "$lab/scoring/score.py" dnsmos --in "$run/callee/received.wav" >"$run/dnsmos.json" || rm -f "$run/dnsmos.json"
    else
        echo "no received.wav: speech and noise scores not measured" >"$run/score.log"
    fi
    snr_json=null; [ "$snr" != "-" ] && snr_json=$snr
    printf '{"run_id": "%s", "preset": "%s", "path": "%s", "profile": "%s", "noise_snr_db": %s, "client_b": "mac-headless", "capture_legs": %s, "caller_exit": %s, "callee_exit": %s, "label": "CI, simulated"}\n' \
        "$name" "$preset" "$path" "$profile" "$snr_json" "$legs" "$rc_a" "$rc_b" >"$run/meta.json"
done < "$tmp/matrix"
cp "$tmp/server.log" "$out/server.log" 2>/dev/null
grep -v -i secret "$tmp/turn.log" 2>/dev/null | tail -200 >"$out/turn.log"
exit "$status"
