// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.connect;

import java.util.Objects;
import java.util.Optional;
import rs.sudoe.quicraft.core.Protocol;
import rs.sudoe.quicraft.core.discovery.Advertisement;

/**
 * Chooses {@code quicraft/3}, {@code quicraft/2} or TCP before any connection is created
 * (docs/protocol.md 12.3, 13.1 and 12.16's pre-connect precedence).
 *
 * <p>{@link #select(Inputs)} is a pure function: it reads no clock, no file and no network state;
 * every input a rule needs, including whether the changed-pin re-bind backoff currently allows an
 * attempt, is passed in. It is safe to call from any thread, and it never mutates its inputs.
 *
 * <p>The rules, with the spec section that requires each one:
 *
 * <ul>
 *   <li>{@code tcp-only} returns {@link Transport#TCP} always (§4).
 *   <li>No advertisement, or one this build cannot parse, returns {@code TCP} (§1).
 *   <li>An advertised {@code quicraft/2} returns {@link Transport#V2}, the unchanged v1 behaviour
 *       (12.1, 13.1). {@code V2} is returned for a {@code quicraft/2} advertisement and for nothing
 *       else.
 *   <li>An advertised {@code quicraft/3} returns {@link Transport#V3} only when the client's
 *       multistream flag is enabled, the TLS exporter capability probe succeeded, the client has a
 *       routing table for its protocol number and the advertisement's digest for that number equals
 *       the local one; otherwise it returns {@code TCP} (12.3, 13.1). A {@code /3} listener speaks
 *       {@code /3} only, so it is never offered {@code /2}: the client either speaks {@code /3} or
 *       joins over TCP.
 *   <li>A changed pin never trusts the advertised fingerprint: it returns {@code V3} only when
 *       every {@code /3} prerequisite holds and the re-bind backoff allows an attempt now, and
 *       {@code TCP} otherwise (12.16).
 *   <li>An unusable pin store never returns {@code V3}: it returns {@code V2} for a
 *       {@code quicraft/2} advertisement and {@code TCP} for a {@code quicraft/3} one, with a reason
 *       naming the corrupt store so the caller can tell the player (12.15).
 *   <li>{@code quic-only} turns a {@code TCP} result into {@link Transport#FAIL}, the "no TCP
 *       fallback" setting of §4, except that {@code tcp-only} still returns {@code TCP}.
 * </ul>
 *
 * <p>Every {@link Result} carries the blocker as a {@link Missing} value next to the prose reason, so
 * a caller can branch on the rule without matching strings.
 *
 * <p>Not in scope here: §6's failure cache and §5's racing, which stay with
 * {@link ConnectDecision} and {@code ConnectionRace}. The player's setting is
 * {@link ConnectDecision.Mode}, the same enum the shipped decision uses, so the two can never drift
 * apart. While the multistream flag defaults to {@link #DEFAULT_MULTISTREAM_ENABLED}, this class can
 * never return {@code V3}, so no shipped code path changes behaviour by using it (12.1).
 */
public final class TransportSelection {
    /**
     * The client-side multistream flag's default: disabled (12.1). A caller that reads a real
     * setting passes its value to {@link Inputs.Builder#multistreamEnabled(boolean)}; while it is
     * false, {@link #select(Inputs)} never returns {@link Transport#V3}.
     */
    public static final boolean DEFAULT_MULTISTREAM_ENABLED = false;

    /** Prefix of a routing digest on the wire (13.1, 13.7). */
    private static final String DIGEST_PREFIX = "sha256:";

    /** Length of the lowercase hex body of a locally computed routing digest (13.7). */
    private static final int LOCAL_DIGEST_HEX_LENGTH = 64;

    /**
     * 12.15: a corrupt pin store disables {@code /3} until repaired, and the player is told. The
     * caller can surface this reason whatever transport was picked.
     */
    private static final String PIN_STORE_UNUSABLE =
            "the pin store is unusable, so /3 stays disabled until it is repaired (12.15); "
                    + "notify the player";

    private TransportSelection() {}

    /** The transport the client uses for this connection. */
    public enum Transport {
        /** {@code quicraft/3}: QUIC with the 12/13 binding and multi-stream protocol. */
        V3,
        /**
         * {@code quicraft/2}: QUIC with the shipped v1 stream format. Only a {@code quicraft/2}
         * advertisement selects this (13.1); a {@code /3} listener never accepts {@code /2}.
         */
        V2,
        /** Vanilla TCP. */
        TCP,
        /** The setting forbids TCP and no QUIC transport is available; show the failure (§4). */
        FAIL
    }

