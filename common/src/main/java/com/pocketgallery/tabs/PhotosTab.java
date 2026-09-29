package com.pocketgallery.tabs;

import com.pocketgallery.PocketGallery;
import com.pocketgallery.art.ArtSearch;
import com.pocketgallery.art.ArtSource;
import com.pocketgallery.art.PhotoTopic;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.gallery.RemoteTab;
import com.pocketgallery.gallery.TabHost;
import com.pocketgallery.gui.PickerScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Photos, space images and wallpapers: nature and architecture from Wikimedia Commons, NASA, the Library of
 * Congress' vintage collections, Wallhaven and Pixabay. One topic (and so one source) at a time.
 */
public final class PhotosTab extends RemoteTab {
    private TabHost host;

    public PhotosTab() {
        super("photos");
    }

    @Override
    protected ArtSearchPager createPager() {
        GalleryConfig c = PocketGallery.config();
        return new ArtSearchPager(ArtSearch.photos(PocketGallery.web(), c.photoTopic, search(), c.pixabayApiKey));
    }

    @Override
    public int addControls(TabHost host, int x, int y) {
        this.host = host;
        host.addTabWidget(Button.builder(topicLabel(), b -> openTopics())
                .bounds(x, y, 170, 20)
                .tooltip(Tooltip.create(Component.translatable("pocket_gallery.gui.topic.tooltip")))
                .build());
        return x + 176;
    }

    private static Component topicLabel() {
        return Component.translatable("pocket_gallery.gui.topic", PocketGallery.config().photoTopic.label());
    }

    private void openTopics() {
        PhotoTopic current = PocketGallery.config().photoTopic;
        List<PickerScreen.Section<PhotoTopic>> sections = new ArrayList<>();
        for (PhotoTopic.Group group : PhotoTopic.Group.values()) {
            List<PickerScreen.Option<PhotoTopic>> options = new ArrayList<>();
            for (PhotoTopic t : PhotoTopic.values()) {
                if (t.group() == group) options.add(new PickerScreen.Option<>(t.label(), t, t == current));
            }
            sections.add(new PickerScreen.Section<>(group.label(), options));
        }
        host.open(new PickerScreen<>(host.screen(), Component.translatable("pocket_gallery.gui.topic.title"), sections, topic -> {
            GalleryConfig c = PocketGallery.config().copy();
            c.photoTopic = topic;
            PocketGallery.setConfig(c);
            host.open(host.screen());
            host.runSearch();
        }));
    }

    private static ArtSource source() {
        return PocketGallery.config().photoTopic.source();
    }

    @Override
    protected String loadingText() {
        return "Searching " + source().displayName() + "...";
    }

    @Override
    protected String promptText() {
        return "Pick a topic, optionally add words, press Enter";
    }

    @Override
    protected String resultNoun() {
        String noun = switch (PocketGallery.config().photoTopic.group()) {
            case WALLPAPERS -> " wallpapers";
            case SPACE, VINTAGE -> " images";
            default -> " photos";
        };
        // Pixabay (and good manners) ask to show where search results come from
        return noun + " from " + source().displayName();
    }

    @Override
    public void onConfigChanged(GalleryConfig before, GalleryConfig after) {
        if (!Objects.equals(before.pixabayApiKey, after.pixabayApiKey) && source() == ArtSource.PIXABAY) newSearch();
    }
}
