// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.releasetool;

import java.io.PrintStream;
import java.util.Set;

/**
 * Offline release-metadata tool for the updater (docs/updater.md blocker 15).
 *
 * <p>It composes the exact bytes of {@code release.v1.json} and {@code revocations.v1.json}, prints
 * the OpenSSL commands the owner runs to sign those bytes by hand, assembles the detached
 * {@code .sig} files, and verifies every pair with the updater's own parser and verifier code. It
 * never generates, reads or stores a private key, and it never touches the network.
 *
 * <p>Run it from the repository root:
 * {@code ./gradlew :release-tool:run --args="compose-release ..."}.
 */
public final class ReleaseTool {

    /** Success. */
    static final int EXIT_OK = 0;

    /** The command ran but a check failed (a bad signature, an expired document, a torn pair). */
    static final int EXIT_FAILED = 1;

    /** Usage, input or I/O error. */
    static final int EXIT_USAGE = 2;

    private ReleaseTool() {
    }

    public static void main(String[] argv) {
        System.exit(run(argv, System.out, System.err));
    }

    /**
     * Runs one command and returns the process exit code.
     *
     * @param argv the command line, command name first
     * @param out stdout for reports
     * @param err stderr for failures
     * @return {@link #EXIT_OK}, {@link #EXIT_FAILED} or {@link #EXIT_USAGE}
     */
    public static int run(String[] argv, PrintStream out, PrintStream err) {
        if (argv.length == 0) {
            usage(err);
            return EXIT_USAGE;
        }
        String command = argv[0];
        try {
            return switch (command) {
                case "compose-revocations" -> ComposeRevocations.run(Cli.parse(argv, 1,
                        ComposeRevocations.OPTIONS, ComposeRevocations.REPEATABLE), out);
                case "compose-release" -> ComposeRelease.run(Cli.parse(argv, 1,
                        ComposeRelease.OPTIONS, ComposeRelease.REPEATABLE), out);
                case "payload-info" -> PayloadInfo.run(Cli.parse(argv, 1,
                        PayloadInfo.OPTIONS, Set.of()), out);
                case "assemble-sig" -> AssembleSig.run(Cli.parse(argv, 1,
                        AssembleSig.OPTIONS, AssembleSig.REPEATABLE), out, err);
                case "verify" -> Verify.run(Cli.parse(argv, 1, Verify.OPTIONS, Set.of()), out, err);
                case "help", "--help", "-h" -> {
                    usage(out);
                    yield EXIT_OK;
                }
                default -> throw new ToolException("unknown command '" + command + "'");
            };
        } catch (ToolException e) {
            err.println("release-tool: " + e.getMessage());
            return EXIT_USAGE;
        }
    }

    private static void usage(PrintStream out) {
        out.println("""
                quicraft release tool: compose and verify updater release metadata offline.

                Usage:
                  compose-revocations --out FILE --seq N --issued TS --days D(1..400)
                        --min-manifest-seq M [--revoke-key kid:pub]... [--block sha512]...
                  compose-release --out FILE --seq N [--issued TS] --days D(1..45)
                        --min-revocations-seq M --tag vX --entry PLATFORM=JARPATH=TARGET1,...
                        [--withdrawn PLATFORM:VERSION:SHA512]... [--trust FILE]
                  payload-info --payload FILE [--trust FILE] [--out SIGNED-NAME]
                  assemble-sig --payload FILE --sig KID=SIGFILE [--sig KID=SIGFILE] --out FILE.sig
                        [--trust FILE] [--now TS]
                  verify --trust FILE --payload FILE --sig FILE [--now TS]

                Timestamps are YYYY-MM-DDTHH:MM:SSZ (UTC). --trust defaults to
                updater/release-trust/updater-trust.json. The tool never handles a private key:
                sign the payload bytes by hand with the OpenSSL command payload-info prints.
                """);
    }
}