    /** The state of the client's authenticated pin for this logical server (12.15, 12.16). */
    public enum PinState {
        /** No pin for this logical server: first contact. */
        NONE,
        /** The advertised fingerprint equals the pin. */
        SAME,
        /**
         * The advertised fingerprint F' differs from the pin. The ping is not trusted: only the
         * one automatic re-bind of 12.16 may proceed, and only over {@code /3}.
         */
        CHANGED,
        /** The pin file is corrupt or unreadable; {@code /3} stays disabled until repaired (12.15). */
        STORE_UNUSABLE
    }

    /**
     * Why {@link Result#transport()} is not {@link Transport#V3}, as a value a caller can switch on
     * instead of matching the prose {@link Result#reason()}. When several blockers apply, this is the
     * first one in the documented order the selection checks them: the setting, the advertisement,
     * the pin store, the multistream flag, the exporter, a local routing table, the advertised
     * digest, and the changed-pin re-bind backoff.
     */
    public enum Missing {
        /** Nothing blocked {@code /3}: it was selected, so no blocker applies. */
        NONE,
        /** The player set {@code tcp-only}, which never selects a QUIC transport (§4). */
        TCP_ONLY_SETTING,
        /** There is no advertisement for the server (§1). */
        NO_ADVERTISEMENT,
        /** The advertisement names an ALPN this client does not offer (13.1). */
        UNKNOWN_ALPN,
        /** The listener advertises {@code quicraft/2}, so it offers no {@code /3} (13.1). */
        ONLY_V2_ADVERTISED,
        /** The client multistream flag is off (12.1). */
        MULTISTREAM_FLAG_OFF,
        /** The TLS exporter capability probe failed (12.3). */
        NO_EXPORTER,
        /** The client has no routing table for its protocol number (12.3, 13.1). */
        NO_LOCAL_ROUTING_TABLE,
        /** The advertisement has no routing digest for the client's protocol number (13.1). */
        NO_ADVERTISED_DIGEST,
        /** The advertised digest for the client's protocol number differs from the local one (13.1). */
        DIGEST_MISMATCH,
        /** The changed-pin re-bind backoff has not elapsed, so this attempt uses fresh TCP (12.16). */
        REBIND_BACKOFF,
        /** The pin store is unusable, which disables {@code /3} until it is repaired (12.15). */
        PIN_STORE_UNUSABLE
    }

    /**
     * Everything the selection rules read. Build one with {@link #builder()}; it defaults to the
     * shipped behaviour (auto, multistream disabled, no advertisement, no pin), so a caller only
     * sets what it knows.
     */
    public static final class Inputs {
        private final ConnectDecision.Mode setting;
        private final boolean multistreamEnabled;
        private final boolean exporterCapable;
        private final Optional<Advertisement> advertisement;
        private final PinState pinState;
        private final boolean rebindAllowed;
        private final int clientProtocolNumber;
        private final Optional<String> localRoutingDigestHex;

        private Inputs(Builder builder) {
            this.setting = builder.setting;
            this.multistreamEnabled = builder.multistreamEnabled;
            this.exporterCapable = builder.exporterCapable;
            this.advertisement = builder.advertisement;
            this.pinState = builder.pinState;
            this.rebindAllowed = builder.rebindAllowed;
            this.clientProtocolNumber = builder.clientProtocolNumber;
            this.localRoutingDigestHex = builder.localRoutingDigestHex;
        }

        public static Builder builder() {
            return new Builder();
        }

        /** The player's setting (§4). {@code AUTO} is the default. */
        public ConnectDecision.Mode setting() {
            return setting;
        }

        /** Whether the disabled-by-default multistream flag is enabled on this client (12.1). */
        public boolean multistreamEnabled() {
            return multistreamEnabled;
        }

        /** True when the TLS exporter capability probe succeeded (12.2, 12.3). */
        public boolean exporterCapable() {
            return exporterCapable;
        }

        /**
         * The advertisement for the server, if any, parsed with
         * {@link Advertisement#extractForSelection(String)} so that a {@code quicraft/3} member is
         * recognized (13.1). Only its ALPN and routing member are read: a malformed advertisement is
         * already "no advertisement" by the time it gets here (§1, 13.1).
         */
        public Optional<Advertisement> advertisement() {
            return advertisement;
        }

