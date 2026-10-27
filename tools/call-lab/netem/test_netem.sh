#!/bin/sh
# Tests for netem.sh. Dry-run only: never runs tc, needs no root.
dir=$(cd "$(dirname "$0")" && pwd)
S="$dir/netem.sh"
fail=0; total=0

check() { # name expected actual
    total=$((total + 1))
    if [ "$2" = "$3" ]; then echo "ok   $1"; else
        echo "FAIL $1"; printf 'expected:\n%s\nactual:\n%s\n' "$2" "$3"; fail=$((fail + 1))
    fi
}

test_apply_shapes_ingress_and_egress() {
    exp='modprobe ifb numifbs=1
ip link set dev ifb0 up
tc qdisc add dev eth0 handle ffff: ingress
tc filter add dev eth0 parent ffff: matchall action mirred egress redirect dev ifb0
tc qdisc add dev eth0 root netem delay 40ms 5ms distribution normal loss 5%
tc qdisc add dev ifb0 root netem delay 40ms 5ms distribution normal loss 5%'
    check apply_shapes_ingress_and_egress "$exp" "$(sh "$S" --dry-run apply eth0 loss5 2>&1)"
}

test_rate_only_when_capped() {
    a=$(sh "$S" --dry-run apply eth0 cap32 2>&1 | grep -c 'rate 32kbit$')
    b=$(sh "$S" --dry-run apply eth0 loss5 2>&1 | grep -c rate)
    check rate_only_when_capped "2 0" "$a $b"
}

test_clear() {
    exp='tc qdisc del dev eth0 root
tc qdisc del dev eth0 ingress
tc qdisc del dev ifb0 root'
    check clear "$exp" "$(sh "$S" --dry-run clear eth0 2>&1)"
}

test_unknown_profile() {
    out=$(sh "$S" --dry-run apply eth0 nope 2>&1); rc=$?
    check unknown_profile "2 unknown profile: nope" "$rc $out"
}

test_apply_shapes_ingress_and_egress
test_rate_only_when_capped
test_clear
test_unknown_profile
if [ "$fail" -eq 0 ]; then echo "PASS $total/$total"; else echo "FAIL $((total - fail))/$total"; exit 1; fi
