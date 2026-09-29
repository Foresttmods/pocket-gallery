package com.pocketgallery.tabs;

import com.pocketgallery.art.ArtItem;
import com.pocketgallery.art.ArtSearch;
import com.pocketgallery.gallery.GalleryEntry;
import com.pocketgallery.gallery.RemoteFeed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Feeds an {@link ArtSearch} (museums, photos, wallpapers) into a gallery tab. */
final class ArtSearchPager implements RemoteFeed.Pager {
    private final ArtSearch search;

    ArtSearchPager(ArtSearch search) {
        this.search = search;
    }

    @Override
    public RemoteFeed.Page next() throws IOException {
        ArtSearch.Page page = search.next();
        List<GalleryEntry> entries = new ArrayList<>(page.items().size());
        for (ArtItem item : page.items()) entries.add(new GalleryEntry.Art(item));
        return new RemoteFeed.Page(entries, page.hasMore(), page.warning());
    }
}
