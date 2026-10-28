#!/bin/sh
# macOS dummynet rig (dnctl + pfctl), for the CI call runs (L10a). Reads the same profiles.tsv as
# netem.sh. Pipe 1 shapes outbound packets on <iface>, pipe 2 inbound, each with the profile's
# delay, loss and rate. The rules live in the pf anchor com.apple/call-lab, which the default
# macOS pf.conf already loads through its `dummynet-anchor "com.apple/*"` line.
# dummynet has no jitter, so a profile's jitter_ms is reported on stderr and not applied.
# On lo0 a packet between two local processes passes the outbound rule and then the inbound rule.
# Usage: dummynet.sh [--dry-run] apply <iface> <profile>
#        dummynet.sh [--dry-run] clear
dir=$(cd "$(dirname "$0")" && pwd)
anchor=com.apple/call-lab
dry=0
if [ "$1" = "--dry-run" ]; then dry=1; shift; fi

run() {
    if [ "$dry" -eq 1 ]; then echo "$*"; return 0; fi
    "$@" || { echo "failed: $*" >&2; exit 1; }
}

usage() { echo "usage: dummynet.sh [--dry-run] apply <iface> <profile> | clear" >&2; exit 64; }

check_iface() {
    if ! printf '%s\n' "$1" | grep -Eq '^[A-Za-z0-9_.:-]{1,15}$'; then
        echo "bad interface name: $1" >&2; exit 2
    fi
    [ "$dry" -eq 1 ] && return 0
    if ! ifconfig "$1" >/dev/null 2>&1; then echo "no such interface: $1" >&2; exit 2; fi
}

pipe() { # number delay loss_pct rate
    plr=$(awk -v l="$3" 'BEGIN { printf "%g", l / 100 }')
    if [ "$4" = "-" ]; then
        run dnctl pipe "$1" config delay "$2" plr "$plr"
    else
        run dnctl pipe "$1" config bw "$4"Kbit/s delay "$2" plr "$plr"
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
    if [ "$jitter" != "0" ]; then
        echo "note: dummynet has no jitter; profile $name jitter $jitter ms is not applied" >&2
    fi
    pipe 1 "$delay" "$loss" "$rate"
    pipe 2 "$delay" "$loss" "$rate"
    rules="dummynet out quick on $iface all pipe 1
dummynet in quick on $iface all pipe 2"
    if [ "$dry" -eq 1 ]; then
        echo "pfctl -a $anchor -f -"
        printf '%s\n' "$rules" | sed 's/^/  /'
    else
        printf '%s\n' "$rules" | pfctl -q -a "$anchor" -f - || { echo "failed: pfctl -a $anchor -f -" >&2; exit 1; }
    fi
    # -E takes a reference on pf (prints a token); clear flushes the anchor and the pipes.
    run pfctl -E
    ;;
clear)
    [ $# -eq 1 ] || usage
    run dnctl -q flush
    run pfctl -a "$anchor" -F all
    ;;
*) usage ;;
esac
