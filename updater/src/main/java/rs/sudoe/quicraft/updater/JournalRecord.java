// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Instant;
import java.util.Objects;

/**
 * The content of {@code journal.json} (docs/updater.md 12.8). Structural rules are enforced here;
 * a violation raises {@link IllegalArgumentException} with a fixed message.
 */
public record JournalRecord(
        String txid,
        JournalPhase phase,
        String platform,
        long manifestSeq,
        JournalLive live,
        JournalStaged staged,
        String backupSha512,
        Instant created,
        int bootAttempts) {

    static final int MAX_BOOT_ATTEMPTS = 9;
    private static final int TXID_CHARS = 32;

    public JournalRecord {
        SharedChecks.requireLowerHex(txid, TXID_CHARS, "txid");
        Objects.requireNonNull(phase, "phase");
        SharedChecks.requirePlatform(platform);
        if (manifestSeq < 0 || manifestSeq > 9007199254740991L) {
            throw new IllegalArgumentException("invalid manifestSeq");
        }
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(staged, "staged");
        SharedChecks.requireLowerHex(backupSha512, 128, "backupSha512");
        if (!backupSha512.equals(live.sha512())) {
            throw new IllegalArgumentException("backupSha512 differs from live sha512");
        }
        Objects.requireNonNull(created, "created");
        if (bootAttempts < 0 || bootAttempts > MAX_BOOT_ATTEMPTS) {
            throw new IllegalArgumentException("invalid bootAttempts");
        }
    }

    public JournalRecord withPhase(JournalPhase next) {
        return new JournalRecord(txid, next, platform, manifestSeq, live, staged, backupSha512,
                created, bootAttempts);
    }

    public JournalRecord withBootAttempts(int next) {
        return new JournalRecord(txid, phase, platform, manifestSeq, live, staged, backupSha512,
                created, next);
    }
}
