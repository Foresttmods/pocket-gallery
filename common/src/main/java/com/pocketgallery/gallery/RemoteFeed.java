package com.pocketgallery.gallery;

import com.pocketgallery.Workers;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Results of a web search, loaded page by page on a worker thread as the grid needs them. A newer search discards
 * answers that belong to an older one, duplicates are dropped, and when a whole page is filtered away the next one
 * is fetched automatically (a few times). All state is touched on the client thread only.
 */
public final class RemoteFeed {
    /** Consecutive empty pages fetched automatically before giving up. */
    private static final int MAX_EMPTY_PAGES_IN_A_ROW = 5;

    /** One running search; {@link #next()} is called on a worker thread, one call at a time. */
    public interface Pager {
        Page next() throws IOException;
    }

    /** @param info note shown next to the result count (e.g. a source that was skipped), or null */
    public record Page(List<GalleryEntry> entries, boolean hasMore, String info) {
    }

    private final Supplier<Pager> factory;
    private Pager pager;
    private final List<GalleryEntry> results = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private boolean loading;
    private boolean more;
    private boolean searched;
    private int emptyInARow;
    private int generation;
    private String error;
    private String info;

    public RemoteFeed(Supplier<Pager> factory) {
        this.factory = factory;
    }

    public void newSearch() {
        generation++;
        results.clear();
        keys.clear();
        more = true;
        loading = false;
        emptyInARow = 0;
        error = null;
        info = null;
        searched = true;
        try {
            pager = factory.get();
        } catch (RuntimeException e) {
            pager = null;
            more = false;
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            return;
        }
        fetchNextPage();
    }

    public void fetchNextPage() {
        if (loading || !more || pager == null) return;
        loading = true;
        error = null;
        int gen = generation;
        Pager p = pager;
        Workers.IO.execute(() -> {
            Page page = null;
            String err = null;
            try {
                page = p.next();
            } catch (Exception e) {
                err = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            Page result = page;
            String e = err;
            Minecraft.getInstance().execute(() -> onPage(gen, result, e));
        });
    }

    private void onPage(int gen, Page page, String err) {
        if (gen != generation) return; // a newer search replaced this one
        loading = false;
        if (page == null) {
            error = err;
            return;
        }
        more = page.hasMore();
        if (page.info() != null) info = page.info();
        int added = 0;
        for (GalleryEntry entry : page.entries()) {
            if (keys.add(entry.key())) {
                results.add(entry);
                added++;
            }
        }
        if (added == 0 && more) {
            // everything on this page was filtered or already shown: look further automatically
            if (++emptyInARow < MAX_EMPTY_PAGES_IN_A_ROW) fetchNextPage();
        } else {
            emptyInARow = 0;
        }
    }

    public List<GalleryEntry> entries() {
        return results;
    }

    public boolean more() {
        return more;
    }

    public boolean loading() {
        return loading;
    }

    public boolean searched() {
        return searched;
    }

    public String error() {
        return error;
    }

    public String info() {
        return info;
    }
}
