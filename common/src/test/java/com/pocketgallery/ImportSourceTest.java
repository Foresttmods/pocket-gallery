package com.pocketgallery;

import com.pocketgallery.gallery.ImportSource;
import com.pocketgallery.gallery.SourceIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportSourceTest {
    @Test
    void paintingNamesCarryTheSource() {
        assertEquals("[Museum] Water Lilies - Claude Monet", ImportSource.MUSEUM.paintingName("Water Lilies - Claude Monet", true));
        assertEquals("Water Lilies", ImportSource.MUSEUM.paintingName(" Water Lilies ", false));
        assertEquals("[Photo] Untitled", ImportSource.PHOTO.paintingName("", true));
        String longName = ImportSource.WALLPAPER.paintingName("x".repeat(400), true);
        assertEquals(ImportSource.MAX_NAME_LENGTH, longName.length());
        assertTrue(longName.startsWith("[Wallpaper] "));
    }

    @Test
    void sourceFromNameAndPath() {
        assertEquals(ImportSource.MUSEUM, ImportSource.fromName("[Museum] Shoes - Vincent van Gogh"));
        assertEquals(ImportSource.FOLDER, ImportSource.fromName("[Folder] cat"));
        assertEquals(ImportSource.WALLPAPER, ImportSource.fromName("[Wallpaper] Space (wallhaven abc123)"));
        assertNull(ImportSource.fromName("Museum Shoes"));
        assertNull(ImportSource.fromName(null));

        assertEquals(ImportSource.MUSEUM, ImportSource.fromRelativePath("art/aic/Water Lilies (16568).jpg"));
        assertEquals(ImportSource.PHOTO, ImportSource.fromRelativePath("photos\\commons\\Castle (42).jpg"));
        assertEquals(ImportSource.WALLPAPER, ImportSource.fromRelativePath("Wallpapers/wallhaven/x.png"));
        assertEquals(ImportSource.FOLDER, ImportSource.fromRelativePath("holiday/art/beach.png"));
        assertEquals(ImportSource.FOLDER, ImportSource.fromRelativePath("artwork.png"));
        assertEquals(ImportSource.PHOTO, ImportSource.fromTag("photo"));
        assertEquals("wallpapers", ImportSource.WALLPAPER.folderName());
    }

    @Test
    void registeredSourcesKeepFilterOrder() {
        ImportSource extra = ImportSource.register("TestSource", "Test sources", "testsource/", 35);
        assertSame(extra, ImportSource.register("testsource", "ignored", null, 1), "same tag = same source");
        List<ImportSource> all = ImportSource.all();
        assertEquals(ImportSource.FOLDER, all.getLast(), "Folder stays last");
        assertEquals(all.indexOf(ImportSource.WALLPAPER) + 1, all.indexOf(extra));
        assertEquals(extra, ImportSource.fromRelativePath("testsource/1.png"));
        assertEquals(extra, ImportSource.fromName("[TestSource] x"));
    }

    @Test
    void indexPersistsAndWinsOverName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sources.json");
        SourceIndex index = new SourceIndex(file);
        index.put("immersive_paintings:abc/1", new SourceIndex.Entry("Photo", "Wikimedia Commons", "CC BY-SA 4.0", "https://x"));
        SourceIndex reloaded = new SourceIndex(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertEquals("Wikimedia Commons", reloaded.get("immersive_paintings:abc/1").origin());
        // index entry wins, then the name prefix, else unknown
        assertEquals(ImportSource.PHOTO, reloaded.sourceOf("immersive_paintings:abc/1", "[Museum] renamed"));
        assertEquals(ImportSource.MUSEUM, reloaded.sourceOf("immersive_paintings:abc/2", "[Museum] Shoes"));
        assertNull(reloaded.sourceOf("immersive_paintings:abc/3", "My drawing"));
        // sources that aren't in this build (e.g. an index written by another edition) count as "other"
        index.put("immersive_paintings:abc/4", new SourceIndex.Entry("NotInThisBuild", "", "", ""));
        assertNull(index.sourceOf("immersive_paintings:abc/4", "whatever"));
    }
}
