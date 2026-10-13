// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** Reports whether the server is healthy after a swap, within a timeout. */
public interface HealthSignal {

    /**
     * @param timeout the maximum time to wait for a verdict
     * @return a stage that completes with {@code true} when healthy and {@code false} otherwise
     */
    CompletionStage<Boolean> awaitHealthy(Duration timeout);
}
