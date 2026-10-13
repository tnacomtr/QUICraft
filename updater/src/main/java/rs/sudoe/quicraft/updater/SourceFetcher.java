// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.channels.WritableByteChannel;

/**
 * Fetches release bytes over HTTPS. Implementations enforce the request's caps, timeouts, redirect
 * policy and HTTPS on every hop.
 */
public interface SourceFetcher {

    /**
     * Fetches the whole body into memory.
     *
     * @param request the fetch request
     * @param cancel the cancellation token, checked between chunks
     * @return the fetched bytes
     * @throws FetchException on any failure; the outcome says which
     */
    FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException;

    /**
     * Streams the body into a caller-owned destination. Cancellation is checked between chunks. A
     * body that reaches {@code maxBytes + 1} bytes fails with {@link FetchOutcome#OVERSIZE} and that
     * extra byte is never written. The caller owns the destination and closes it.
     *
     * @param request the fetch request
     * @param destination the channel that receives the body
     * @param cancel the cancellation token, checked between chunks
     * @return the number of bytes written
     * @throws FetchException on any failure; the outcome says which
     */
    long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
            throws FetchException;
}