        /** The pin state for this logical server (12.15, 12.16). */
        public PinState pinState() {
            return pinState;
        }

        /** Whether a changed-pin re-bind attempt is allowed now, i.e. the backoff has elapsed (12.16). */
        public boolean rebindAllowed() {
            return rebindAllowed;
        }

        /** The client's Minecraft protocol number, used to look up the advertised digest (13.1). */
        public int clientProtocolNumber() {
            return clientProtocolNumber;
        }

        /**
         * The routing digest R of the client's table for {@link #clientProtocolNumber()}, as
         * lowercase hex without the {@code sha256:} prefix ({@code RoutingTable.digestHex()}), or
         * empty when the client has no table for that protocol number. Empty means {@code /3} is
         * impossible here (12.3, 13.1). The builder rejects any other value, so the comparison with
         * the advertised digest stays exact.
         */
        public Optional<String> localRoutingDigestHex() {
            return localRoutingDigestHex;
        }

        /** Mutable builder for {@link Inputs}; not thread-safe, but each built value is. */
        public static final class Builder {
            private ConnectDecision.Mode setting = ConnectDecision.Mode.AUTO;
            private boolean multistreamEnabled = DEFAULT_MULTISTREAM_ENABLED;
            private boolean exporterCapable;
            private Optional<Advertisement> advertisement = Optional.empty();
            private PinState pinState = PinState.NONE;
            private boolean rebindAllowed;
            private int clientProtocolNumber;
            private Optional<String> localRoutingDigestHex = Optional.empty();

            private Builder() {}

            public Builder setting(ConnectDecision.Mode setting) {
                this.setting = Objects.requireNonNull(setting, "setting");
                return this;
            }

            public Builder multistreamEnabled(boolean multistreamEnabled) {
                this.multistreamEnabled = multistreamEnabled;
                return this;
            }

            public Builder exporterCapable(boolean exporterCapable) {
                this.exporterCapable = exporterCapable;
                return this;
            }

            public Builder advertisement(Advertisement advertisement) {
                this.advertisement = Optional.ofNullable(advertisement);
                return this;
            }

            public Builder advertisement(Optional<Advertisement> advertisement) {
                this.advertisement = Objects.requireNonNull(advertisement, "advertisement");
                return this;
            }

            public Builder pinState(PinState pinState) {
                this.pinState = Objects.requireNonNull(pinState, "pinState");
                return this;
            }

            public Builder rebindAllowed(boolean rebindAllowed) {
                this.rebindAllowed = rebindAllowed;
                return this;
            }

            public Builder clientProtocolNumber(int clientProtocolNumber) {
                this.clientProtocolNumber = clientProtocolNumber;
                return this;
            }

            /**
             * Sets the local routing digest, as {@code RoutingTable.digestHex()} returns it:
             * {@code null} or empty means the client has no table for its protocol number (12.3,
             * 13.1).
             *
             * @throws IllegalArgumentException if {@code localRoutingDigestHex} is not 64 lowercase
             *     hex digits or {@code null}
             */
            public Builder localRoutingDigestHex(String localRoutingDigestHex) {
                this.localRoutingDigestHex = Optional.ofNullable(validLocalDigest(localRoutingDigestHex));
                return this;
            }

            /**
             * Sets the local routing digest from an optional value; empty means the client has no
             * table for its protocol number (12.3, 13.1).
             *
             * @throws IllegalArgumentException if the present value is not 64 lowercase hex digits
             */
            public Builder localRoutingDigestHex(Optional<String> localRoutingDigestHex) {
                Objects.requireNonNull(localRoutingDigestHex, "localRoutingDigestHex");
                this.localRoutingDigestHex = localRoutingDigestHex.map(Builder::validLocalDigest);
                return this;
            }

            /**
             * {@code digest} unchanged when it is 64 lowercase hex digits or {@code null}, so a
             * caller cannot pass a value the advertised digest could never equal.
             */
            private static String validLocalDigest(String digest) {
                if (digest == null) {
                    return null;
                }
                if (digest.length() != LOCAL_DIGEST_HEX_LENGTH) {
                    throw new IllegalArgumentException("the local routing digest must be "
                            + LOCAL_DIGEST_HEX_LENGTH + " lowercase hex digits, got "
                            + digest.length() + " characters: " + digest);
                }
                for (int i = 0; i < digest.length(); i++) {
                    char c = digest.charAt(i);
                    if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                        throw new IllegalArgumentException(
                                "the local routing digest must be lowercase hex digits: " + digest);
                    }
                }
                return digest;
            }

