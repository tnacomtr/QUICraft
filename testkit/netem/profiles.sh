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
        # The two together: loss recovery with a real round trip to wait for.
        delayloss) echo "delay 75ms loss 2%" ;;
        # 25% of packets skip the 10 ms delay and overtake the ones queued before them.
        reorder) echo "delay 10ms reorder 25% 50%" ;;
        # Reordering as real paths produce it: delay jitter around a real base delay (each
        # direction 10 ms +- 5 ms, normal), so later packets sometimes overtake earlier ones.
        # Unlike 'reorder', no packet skips the delay, so the minimum RTT stays realistic.
        jitter)  echo "delay 10ms 5ms distribution normal" ;;
        *)       return 1 ;;
    esac
}
