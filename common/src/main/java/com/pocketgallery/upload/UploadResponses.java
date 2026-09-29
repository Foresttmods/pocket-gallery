package com.pocketgallery.upload;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Register responses captured from Immersive Paintings' {@code PaintingRegisterResponsePayload} (see the mixin).
 * Filled on the packet handling thread, drained on the client thread.
 */
public final class UploadResponses {
    /** @param paintingId painting id string on success, null on failure; @param error IP error key ("" on success) */
    public record Response(String paintingId, String error) {
        public boolean success() {
            return error == null || error.isEmpty();
        }
    }

    private static final ConcurrentLinkedQueue<Response> QUEUE = new ConcurrentLinkedQueue<>();
    /** Responses the server still owes us (one per register request we sent). */
    private static final AtomicInteger OUTSTANDING = new AtomicInteger();

    private UploadResponses() {
    }

    /**
     * Called by the mixin for every response.
     *
     * @return true if the response belongs to one of our uploads, in which case IP's own handling is skipped (it
     * would otherwise apply the painting to whatever IP painting screen happens to be open).
     */
    public static boolean offer(String paintingId, String error) {
        if (OUTSTANDING.getAndUpdate(n -> Math.max(0, n - 1)) <= 0) return false;
        QUEUE.add(new Response(paintingId, error));
        return true;
    }

    static void expectOne() {
        QUEUE.clear();
        OUTSTANDING.incrementAndGet();
    }

    /** Batch finished: stop intercepting (a lost response must not swallow IP's own later uploads). */
    static void reset() {
        OUTSTANDING.set(0);
        QUEUE.clear();
    }

    static Response poll() {
        return QUEUE.poll();
    }
}