            public Inputs build() {
                return new Inputs(this);
            }
        }
    }

    /**
     * What to use, and why. {@code transport} is the decision; {@code reason} is a short sentence
     * naming the rule and its spec section, fit to be shown to the player or written to a log (12.15
     * requires the player to be told the pin store is unusable); {@code missing} is the same blocker
     * as a {@link Missing} value, for code that must not match on prose.
     */
    public static final class Result {
        private final Transport transport;
        private final String reason;
        private final Missing missing;

        private Result(Transport transport, String reason, Missing missing) {
            this.transport = transport;
            this.reason = reason;
            this.missing = missing;
        }

        public Transport transport() {
            return transport;
        }

        public String reason() {
            return reason;
        }

        /** Why this is not {@code V3}: {@link Missing#NONE} only when {@link #transport()} is V3. */
        public Missing missing() {
            return missing;
        }

        public boolean isFail() {
            return transport == Transport.FAIL;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Result)) {
                return false;
            }
            Result other = (Result) o;
            return transport == other.transport && reason.equals(other.reason) && missing == other.missing;
        }

        @Override
        public int hashCode() {
            return Objects.hash(transport, reason, missing);
        }

        @Override
        public String toString() {
            return transport + ": " + reason;
        }
    }

    /**
     * Applies the pre-connect precedence of 12.3, 13.1 and 12.16 to {@code inputs}. Pure and
     * thread-safe; never throws for a valid {@link Inputs}.
     */
    public static Result select(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.setting() == ConnectDecision.Mode.TCP_ONLY) {
            return decide(inputs, Transport.TCP, "the player set tcp-only (protocol §4)",
                    Missing.TCP_ONLY_SETTING);
        }
        Optional<Advertisement> advertisement = inputs.advertisement();
        if (!advertisement.isPresent()) {
            if (inputs.pinState() == PinState.STORE_UNUSABLE) {
                return decide(inputs, Transport.TCP, "there is no advertisement for this server "
                        + "(protocol §1); " + PIN_STORE_UNUSABLE, Missing.PIN_STORE_UNUSABLE);
            }
            return decide(inputs, Transport.TCP, "there is no advertisement for this server (protocol §1)",
                    Missing.NO_ADVERTISEMENT);
        }
        Advertisement ad = advertisement.get();
        if (Protocol.ALPN_V3.equals(ad.alpn())) {
            return selectV3(inputs, ad);
        }
        if (Protocol.ALPN.equals(ad.alpn())) {
            return selectV2(inputs);
        }
        // Unreachable through extract, extractForSelection and of, which all reject an unknown ALPN.
        // It stays explicit so no ALPN other than quicraft/2 can ever be treated as /2 (13.1).
        return decide(inputs, Transport.TCP,
                "the advertisement names an ALPN this client does not offer (13.1)", Missing.UNKNOWN_ALPN);
    }

    /**
     * An advertised {@code quicraft/2} listener keeps the unchanged v1 behaviour (12.1, 13.1),
     * except for a changed pin, which never trusts F' over {@code /2} (12.16).
     */
    private static Result selectV2(Inputs inputs) {
        if (inputs.pinState() == PinState.CHANGED) {
            return decide(inputs, Transport.TCP, "the advertised fingerprint differs from the pin and "
                    + "only quicraft/2 is advertised, but a re-bind needs /3 (12.16); using fresh TCP",
                    Missing.ONLY_V2_ADVERTISED);
        }
        if (inputs.pinState() == PinState.STORE_UNUSABLE) {
            return decide(inputs, Transport.V2, PIN_STORE_UNUSABLE + ", using /2",
                    Missing.PIN_STORE_UNUSABLE);
        }
        return decide(inputs, Transport.V2, "the server advertises quicraft/2 (13.1)",
                Missing.ONLY_V2_ADVERTISED);
    }

    /**
     * An advertised {@code quicraft/3} listener speaks {@code /3} only (13.1), so every prerequisite
     * that fails returns {@code TCP}, never {@code /2}: this client either does {@code /3} or joins
     * over vanilla TCP. A changed pin is the same answer for the same reason (12.16).
     */
    private static Result selectV3(Inputs inputs, Advertisement ad) {
        if (inputs.pinState() == PinState.STORE_UNUSABLE) {
            return decide(inputs, Transport.TCP, PIN_STORE_UNUSABLE + ", using TCP",
                    Missing.PIN_STORE_UNUSABLE);
        }
        Missing missing = missingPrerequisite(inputs, ad);
        if (inputs.pinState() == PinState.CHANGED) {
            if (!inputs.rebindAllowed()) {
                return decide(inputs, Transport.TCP, "the changed-pin re-bind backoff is active (12.16); "
                        + "using fresh TCP", Missing.REBIND_BACKOFF);
            }
            if (missing != Missing.NONE) {
                return decide(inputs, Transport.TCP, "a changed pin needs /3, but " + describe(missing, inputs)
                        + " (12.16, 13.1); using fresh TCP without trusting F'", missing);
            }
            return decide(inputs, Transport.V3, "the one automatic re-bind attempt uses /3 with the "
                    + "advertised fingerprint F' (12.16)", Missing.NONE);
        }
        if (missing != Missing.NONE) {
            return decide(inputs, Transport.TCP, "quicraft/3 is advertised, but " + describe(missing, inputs)
                    + "; a /3 listener does not accept /2, so this uses TCP (13.1)", missing);
        }
        return decide(inputs, Transport.V3, "quicraft/3 is advertised and the routing digest for "
                + "protocol " + inputs.clientProtocolNumber() + " matches the local one (13.1)",
                Missing.NONE);
    }

    /**
     * The first 12.3/13.1 prerequisite that a {@code /3} selection is missing, or
     * {@link Missing#NONE} when they all hold: the enabled multistream flag, a successful exporter
     * probe, a local routing table for the client's protocol number, and an advertised digest for
     * that number equal to the local one.
     */
    private static Missing missingPrerequisite(Inputs inputs, Advertisement ad) {
        if (!inputs.multistreamEnabled()) {
            return Missing.MULTISTREAM_FLAG_OFF;
        }
        if (!inputs.exporterCapable()) {
            return Missing.NO_EXPORTER;
        }
        if (!inputs.localRoutingDigestHex().isPresent()) {
            return Missing.NO_LOCAL_ROUTING_TABLE;
        }
        Optional<String> advertised = ad.routingDigest(inputs.clientProtocolNumber());
        if (!advertised.isPresent()) {
            return Missing.NO_ADVERTISED_DIGEST;
        }
        if (!advertised.get().equals(DIGEST_PREFIX + inputs.localRoutingDigestHex().get())) {
            return Missing.DIGEST_MISMATCH;
        }
        return Missing.NONE;
    }

    /** The reason phrase of a missing prerequisite, or of {@link Missing#NONE} for none of them. */
    private static String describe(Missing missing, Inputs inputs) {
        switch (missing) {
            case MULTISTREAM_FLAG_OFF:
                return "the client multistream flag is off (12.1)";
            case NO_EXPORTER:
                return "the TLS exporter capability probe failed (12.3)";
            case NO_LOCAL_ROUTING_TABLE:
                return "the client has no routing table for protocol " + inputs.clientProtocolNumber()
                        + " (12.3, 13.1)";
            case NO_ADVERTISED_DIGEST:
                return "the advertisement has no routing digest for protocol "
                        + inputs.clientProtocolNumber() + " (13.1)";
            case DIGEST_MISMATCH:
                return "the advertised routing digest for protocol " + inputs.clientProtocolNumber()
                        + " differs from the local one (13.1)";
            default:
                return "no /3 prerequisite is missing";
        }
    }

    /**
     * The {@code quic-only} rule of §4: it only turns a {@code TCP} decision into {@code FAIL},
     * because every other transport is QUIC. {@code tcp-only} is handled before this and is never
     * turned into a failure.
     */
    private static Result decide(Inputs inputs, Transport transport, String reason, Missing missing) {
        if (transport == Transport.TCP && inputs.setting() == ConnectDecision.Mode.QUIC_ONLY) {
            return new Result(Transport.FAIL, reason
                    + "; quic-only has no TCP fallback, so the connection fails (protocol §4)", missing);
        }
        return new Result(transport, reason, missing);
    }
}
