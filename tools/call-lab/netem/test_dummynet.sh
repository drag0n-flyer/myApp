#!/bin/sh
# Tests for dummynet.sh. Dry-run only: never runs dnctl or pfctl, needs no root, runs on Linux.
dir=$(cd "$(dirname "$0")" && pwd)
S="$dir/dummynet.sh"
fail=0; total=0
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
# The pf reference token from `pfctl -E` is kept here between apply and clear.
DUMMYNET_TOKEN_FILE="$tmp/token"; export DUMMYNET_TOKEN_FILE

check() { # name expected actual
    total=$((total + 1))
    if [ "$2" = "$3" ]; then echo "ok   $1"; else
        echo "FAIL $1"; printf 'expected:\n%s\nactual:\n%s\n' "$2" "$3"; fail=$((fail + 1))
    fi
}

# On lo0 a packet between two local processes leaves once (out) and arrives once (in). Only the out
# pass is shaped, so each packet goes through exactly one pipe. The mask gives every sending socket
# its own dynamic pipe, so each call direction has its own delay, loss and rate.
test_apply_loss5_one_pipe_per_packet() {
    exp='pfctl -E
pfctl -f /etc/pf.conf
dnctl pipe 1 config delay 40 plr 0.05 mask src-ip 0xffffffff src-port 0xffff
pfctl -a com.apple/call-lab -f -
  dummynet out quick on lo0 proto udp all pipe 1'
    check apply_loss5_one_pipe_per_packet "$exp" "$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null)"
}

test_each_packet_exactly_one_pipe() {
    for mode in "" relay; do
        out=$(sh "$S" --dry-run apply lo0 loss5 $mode 2>/dev/null)
        rules=$(echo "$out" | grep -c 'dummynet ')
        outs=$(echo "$out" | grep -c 'dummynet out ')
        ins=$(echo "$out" | grep -c 'dummynet in ')
        pipes=$(echo "$out" | grep -c '^dnctl pipe ')
        masked=$(echo "$out" | grep '^dnctl pipe ' | grep -c 'mask src-ip 0xffffffff src-port 0xffff$')
        check "each_packet_exactly_one_pipe(${mode:-direct})" "1 1 0 1 1" "$rules $outs $ins $pipes $masked"
    done
}

# Relay: media goes A -> coturn -> B, three lo0 packets (client to 3478, coturn relay to relay,
# 3478 to client). Only the clients' sends to 3478 are shaped, so each direction is shaped once.
test_relay_shapes_only_client_sends_to_turn() {
    r=$(sh "$S" --dry-run apply lo0 loss5 relay 2>/dev/null | grep 'dummynet ')
    check relay_shapes_only_client_sends_to_turn "  dummynet out quick on lo0 proto udp to any port 3478 pipe 1" "$r"
}

test_bad_mode() {
    out=$(sh "$S" --dry-run apply lo0 loss5 sideways 2>&1); rc=$?
    check bad_mode "2 unknown mode: sideways (direct or relay)" "$rc $out"
}

test_apply_has_plr_and_delay() {
    out=$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null)
    n=$(echo "$out" | grep '^dnctl pipe' | grep -c 'plr 0.05' )
    d=$(echo "$out" | grep '^dnctl pipe' | grep -c 'delay 40 ')
    check apply_has_plr_and_delay "1 1" "$n $d"
}

test_rate_only_when_capped() {
    a=$(sh "$S" --dry-run apply lo0 cap32 2>/dev/null | grep -c 'bw 32Kbit/s')
    b=$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null | grep -c ' bw ')
    check rate_only_when_capped "1 0" "$a $b"
}

test_clean_profile_has_no_loss() {
    out=$(sh "$S" --dry-run apply lo0 clean 2>/dev/null | grep '^dnctl pipe 1')
    check clean_profile_has_no_loss "dnctl pipe 1 config delay 0 plr 0 mask src-ip 0xffffffff src-port 0xffff" "$out"
}

test_jitter_is_noted_not_applied() {
    err=$(sh "$S" --dry-run apply lo0 loss5 2>&1 >/dev/null)
    check jitter_is_noted_not_applied "note: jitter not simulated (dummynet has no jitter); profile loss5 jitter 5 ms is not applied" "$err"
}

test_clear() {
    rm -f "$DUMMYNET_TOKEN_FILE"
    exp='dnctl -q flush
pfctl -a com.apple/call-lab -F all'
    check clear "$exp" "$(sh "$S" --dry-run clear 2>&1)"
}

# The main ruleset (/etc/pf.conf, with its dummynet-anchor "com.apple/*" line) must be loaded
# before the anchor rules, or the anchor is never evaluated and nothing is shaped.
test_main_ruleset_loaded_before_anchor() {
    out=$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null)
    main=$(echo "$out" | grep -n '^pfctl -f /etc/pf.conf$' | cut -d: -f1)
    anc=$(echo "$out" | grep -n '^pfctl -a com.apple/call-lab -f -$' | cut -d: -f1)
    [ -n "$main" ] && [ -n "$anc" ] && [ "$main" -lt "$anc" ] && r=yes || r=no
    check main_ruleset_loaded_before_anchor yes "$r"
}

# apply keeps the token `pfctl -E` prints; clear releases exactly that reference.
test_token_kept_and_released() {
    mkdir -p "$tmp/bin"
    printf '#!/bin/sh\ncase "$1" in -E) echo "pf enabled" >&2; echo "Token : 4242" >&2;; esac\nexit 0\n' > "$tmp/bin/pfctl"
    printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/dnctl"
    printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/ifconfig"
    chmod +x "$tmp/bin/"*
    rm -f "$DUMMYNET_TOKEN_FILE"
    PATH="$tmp/bin:$PATH" sh "$S" apply lo0 loss5 >/dev/null 2>&1
    check token_kept "4242" "$(cat "$DUMMYNET_TOKEN_FILE" 2>/dev/null)"
    out=$(sh "$S" --dry-run clear 2>&1)
    check token_released 'dnctl -q flush
pfctl -a com.apple/call-lab -F all
pfctl -X 4242' "$out"
}

