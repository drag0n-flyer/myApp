#!/bin/sh
# Tests for dummynet.sh. Dry-run only: never runs dnctl or pfctl, needs no root, runs on Linux.
dir=$(cd "$(dirname "$0")" && pwd)
S="$dir/dummynet.sh"
fail=0; total=0

check() { # name expected actual
    total=$((total + 1))
    if [ "$2" = "$3" ]; then echo "ok   $1"; else
        echo "FAIL $1"; printf 'expected:\n%s\nactual:\n%s\n' "$2" "$3"; fail=$((fail + 1))
    fi
}

test_apply_loss5_pipes_and_both_directions() {
    exp='dnctl pipe 1 config delay 40 plr 0.05
dnctl pipe 2 config delay 40 plr 0.05
pfctl -a com.apple/call-lab -f -
  dummynet out quick on lo0 all pipe 1
  dummynet in quick on lo0 all pipe 2
pfctl -E'
    check apply_loss5_pipes_and_both_directions "$exp" "$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null)"
}

test_apply_has_plr_and_delay() {
    out=$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null)
    n=$(echo "$out" | grep '^dnctl pipe' | grep -c 'plr 0.05' )
    d=$(echo "$out" | grep '^dnctl pipe' | grep -c 'delay 40 ')
    check apply_has_plr_and_delay "2 2" "$n $d"
}

test_rate_only_when_capped() {
    a=$(sh "$S" --dry-run apply lo0 cap32 2>/dev/null | grep -c 'bw 32Kbit/s')
    b=$(sh "$S" --dry-run apply lo0 loss5 2>/dev/null | grep -c ' bw ')
    check rate_only_when_capped "2 0" "$a $b"
}

test_clean_profile_has_no_loss() {
    out=$(sh "$S" --dry-run apply lo0 clean 2>/dev/null | grep '^dnctl pipe 1')
    check clean_profile_has_no_loss "dnctl pipe 1 config delay 0 plr 0" "$out"
}

test_jitter_is_noted_not_applied() {
    err=$(sh "$S" --dry-run apply lo0 loss5 2>&1 >/dev/null)
    check jitter_is_noted_not_applied "note: dummynet has no jitter; profile loss5 jitter 5 ms is not applied" "$err"
}

test_clear() {
    exp='dnctl -q flush
pfctl -a com.apple/call-lab -F all'
    check clear "$exp" "$(sh "$S" --dry-run clear 2>&1)"
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

test_apply_loss5_pipes_and_both_directions
test_apply_has_plr_and_delay
test_rate_only_when_capped
test_clean_profile_has_no_loss
test_jitter_is_noted_not_applied
test_clear
test_unknown_profile
test_bad_interface_name
test_usage
test_executable
if [ "$fail" -eq 0 ]; then echo "PASS $total/$total"; else echo "FAIL $((total - fail))/$total"; exit 1; fi
