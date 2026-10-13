// SPDX-License-Identifier: GPL-3.0-or-later
package rs.sudoe.quicraft.updater;

import java.nio.channels.WritableByteChannel;

/**
 * Fetches release bytes over HTTPS.
 *
 * <p>Implementations enforce the request's caps, timeouts, redirect policy and HTTPS on every hop.
 * They throw only {@link FetchException}: an {@link java.io.IOException} or a
 * {@link RuntimeException} becomes {@link FetchOutcome#TRANSPORT_ERROR}; an interrupt becomes
 * {@link FetchOutcome#CANCELLED} with the interrupt flag restored.
 *
 * <p>Rules shared by both methods:
 * <ul>
 * <li>Cancellation is checked before connect, between redirect hops and between body chunks.</li>
 * <li>The overall deadline is measured from the start of the call, across all hops, and never resets.</li>
 * <li>{@code maxBytes} applies to the final body, after redirects.</li>
 * <li>A declared Content-Length above {@code maxBytes} fails {@link FetchOutcome#OVERSIZE} before any
 * body byte is read.</li>
 * </ul>
 */
public interface SourceFetcher {

    /**
     * Fetches the whole body into memory.
     *
     * <p>If {@code request.maxBytes()} is greater than {@link FetchRequest#MAX_FETCH_BYTES}, this
     * fails with {@link FetchOutcome#OVERSIZE} without reading any body.
     *
     * @param request the fetch request
     * @param cancel the cancellation token
     * @return the fetched bytes
     * @throws FetchException on any failure; the outcome says which
     */
    FetchedBytes fetch(FetchRequest request, CancelToken cancel) throws FetchException;

    /**
     * Streams the body into a caller-owned destination.
     *
     * <p>A body that reaches {@code maxBytes + 1} bytes fails with {@link FetchOutcome#OVERSIZE}, and
     * that extra byte is never written. After any failure the destination may hold a partial body; the
     * caller discards it. The caller owns the destination and closes it.
     *
     * @param request the fetch request
     * @param destination the channel that receives the body
     * @param cancel the cancellation token
     * @return the number of bytes written
     * @throws FetchException on any failure; the outcome says which
     */
    long download(FetchRequest request, WritableByteChannel destination, CancelToken cancel)
            throws FetchException;
}