# dnctl's per-queue rows are garbage on macOS 26 (fixture: the real `dnctl pipe show 1` from the
# runner, 2026-10-28), so `packets` counts with pf's own counter on the anchor's dummynet rule.
fake_tools() { # pfctl -s dummynet output file
    mkdir -p "$tmp/bin"
    printf '#!/bin/sh\ncat "%s"\n' "$dir/fixtures/dnctl-pipe-show-macos26.txt" > "$tmp/bin/dnctl"
    printf '#!/bin/sh\ncase "$*" in *"-s dummynet"*) cat "%s";; esac\nexit 0\n' "$1" > "$tmp/bin/pfctl"
    chmod +x "$tmp/bin/dnctl" "$tmp/bin/pfctl"
}

test_packets_from_pf_rule_counter() {
    cat > "$tmp/pf-shaped" <<'OUT'
dummynet out quick on lo0 proto udp all pipe 1
  [ Evaluations: 25345     Packets: 25345     Bytes: 3254400     States: 0     ]
  [ Inserted: uid 0 pid 36410 ]
OUT
    fake_tools "$tmp/pf-shaped"
    check packets_from_pf_rule_counter 25345 "$(PATH="$tmp/bin:$PATH" sh "$S" packets 2>&1)"
    PATH="$tmp/bin:$PATH" sh "$S" check-shaped >/dev/null 2>&1; check check_shaped_passes 0 "$?"
}

# Negative: shaping not applied (rule never matched, or no rule in the anchor) must fail.
test_check_shaped_fails_when_not_applied() {
    cat > "$tmp/pf-idle" <<'OUT'
dummynet out quick on lo0 proto udp all pipe 1
  [ Evaluations: 812       Packets: 0         Bytes: 0           States: 0     ]
OUT
    fake_tools "$tmp/pf-idle"
    check packets_zero_when_rule_idle 0 "$(PATH="$tmp/bin:$PATH" sh "$S" packets 2>&1)"
    out=$(PATH="$tmp/bin:$PATH" sh "$S" check-shaped 2>&1); rc=$?
    check check_shaped_fails_idle "1 shaping not applied: 0 packets matched the dummynet rule" "$rc $out"
    : > "$tmp/pf-empty"
    fake_tools "$tmp/pf-empty"
    PATH="$tmp/bin:$PATH" sh "$S" check-shaped >/dev/null 2>&1; check check_shaped_fails_no_rule 1 "$?"
}

# dnctl can fail once with ENOMEM on a fresh runner (run 40738133728); apply retries the pipe
# config a few times, and still fails when it keeps failing.
test_pipe_config_retried() {
    mkdir -p "$tmp/bin"
    printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/pfctl"; printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/ifconfig"
    printf '#!/bin/sh\nexit 0\n' > "$tmp/bin/sleep"
    cat > "$tmp/bin/dnctl" <<EOS
#!/bin/sh
n=\$(cat "$tmp/dn" 2>/dev/null || echo 0); n=\$((n + 1)); echo \$n > "$tmp/dn"
[ \$n -ge \$(cat "$tmp/dn-ok") ] && exit 0
echo "dnctl: setsockopt(IP_DUMMYNET_CONFIGURE): Cannot allocate memory" >&2; exit 71
EOS
    chmod +x "$tmp/bin/"*
    echo 2 > "$tmp/dn-ok"; rm -f "$tmp/dn"
    PATH="$tmp/bin:$PATH" sh "$S" apply lo0 loss5 >/dev/null 2>&1; rc=$?
    check pipe_config_retried "0 2" "$rc $(cat "$tmp/dn")"
    echo 99 > "$tmp/dn-ok"; rm -f "$tmp/dn"
    PATH="$tmp/bin:$PATH" sh "$S" apply lo0 loss5 >/dev/null 2>&1; rc=$?
    [ "$rc" -ne 0 ] && r=nonzero || r=zero
    check pipe_config_gives_up "nonzero 3" "$r $(cat "$tmp/dn")"
    rm -f "$tmp/bin/sleep"
}

test_unknown_profile() {
    out=$(sh "$S" --dry-run apply lo0 nope 2>&1); rc=$?
    check unknown_profile "2 unknown profile: nope" "$rc $out"
}

test_bad_interface_name() {
    out=$(sh "$S" --dry-run apply 'a b' loss5 2>&1); rc=$?
    check bad_interface_name "2 bad interface name: a b" "$rc $out"
}

test_usage() {
    out=$(sh "$S" --dry-run apply lo0 2>&1); rc=$?
    check usage "64" "$rc"
}

test_executable() {
    [ -x "$S" ] && r=yes || r=no
    check executable yes "$r"
}

test_apply_loss5_one_pipe_per_packet
test_each_packet_exactly_one_pipe
test_relay_shapes_only_client_sends_to_turn
test_bad_mode
test_apply_has_plr_and_delay
test_rate_only_when_capped
test_clean_profile_has_no_loss
test_jitter_is_noted_not_applied
test_clear
test_main_ruleset_loaded_before_anchor
test_token_kept_and_released
test_packets_from_pf_rule_counter
test_check_shaped_fails_when_not_applied
test_pipe_config_retried
test_unknown_profile
test_bad_interface_name
test_usage
test_executable
if [ "$fail" -eq 0 ]; then echo "PASS $total/$total"; else echo "FAIL $((total - fail))/$total"; exit 1; fi
