#!/bin/sh
# Tests for netem.sh. Dry-run only: never runs tc, needs no root.
dir=$(cd "$(dirname "$0")" && pwd)
S="$dir/netem.sh"
# Fake sysfs so tests need no real interfaces.
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/sys/eth0" "$tmp/bin"
NETEM_SYSFS="$tmp/sys"; export NETEM_SYSFS
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

test_unknown_interface() {
    out=$(sh "$S" --dry-run apply bogus0 loss5 2>&1); rc=$?
    check unknown_interface "2 no such interface: bogus0" "$rc $out"
    out=$(sh "$S" --dry-run clear bogus0 2>&1); rc=$?
    check unknown_interface_clear "2 no such interface: bogus0" "$rc $out"
}

test_refuse_ifb() {
    mkdir -p "$tmp/sys/ifb0"
    out=$(sh "$S" --dry-run apply ifb0 loss5 2>&1); rc=$?
    check refuse_ifb_apply "2 refusing ifb device: ifb0" "$rc $out"
    out=$(sh "$S" --dry-run clear ifb0 2>&1); rc=$?
    check refuse_ifb_clear "2 refusing ifb device: ifb0" "$rc $out"
}

test_stops_on_first_failure() {
    for c in modprobe ip; do printf '#!/bin/sh\necho "$0 $*" >> "%s/log"\n' "$tmp" > "$tmp/bin/$c"; done
    printf '#!/bin/sh\necho "tc $*" >> "%s/log"\nexit 1\n' "$tmp" > "$tmp/bin/tc"
    chmod +x "$tmp/bin/"*
    : > "$tmp/log"
    out=$(PATH="$tmp/bin:$PATH" sh "$S" apply eth0 loss5 2>&1); rc=$?
    n=$(wc -l < "$tmp/log" | tr -d ' ')
    [ "$rc" -ne 0 ] && rcok=nonzero || rcok=zero
    check stops_on_first_failure "nonzero 3" "$rcok $n"
}

test_executable() {
    [ -x "$S" ] && r=yes || r=no
    check executable yes "$r"
}

test_apply_shapes_ingress_and_egress
test_rate_only_when_capped
test_clear
test_unknown_profile
test_unknown_interface
test_refuse_ifb
test_stops_on_first_failure
test_executable
if [ "$fail" -eq 0 ]; then echo "PASS $total/$total"; else echo "FAIL $((total - fail))/$total"; exit 1; fi
