// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import rs.sudoe.quicraft.updater.TrustTransition.Transition;

/**
 * Admission rules for {@code /quicraft update} (docs/updater.md 12.9). Every failing rule is
 * collected, with a fixed text, so the owner sees all reasons at once.
 */
final class UpdateAdmission {

    static final String INIT_NOT_COMPLETE = "initialisation has not finished";
    static final String JOURNAL_PRESENT = "a previous update is pending restart or recovery";
    static final String JAR_CHANGED = "the installed jar changed since start";
    static final String TRUST_EPOCH_OLDER = "the trust epoch is older than the persisted one";
    static final String TRUST_INVALID = "the trust file does not match the persisted key set";
    static final String STATE_FULL = "updater state full";
    static final String LIVE_HASH_UNKNOWN = "the live jar hash is unknown";
    static final String TRUST_NOT_APPLIED =
            "the running trust file has not been applied to the persisted state; restart the server";

    private static final Pattern SHA512_HEX = Pattern.compile("[0-9a-f]{128}");

    private UpdateAdmission() {
    }

    /**
     * The facts the rules are judged on.
     *
     * @param initComplete true once initialisation has finished
     * @param journalPresent true when a journal exists in any phase
     * @param liveSha512AtInit the live jar SHA-512 recorded at start
     * @param currentLiveSha512 the live jar SHA-512 now
     * @param stateFull true when the persisted state is full
     */
    record AdmissionContext(
            boolean initComplete,
            boolean journalPresent,
            String liveSha512AtInit,
            String currentLiveSha512,
            boolean stateFull) {
        AdmissionContext {
            Objects.requireNonNull(liveSha512AtInit, "liveSha512AtInit");
            Objects.requireNonNull(currentLiveSha512, "currentLiveSha512");
        }
    }

    /**
     * The decision: admitted only when {@code reasons} is empty.
     *
     * @param admitted true when the update may start
     * @param reasons the fixed refusal texts, in rule order
     */
    record AdmissionDecision(boolean admitted, List<String> reasons) {
        AdmissionDecision {
            reasons = List.copyOf(reasons);
            if (admitted != reasons.isEmpty()) {
                throw new IllegalArgumentException("admitted must match the absence of reasons");
            }
        }
    }

    /**
     * Applies the admission rules.
     *
     * @throws UpdateRejectedException if the running trust file is malformed
     */
    static AdmissionDecision admit(AdmissionContext ctx, RuntimeIdentity id, StateRecords.Snapshot current)
            throws UpdateRejectedException {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(current, "current");
        List<String> reasons = new ArrayList<>();
        if (!ctx.initComplete()) {
            reasons.add(INIT_NOT_COMPLETE);
        }
        if (ctx.journalPresent()) {
            reasons.add(JOURNAL_PRESENT);
        }
        if (!isSha512Hex(ctx.liveSha512AtInit()) || !isSha512Hex(ctx.currentLiveSha512())) {
            reasons.add(LIVE_HASH_UNKNOWN);
        } else if (!ctx.currentLiveSha512().equals(ctx.liveSha512AtInit())) {
            reasons.add(JAR_CHANGED);
        }
        Transition transition = TrustTransition.classify(
                current.state().persistedTrust(), id.runningTrust()).transition();
        if (transition == Transition.OLDER_EPOCH_REPORT_ONLY) {
            reasons.add(TRUST_EPOCH_OLDER);
        } else if (transition == Transition.INVALID_SAME_EPOCH) {
            reasons.add(TRUST_INVALID);
        } else if (transition != Transition.NORMAL) {
            reasons.add(TRUST_NOT_APPLIED);
        }
        if (ctx.stateFull()) {
            reasons.add(STATE_FULL);
        }
        return new AdmissionDecision(reasons.isEmpty(), reasons);
    }

    private static boolean isSha512Hex(String value) {
        return value != null && SHA512_HEX.matcher(value).matches();
    }
}
