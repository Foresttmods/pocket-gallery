package com.pocketgallery.gallery;

import java.util.List;

/** A tab whose results come from a web search, paged through a {@link RemoteFeed}. */
public abstract class RemoteTab extends GalleryTab {
    protected final RemoteFeed feed = new RemoteFeed(this::createPager);

    protected RemoteTab(String id) {
        super(id);
    }

    /**
     * Starts a search with the current search text and settings. May throw a {@link RuntimeException} whose message
     * is shown as the error (e.g. a missing API key).
     */
    protected abstract RemoteFeed.Pager createPager();

    /** e.g. "Searching Wikimedia Commons..." */
    protected abstract String loadingText();

    /** Shown before the first search, e.g. "Type an artist, style or subject". */
    protected abstract String promptText();

    /** After the count, e.g. " photos from Wikimedia Commons". */
    protected abstract String resultNoun();

    /** Search as soon as the tab opens (true), or wait for the player to type (false: the search box gets focus). */
    protected boolean searchOnOpen() {
        return true;
    }

    @Override
    public boolean isRemote() {
        return true;
    }

    @Override
    public List<GalleryEntry> entries() {
        return feed.entries();
    }

    @Override
    public void onShown(TabHost host) {
        if (feed.searched()) return;
        if (searchOnOpen()) host.runSearch();
        else host.focusSearch();
    }

    @Override
    public Status status() {
        if (feed.error() != null) return new Status(feed.error(), Status.ERROR);
        if (feed.loading() && feed.entries().isEmpty()) return new Status(loadingText(), Status.NORMAL);
        if (!feed.searched()) return new Status(promptText(), Status.NORMAL);
        String text = feed.entries().size() + resultNoun()
                + (feed.loading() ? " (loading more...)" : feed.more() ? " (more on next pages)" : "")
                + (feed.info() != null ? " - " + feed.info() : "");
        return new Status(text, feed.info() != null ? Status.NOTE : Status.NORMAL);
    }

    @Override
    public String emptyMessage() {
        return feed.searched() && !feed.loading() && feed.error() == null ? "No results." : "";
    }

    @Override
    public boolean searched() {
        return feed.searched();
    }

    @Override
    public boolean more() {
        return feed.more();
    }

    @Override
    public boolean loading() {
        return feed.loading();
    }

    @Override
    public String error() {
        return feed.error();
    }

    @Override
    public void newSearch() {
        feed.newSearch();
    }

    @Override
    public void fetchNextPage() {
        feed.fetchNextPage();
    }
}
