package com.pocketgallery.tabs;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.art.ArtCatalog;
import com.pocketgallery.art.ArtSearch;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.gallery.RemoteTab;
import com.pocketgallery.gallery.TabHost;
import com.pocketgallery.gui.PickerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Public-domain paintings from open-access museums and Wikimedia Commons. */
public final class MuseumsTab extends RemoteTab {
    private TabHost host;

    public MuseumsTab() {
        super("museums");
    }

    @Override
    protected ArtSearchPager createPager() {
        GalleryConfig c = PocketGallery.config();
        return new ArtSearchPager(ArtSearch.museums(PocketGallery.web(), search(), c.museumFilter, c.artLatestYear));
    }

    @Override
    public int addControls(TabHost host, int x, int y) {
        this.host = host;
        host.addTabWidget(Button.builder(museumLabel(), this::cycleMuseum)
                .bounds(x, y, 150, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.museum.tooltip",
                        PocketGallery.config().artLatestYear)))
                .build());
        host.addTabWidget(Button.builder(Component.translatable("pocket_gallery.gui.browse"), b -> openBrowse())
                .bounds(x + 152, y, 64, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.browse.tooltip")))
                .build());
        return x + 222;
    }

    private static Component museumLabel() {
        return Component.translatable("pocket_gallery.gui.museum", PocketGallery.config().museumFilter.label());
    }

    private void cycleMuseum(Button button) {
        GalleryConfig c = PocketGallery.config().copy();
        c.museumFilter = c.museumFilter.next();
        PocketGallery.setConfig(c);
        button.setMessage(museumLabel());
        host.runSearch();
    }

    private void openBrowse() {
        String current = search().strip();
        List<PickerScreen.Section<String>> sections = new ArrayList<>();
        for (ArtCatalog.Section s : ArtCatalog.SECTIONS) {
            List<PickerScreen.Option<String>> options = new ArrayList<>();
            for (String pick : s.picks()) options.add(new PickerScreen.Option<>(pick, pick, pick.equalsIgnoreCase(current)));
            sections.add(new PickerScreen.Section<>(s.title(), options));
        }
        host.open(new PickerScreen<>(host.screen(), Component.translatable("pocket_gallery.gui.browse.title"), sections, pick -> {
            setSearch(pick, host);
            host.open(host.screen()); // re-inits the gallery, so the search box shows the pick
            host.runSearch();
        }));
    }

    @Override
    protected String loadingText() {
        return "Searching " + PocketGallery.config().museumFilter.label().toLowerCase(Locale.ROOT) + "...";
    }

    @Override
    protected String promptText() {
        return "Type an artist, style or subject, or use Browse";
    }

    @Override
    protected String resultNoun() {
        return " artworks";
    }

    @Override
    public void onConfigChanged(GalleryConfig before, GalleryConfig after) {
        if (before.artLatestYear != after.artLatestYear && searched()) newSearch();
    }
}
