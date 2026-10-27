#!/bin/sh
# Linux tc netem rig. Shapes egress on <iface> and ingress via ifb0.
# Usage: netem.sh [--dry-run] apply <iface> <profile>
#        netem.sh [--dry-run] clear <iface>
dir=$(cd "$(dirname "$0")" && pwd)
dry=0
if [ "$1" = "--dry-run" ]; then dry=1; shift; fi

run() {
    if [ "$dry" -eq 1 ]; then echo "$*"; return 0; fi
    "$@" || { echo "failed: $*" >&2; exit 1; }
}

# Refuse ifb devices and interfaces that do not exist. NETEM_SYSFS lets tests
# point at a fake sysfs root.
check_iface() {
    if ! printf '%s\n' "$1" | grep -Eq '^[A-Za-z0-9_.:-]{1,15}$'; then
        echo "bad interface name: $1" >&2; exit 2
    fi
    case "$1" in
    ifb*) echo "refusing ifb device: $1" >&2; exit 2 ;;
    esac
    [ "$dry" -eq 1 ] && return 0
    if [ ! -e "${NETEM_SYSFS:-/sys/class/net}/$1" ]; then
        echo "no such interface: $1" >&2; exit 2
    fi
}

usage() { echo "usage: netem.sh [--dry-run] apply <iface> <profile> | clear <iface>" >&2; exit 64; }

shape() { # dev delay jitter loss rate
    if [ "$5" = "-" ]; then
        run tc qdisc add dev "$1" root netem delay "$2"ms "$3"ms distribution normal loss "$4"%
    else
        run tc qdisc add dev "$1" root netem delay "$2"ms "$3"ms distribution normal loss "$4"% rate "$5"kbit
    fi
}

case "$1" in
apply)
    [ $# -eq 3 ] || usage
    iface=$2; name=$3
    check_iface "$iface"
    line=$(grep -v '^#' "$dir/profiles.tsv" | awk -F'\t' -v n="$name" '$1 == n { print; exit }')
    if [ -z "$line" ]; then echo "unknown profile: $name" >&2; exit 2; fi
    rate=$(echo "$line" | cut -f2); delay=$(echo "$line" | cut -f3)
    jitter=$(echo "$line" | cut -f4); loss=$(echo "$line" | cut -f5)
    run modprobe ifb numifbs=1
    run ip link set dev ifb0 up
    run tc qdisc add dev "$iface" handle ffff: ingress
    run tc filter add dev "$iface" parent ffff: matchall action mirred egress redirect dev ifb0
    shape "$iface" "$delay" "$jitter" "$loss" "$rate"
    shape ifb0 "$delay" "$jitter" "$loss" "$rate"
    ;;
clear)
    [ $# -eq 2 ] || usage
    check_iface "$2"
    # Try all three deletes. A missing qdisc is fine; any other error fails.
    bad=0
    try_del() {
        if [ "$dry" -eq 1 ]; then echo "$*"; return 0; fi
        err=$("$@" 2>&1) && return 0
        case "$err" in
        *"No such file"*|*"Cannot find"*|*"Cannot delete qdisc with handle of zero"*|*"Invalid handle"*) return 0 ;;
        esac
        echo "failed: $*: $err" >&2; bad=1
    }
    try_del tc qdisc del dev "$2" root
    try_del tc qdisc del dev "$2" ingress
    try_del tc qdisc del dev ifb0 root
    exit "$bad"
    ;;
*) usage ;;
esac
