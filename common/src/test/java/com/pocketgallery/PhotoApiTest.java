package com.pocketgallery;

import com.pocketgallery.art.ArtApi;
import com.pocketgallery.art.ArtItem;
import com.pocketgallery.art.ArtSource;
import com.pocketgallery.art.PhotoApi;
import com.pocketgallery.art.PhotoTopic;
import com.pocketgallery.gallery.ImportSource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Photos tab's other sources, parsed from real responses captured once (Pixabay: the example response from its
 * API documentation, since it needs a key).
 */
class PhotoApiTest {
    private static String resource(String name) throws Exception {
        try (InputStream in = PhotoApiTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- NASA

    @Test
    void nasaUrls() {
        String url = PhotoApi.nasaUrl("nebula", " orion  ", 2, 40);
        URI uri = URI.create(url);
        assertEquals("images-api.nasa.gov", uri.getHost());
        assertTrue(url.contains("q=nebula+orion"));
        assertTrue(uri.getQuery().contains("media_type=image") && uri.getQuery().contains("page=2"));
        assertTrue(PhotoApi.nasaUrl("", "", 1, 40).contains("q=space"), "an empty search still needs a phrase");
    }

    @Test
    void parsesNasaResponse() throws Exception {
        ArtApi.Parsed parsed = PhotoApi.parseNasa(resource("nasa_sample.json"), 1);
        assertEquals(4, parsed.items().size());
        assertEquals(2, parsed.next());
        assertEquals(316, parsed.total());
        ArtItem dumbbell = parsed.items().getFirst();
        assertEquals(ArtSource.NASA, dumbbell.source());
        assertEquals(ImportSource.PHOTO, dumbbell.kind());
        assertEquals("PIA14417", dumbbell.id());
        assertEquals("Weighing in on the Dumbbell Nebula - NASA/JPL-Caltech/Harvard-Smithsonian CfA", dumbbell.displayName());
        assertEquals("2011", dumbbell.date());
        // small original: imported as is
        assertEquals(2800, dumbbell.width());
        assertEquals(1400, dumbbell.height());
        assertEquals("https://images-assets.nasa.gov/image/PIA14417/PIA14417~orig.jpg", dumbbell.downloadUrl());
        assertTrue(dumbbell.thumbUrl().endsWith("~thumb.jpg"));
        assertTrue(dumbbell.previewUrl().endsWith("~medium.jpg"));
        assertEquals("https://images.nasa.gov/details/PIA14417", dumbbell.pageUrl());
        // no size reported: kept, size unknown
        ArtItem planetary = parsed.items().get(2);
        assertEquals(0, planetary.width());
        assertEquals("Planetary Nebula - GSFC", planetary.displayName(), "the NASA center is the credit when nothing else is");
    }

    @Test
    void nasaSkipsTinyAndHugeOriginals() {
        String json = """
                {"collection":{"items":[
                  {"data":[{"nasa_id":"tiny","title":"Tiny","media_type":"image"}],
                   "links":[{"href":"http://x/tiny~thumb.jpg","rel":"preview"},
                            {"href":"http://x/tiny~orig.jpg","rel":"canonical","width":400,"height":300,"size":1000}]},
                  {"data":[{"nasa_id":"huge id","title":"Huge","media_type":"image"}],
                   "links":[{"href":"http://x/huge id~thumb.jpg","rel":"preview"},
                            {"href":"http://x/huge id~medium.jpg","rel":"alternate","width":1280,"height":720,"size":5},
                            {"href":"http://x/huge id~orig.tif","rel":"canonical","width":20000,"height":12000,"size":900000000}]},
                  {"data":[{"nasa_id":"video","title":"Video","media_type":"video"}],"links":[]}
                ],"links":[]}}""";
        ArtApi.Parsed parsed = PhotoApi.parseNasa(json, 3);
        assertEquals(1, parsed.items().size());
        ArtItem huge = parsed.items().getFirst();
        assertEquals("https://x/huge%20id~medium.jpg", huge.downloadUrl(), "TIFF / oversized originals: medium rendition");
        assertEquals(1280, huge.width());
        assertEquals(-1, parsed.next());
        assertThrows(IllegalArgumentException.class, () -> PhotoApi.parseNasa("{\"reason\":\"x\"}", 1));
    }

    // ---------------------------------------------------------------- Library of Congress

    @Test
    void locUrls() {
        URI uri = URI.create(PhotoApi.locUrl("photochrom-prints", " castle ", 3, 40));
        assertEquals("/collections/photochrom-prints/", uri.getPath());
        assertTrue(uri.getQuery().contains("fo=json") && uri.getQuery().contains("sp=3") && uri.getQuery().contains("q=castle"));
        assertFalse(PhotoApi.locUrl("prokudin-gorskii", "", 1, 40).contains("q="));
    }

    @Test
    void parsesLocResponse() throws Exception {
        ArtApi.Parsed parsed = PhotoApi.parseLoc(resource("loc_sample.json"), 1, ImportSource.PHOTO);
        assertEquals(4, parsed.items().size());
        assertEquals(2, parsed.next());
        assertEquals(119, parsed.total());
        ArtItem corfe = parsed.items().getFirst();
        assertEquals(ArtSource.LOC, corfe.source());
        assertEquals("2002696569", corfe.id());
        assertEquals("Castle, Corfe Castle, England", corfe.title(), "catalogers' brackets are removed");
        assertEquals("1890", corfe.date());
        assertEquals(1024, corfe.width());
        assertEquals(763, corfe.height());
        assertEquals("https://tile.loc.gov/storage-services/service/pnp/ppmsc/08100/08198_150px.jpg", corfe.thumbUrl());
        assertEquals("https://tile.loc.gov/storage-services/service/pnp/ppmsc/08100/08198r.jpg", corfe.previewUrl());
        assertEquals("https://tile.loc.gov/storage-services/service/pnp/ppmsc/08100/08198v.jpg", corfe.downloadUrl());
        assertEquals("https://www.loc.gov/item/2002696569/", corfe.pageUrl());

        ArtItem poster = PhotoApi.parseLoc(resource("loc_sample.json"), 1, ImportSource.MUSEUM).items().getFirst();
        assertEquals(ImportSource.MUSEUM, poster.kind(), "posters and prints count as museum art");
        assertEquals(Path.of("p", "art", "loc", poster.fileName()), poster.cacheFile(Path.of("p")));
    }

    @Test
    void locSkipsRestrictedItemsAndStopsOnTheLastPage() {
        String json = """
                {"pagination":{"next":null,"total":2},"results":[
                  {"url":"https://www.loc.gov/item/1/","access_restricted":true,"image_url":["https://x/1v.jpg#h=10&w=10"]},
                  {"url":"https://www.loc.gov/item/2/","title":"Poster","image_url":["https://x/2r.jpg#h=640&w=498"]}
                ]}""";
        ArtApi.Parsed parsed = PhotoApi.parseLoc(json, 5, ImportSource.MUSEUM);
        assertEquals(List.of("2"), parsed.items().stream().map(ArtItem::id).toList());
        assertEquals(-1, parsed.next());
        assertEquals("https://x/2r.jpg", parsed.items().getFirst().downloadUrl());
    }

    // ---------------------------------------------------------------- Wallhaven

    @Test
    void wallhavenUrlsAreAlwaysSafeForWork() {
        String top = PhotoApi.wallhavenUrl("", "", 1);
        assertTrue(top.contains("categories=100") && top.contains("purity=100"));
        assertTrue(top.contains("sorting=favorites"));
        assertFalse(top.contains("apikey"));
        String typed = PhotoApi.wallhavenUrl("landscape", "alps lake", 2);
        URI.create(typed);
        assertTrue(typed.contains("q=landscape+alps+lake"));
        assertTrue(typed.contains("sorting=relevance") && typed.contains("page=2"));
    }

    @Test
    void parsesWallhavenResponse() throws Exception {
        ArtApi.Parsed parsed = PhotoApi.parseWallhaven(resource("wallhaven_sample.json"), 1, "Landscapes");
        assertEquals(24, parsed.items().size());
        assertEquals(2, parsed.next());
        ArtItem first = parsed.items().getFirst();
        assertEquals(ArtSource.WALLHAVEN, first.source());
        assertEquals(ImportSource.WALLPAPER, first.kind());
        assertEquals("rddgwm", first.id());
        assertEquals("Landscapes (wallhaven rddgwm)", first.displayName());
        assertEquals(2500, first.width());
        assertEquals(1401, first.height());
        assertEquals("https://th.wallhaven.cc/orig/rd/rddgwm.jpg", first.thumbUrl());
        assertEquals("https://w.wallhaven.cc/full/rd/wallhaven-rddgwm.jpg", first.downloadUrl());
        assertEquals(Path.of("p", "wallpapers", "wallhaven", "Landscapes (wallhaven rddgwm) (rddgwm).jpg"), first.cacheFile(Path.of("p")));
        assertEquals("png", parsed.items().get(2).extension());
    }

    @Test
    void wallhavenDropsAnythingNotSafeForWork() {
        String json = """
                {"data":[
                  {"id":"a","purity":"sketchy","file_type":"image/jpeg","path":"https://x/a.jpg","thumbs":{"original":"https://t/a.jpg"}},
                  {"id":"b","purity":"sfw","file_type":"image/gif","path":"https://x/b.gif","thumbs":{"original":"https://t/b.jpg"}},
                  {"id":"c","purity":"sfw","file_type":"image/jpeg","path":"https://x/c.jpg","thumbs":{"original":"https://t/c.jpg"}}
                ],"meta":{"current_page":3,"last_page":3,"total":3}}""";
        ArtApi.Parsed parsed = PhotoApi.parseWallhaven(json, 3, "");
        assertEquals(List.of("c"), parsed.items().stream().map(ArtItem::id).toList());
        assertEquals("Wallpaper (wallhaven c)", parsed.items().getFirst().title());
        assertEquals(-1, parsed.next());
        assertThrows(IllegalArgumentException.class, () -> PhotoApi.parseWallhaven("{\"error\":\"Too many requests\"}", 1, ""));
    }

    // ---------------------------------------------------------------- Pixabay

    @Test
    void pixabayUrls() {
        String url = PhotoApi.pixabayUrl("my key", "illustration", "", 2, 50);
        URI.create(url);
        assertTrue(url.contains("key=my+key"));
        assertTrue(url.contains("safesearch=true") && url.contains("image_type=illustration"));
        assertTrue(url.contains("editors_choice=true"), "curated picks while nothing is typed");
        assertTrue(PhotoApi.pixabayUrl("k", "photo", "x".repeat(150), 1, 50).endsWith("&q=" + "x".repeat(100)), "Pixabay allows 100 characters");
        assertTrue(PhotoApi.pixabayUrl("k", "photo", "castle", 1, 50).contains("editors_choice=false"));
    }

    @Test
    void parsesPixabayResponse() throws Exception {
        ArtApi.Parsed parsed = PhotoApi.parsePixabay(resource("pixabay_sample.json"), 1, 50);
        assertEquals(1, parsed.items().size(), "hits without a large image are skipped");
        assertEquals(2, parsed.next());
        ArtItem flower = parsed.items().getFirst();
        assertEquals(ArtSource.PIXABAY, flower.source());
        assertEquals(ImportSource.WALLPAPER, flower.kind());
        assertEquals("Blossom, bloom - Josch13", flower.displayName());
        assertEquals(1280, flower.width());
        assertEquals(720, flower.height());
        assertEquals("https://pixabay.com/get/ed6a99fd0a76647_1280.jpg", flower.downloadUrl());
        assertEquals("https://pixabay.com/get/35bbf209e13e39d2_640.jpg", flower.previewUrl());
        assertEquals("Pixabay Content License", flower.license());
        // 500 hits at most per query: page 10 of 50 is the last
        assertEquals(-1, PhotoApi.parsePixabay(resource("pixabay_sample.json"), 10, 50).next());
    }

    // ---------------------------------------------------------------- topics

    @Test
    void everyTopicHasAUsableFilter() {
        for (PhotoTopic t : PhotoTopic.values()) {
            switch (t.source()) {
                case COMMONS -> assertTrue(t.filter().startsWith("incategory:") || t.filter().startsWith("deepcat:"), t.name());
                case LOC -> assertTrue(t.filter().matches("[a-z0-9-]+"), t.name());
                case PIXABAY -> assertTrue(List.of("photo", "illustration", "vector", "all").contains(t.filter()), t.name());
                case NASA, WALLHAVEN -> assertFalse(t.filter().contains("\""), t.name());
                default -> throw new AssertionError(t + " uses an unexpected source");
            }
        }
        assertEquals(ImportSource.MUSEUM, PhotoTopic.WPA_POSTERS.kind());
        assertEquals(ImportSource.WALLPAPER, PhotoTopic.WALLS_TOP.kind());
        assertEquals(ImportSource.PHOTO, PhotoTopic.NEBULAE.kind());
    }
}
