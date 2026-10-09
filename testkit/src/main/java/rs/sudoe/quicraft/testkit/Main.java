// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.testkit;

import java.util.Arrays;
import rs.sudoe.quicraft.testkit.bench.BenchClient;
import rs.sudoe.quicraft.testkit.bench.Summarize;
import rs.sudoe.quicraft.testkit.session.MockSessionServer;
import rs.sudoe.quicraft.testkit.transport.TransportClient;
import rs.sudoe.quicraft.testkit.transport.TransportServer;
import rs.sudoe.quicraft.testkit.transport.TransportSummarize;

/** Testkit entry point: {@code bench}, {@code mocksession} or {@code summarize}. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        int status = switch (args[0]) {
            case "bench" -> BenchClient.run(Args.parse(rest));
            case "mocksession" -> MockSessionServer.run(Args.parse(rest));
            case "summarize" -> Summarize.run(Args.parse(rest));
            case "transport-server" -> TransportServer.run(Args.parse(rest));
            case "transport-client" -> TransportClient.run(Args.parse(rest));
            case "transport-summarize" -> TransportSummarize.run(Args.parse(rest));
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(status);
    }

    private static void usage() {
        System.err.println("""
                usage: quicraft-testkit <command> [--key value ...]
                  bench        join a server repeatedly and record join, chunk-load and play RTT metrics
                  mocksession  run a mock Mojang session server for online-mode logins
                  summarize    aggregate bench results and check reproducibility
                  transport-server     QUIC (Reno, CUBIC, BBR) and TCP servers with Minecraft-shaped traffic
                  transport-client     compare those transports: handshake, burst and play RTT
                  transport-summarize  medians and bootstrap CIs of QUIC minus TCP""");
    }
}
