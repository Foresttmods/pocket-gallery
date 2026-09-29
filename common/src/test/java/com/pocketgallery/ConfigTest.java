package com.pocketgallery;

import com.pocketgallery.anim.AnimatedIndex;
import com.pocketgallery.art.MuseumFilter;
import com.pocketgallery.art.PhotoTopic;
import com.pocketgallery.config.GalleryConfig;
import com.pocketgallery.folder.FolderScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {
    @TempDir
    Path dir;

    @Test
    void defaults() {
        GalleryConfig c = new GalleryConfig();
        assertEquals(4, c.longSideBlocks);
        assertTrue(c.cropToFit);
        assertEquals("", c.pixabayApiKey);
        assertEquals(MuseumFilter.ALL, c.museumFilter);
        assertEquals(PhotoTopic.LANDSCAPES, c.photoTopic);
    }

    @Test
    void roundTrip() throws Exception {
        GalleryConfig c = new GalleryConfig();
        c.folderPath = "D:\\Bilder\\Gemälde";
        c.pixabayApiKey = "12345-abcdef";
        c.longSideBlocks = 7;
        c.cropToFit = false;
        c.maxGifFrames = 99;
        c.museumFilter = MuseumFilter.SMK;
        c.photoTopic = PhotoTopic.NEBULAE;
        Path file = dir.resolve("config/pocket_gallery.json");
        c.save(file);

        GalleryConfig loaded = GalleryConfig.load(file);
        assertEquals(c.toJson(), loaded.toJson());
        assertEquals("D:\\Bilder\\Gemälde", loaded.folderPath);
        assertEquals(PhotoTopic.NEBULAE, loaded.photoTopic);
    }

    @Test
    void missingOrBrokenFilesGiveDefaults() throws Exception {
        assertEquals(new GalleryConfig().toJson(), GalleryConfig.load(dir.resolve("nope.json")).toJson());
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ this is not json");
        assertEquals(new GalleryConfig().toJson(), GalleryConfig.load(broken).toJson());
    }

    @Test
    void sanitizeClampsAndReadsOldFiles() {
        // a 1.1.0 file: Danbooru settings are ignored here (the personal edition takes them over), unknown enum
        // values fall back to the defaults
        GalleryConfig c = GalleryConfig.fromJson("""
                {"longSideBlocks": 40, "maxGifFrames": -5, "ratingFilter": "ALL", "danbooruUsername": "me",
                 "museumFilter": "SOMETHING_NEW", "photoTopic": "CASTLES", "pixabayApiKey": "  key  ", "unknownField": 1}""");
        assertEquals(16, c.longSideBlocks);
        assertEquals(1, c.maxGifFrames);
        assertEquals(MuseumFilter.ALL, c.museumFilter);
        assertEquals(PhotoTopic.CASTLES, c.photoTopic);
        assertEquals("key", c.pixabayApiKey);
        assertFalse(c.toJson().contains("danbooru"));
    }

    @Test
    void copyIsIndependent() {
        GalleryConfig c = new GalleryConfig();
        GalleryConfig copy = c.copy();
        copy.longSideBlocks = 9;
        assertEquals(4, c.longSideBlocks);
    }

    @Test
    void animatedIndexRoundTrip() throws Exception {
        Path file = dir.resolve("anim.json");
        AnimatedIndex index = new AnimatedIndex(file);
        index.put("immersive_paintings:abc/123", new AnimatedIndex.Entry("C:/x.gif", 1, 2, 3, 4, 5, 6));
        AnimatedIndex reloaded = new AnimatedIndex(file);
        reloaded.load();
        assertEquals(new AnimatedIndex.Entry("C:/x.gif", 1, 2, 3, 4, 5, 6), reloaded.get("immersive_paintings:abc/123"));
        assertNull(reloaded.get("other"));
    }

    @Test
    void folderScannerFindsSupportedFilesRecursively() throws Exception {
        Files.createDirectories(dir.resolve("a/b"));
        Files.createDirectories(dir.resolve(".hidden"));
        Files.writeString(dir.resolve("one.PNG"), "x");
        Files.writeString(dir.resolve("a/two.jpeg"), "x");
        Files.writeString(dir.resolve("a/b/three.gif"), "x");
        Files.writeString(dir.resolve("a/notes.txt"), "x");
        Files.writeString(dir.resolve(".hidden/four.png"), "x");
        List<FolderScanner.LocalImage> found = FolderScanner.scan(dir, () -> false);
        assertEquals(List.of("a/b/three.gif", "a/two.jpeg", "one.PNG"), found.stream().map(FolderScanner.LocalImage::relativePath).toList());
        assertTrue(FolderScanner.matches(found.get(0), "B THREE"));
        assertFalse(FolderScanner.matches(found.get(0), "two"));
    }
}
