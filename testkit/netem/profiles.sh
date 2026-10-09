# SPDX-License-Identifier: GPL-3.0-or-later
# netem profiles for the testkit. Each one is applied to BOTH directions of the client's
# link (egress on the interface, ingress through an ifb device), so "delay" adds 2 x 75 ms
# of round-trip time and "loss" drops 2% of packets each way. docs/benchmarks.md quotes
# these definitions; change both together.
netem_args() {
    case "$1" in
        clean)   echo "" ;;
        loss)    echo "loss 2%" ;;
        delay)   echo "delay 75ms" ;;
        # 25% of packets skip the 10 ms delay and overtake the ones queued before them.
        reorder) echo "delay 10ms reorder 25% 50%" ;;
        *)       return 1 ;;
    esac
}
