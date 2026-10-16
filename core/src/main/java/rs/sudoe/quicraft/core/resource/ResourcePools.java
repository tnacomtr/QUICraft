// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.core.resource;

/** Shared node reservations and accounting transitions (docs/protocol.md 13.12). */
public interface ResourcePools {
    enum Pool { NODE, INFLATER, OUTBOUND, INBOUND_EXCESS, PRE_LOGIN }
    /** Reserve bytes from the named pool; false means temporarily unavailable. */
    boolean reserve(Pool pool, Object owner, long bytes);
    /** Release a previously reserved charge. */
    void release(Pool pool, Object owner, long bytes);
    /** Atomically move an existing reservation between pools during promotion. */
    boolean promote(Pool from, Pool to, Object owner, long bytes);
}
