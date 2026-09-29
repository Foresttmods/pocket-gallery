package com.pocketgallery;

import com.pocketgallery.art.ArtApi;
import com.pocketgallery.art.ArtItem;
import com.pocketgallery.art.ArtSource;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsers are tested against real responses captured once from each API (see src/test/resources). */
class ArtApiTest {
    private static String resource(String name) throws Exception {
        try (InputStream in = ArtApiTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---------------------------------------------------------------- Art Institute of Chicago

    @Test
    void aicUrlIsValidAndFiltersClassicPaintings() {
        String url = ArtApi.aicSearchUrl("claude monet", 2, 12, 1925);
        URI uri = URI.create(url); // brackets must be encoded or URI.create throws
        assertEquals("api.artic.edu", uri.getHost());
        assertTrue(url.contains("q=claude+monet"));
        assertTrue(url.contains("&page=2") && url.contains("limit=12"));
        assertTrue(uri.getQuery().contains("query[bool][filter][2][range][date_end][lte]=1925"));
        assertTrue(uri.getQuery().contains("[is_public_domain]=true"));
        assertFalse(ArtApi.aicSearchUrl("  ", 1, 12, 1925).contains("q="));
    }

    @Test
    void parsesAicResponse() throws Exception {
        ArtApi.Parsed parsed = ArtApi.parseAic(resource("aic_search_sample.json"));
        assertEquals(3, parsed.items().size());
        assertEquals(2, parsed.next());
        ArtItem first = parsed.items().getFirst();
        assertEquals(ArtSource.AIC, first.source());
        assertEquals("16568", first.id());
        assertEquals("Water Lilies", first.title());
        assertEquals("Claude Monet", first.artist());
        assertEquals("Water Lilies - Claude Monet", first.displayName());
        // 8808 x 8460 original, downloaded confined to 3000
        assertEquals(3000, first.width());
        assertEquals(2881, first.height());
        assertTrue(first.downloadUrl().endsWith("/full/!3000,3000/0/default.jpg"));
        assertTrue(first.thumbUrl().contains("/full/200,/0/"));
        assertEquals("jpg", first.extension());
        assertEquals("https://www.artic.edu/artworks/16568", first.pageUrl());
        assertEquals("art:aic:16568", first.key());
    }

    // ---------------------------------------------------------------- The Met

    @Test
    void parsesMetObjects() throws Exception {
        ArtItem shoes = ArtApi.parseMetObject(resource("met_object_sample.json"), 1925);
        assertNotNull(shoes);
        assertEquals(ArtSource.MET, shoes.source());
        assertEquals("436533", shoes.id());
        assertEquals("Shoes", shoes.title());
        assertEquals("Vincent van Gogh", shoes.artist());
        assertTrue(shoes.downloadUrl().contains("/original/"));
        assertTrue(shoes.thumbUrl().contains("/web-large/"));
        assertEquals(0, shoes.width()); // the Met doesn't report pixel sizes

        ArtItem wave = ArtApi.parseMetObject(resource("met_object_other_sample.json"), 1925);
        assertNotNull(wave, "ukiyo-e prints are accepted");
        assertEquals("Katsushika Hokusai", wave.artist());

        assertNull(ArtApi.parseMetObject(resource("met_object_sample.json"), 1850), "made after the cut-off year");
    }

    @Test
    void metRejectsUnusableObjects() {
        assertNull(ArtApi.parseMetObject("{\"objectID\":1,\"isPublicDomain\":true,\"primaryImage\":\"https://x/a.jpg\",\"classification\":\"Sculpture\"}", 1925));
        assertNull(ArtApi.parseMetObject("{\"objectID\":1,\"isPublicDomain\":false,\"primaryImage\":\"https://x/a.jpg\",\"classification\":\"Paintings\"}", 1925));
        assertNull(ArtApi.parseMetObject("{\"objectID\":1,\"isPublicDomain\":true,\"primaryImage\":\"\",\"classification\":\"Paintings\"}", 1925));
        assertNull(ArtApi.parseMetObject("{\"message\":\"Not a valid object\"}", 1925));
        assertNull(ArtApi.parseMetObject("<html>", 1925));
    }

    @Test
    void metSearchAndInterleave() {
        String url = ArtApi.metSearchUrl("", ArtApi.MET_EUROPEAN_PAINTINGS, 1925);
        URI.create(url);
        assertTrue(url.contains("departmentId=11") && url.contains("dateEnd=1925") && url.contains("q=painting"));
        assertEquals(List.of(3L, 1L, 2L), ArtApi.parseMetIds("{\"total\":3,\"objectIDs\":[3,1,2]}"));
        assertEquals(List.of(), ArtApi.parseMetIds("{\"total\":0,\"objectIDs\":null}"));
        assertEquals(List.of(1L, 2L, 4L, 3L), ArtApi.interleave(List.of(List.of(1L, 2L, 3L), List.of(2L, 4L))));
    }

    // ---------------------------------------------------------------- Cleveland

    @Test
    void parsesClevelandResponse() throws Exception {
        URI.create(ArtApi.clevelandUrl("claude monet", 24, 12, 1925));
        ArtApi.Parsed parsed = ArtApi.parseCleveland(resource("cleveland_sample.json"), 0);
        assertEquals(2, parsed.items().size());
        assertEquals(12, parsed.total());
        assertEquals(2, parsed.next());
        ArtItem red = parsed.items().getFirst();
        assertEquals(ArtSource.CLEVELAND, red.source());
        assertEquals("The Red Kerchief", red.title());
        assertEquals("Claude Monet", red.artist(), "'(French, 1840-1926)' is removed");
        assertEquals(2732, red.width());
        assertEquals(3400, red.height());
        assertTrue(red.downloadUrl().endsWith("_print.jpg"));
        assertTrue(red.thumbUrl().endsWith("_web.jpg"));
        // last page
        assertEquals(-1, ArtApi.parseCleveland(resource("cleveland_sample.json"), 10).next());
    }

    // ---------------------------------------------------------------- Wikimedia Commons

    @Test
    void parsesCommonsResponse() throws Exception {
        ArtApi.Parsed parsed = ArtApi.parseCommons(resource("commons_sample.json"));
        assertEquals(3, parsed.items().size());
        assertEquals(3, parsed.next());
        ArtItem first = parsed.items().getFirst(); // sorted by search rank ("index"), not page order
        assertEquals(ArtSource.COMMONS, first.source());
        assertEquals("27899216", first.id());
        assertEquals("Schloss Neuschwanstein 2013", first.title());
        assertEquals("Thomas Wolf, www.foto-tw.de", first.artist());
        assertEquals("2013", first.date());
        assertEquals("CC BY-SA 3.0 de", first.license());
        // 5979 x 4468 original -> the largest standard thumbnail (3840) is downloaded instead
        assertEquals(3840, first.width());
        assertEquals(2870, first.height());
        assertTrue(first.downloadUrl().contains("/3840px-"), first.downloadUrl());
        assertTrue(first.previewUrl().contains("/960px-"));
        assertTrue(first.thumbUrl().contains("/250px-"));
        assertEquals("jpg", first.extension());

        ArtItem leeds = parsed.items().get(1);
        assertEquals("Leeds Castle, Kent, England 3 - May 09", leeds.title());
        assertEquals("Diliff", leeds.artist());
    }

    @Test
    void commonsErrorsAndQueries() {
        assertThrows(IllegalArgumentException.class,
                () -> ArtApi.parseCommons("{\"error\":{\"code\":\"x\",\"info\":\"Search is currently too busy\"}}"));
        assertEquals(0, ArtApi.parseCommons("{\"batchcomplete\":true}").items().size());
        assertEquals(-1, ArtApi.parseCommons("{\"batchcomplete\":true}").next());

        assertEquals("deepcat:\"Featured pictures of castles\" castle Bavaria x filetype:bitmap",
                ArtApi.commonsSearch(PhotoTopic.CASTLES, " Bavaria \"x:\" "));
        assertEquals("incategory:Featured_pictures_of_bridges filetype:bitmap", ArtApi.commonsSearch(PhotoTopic.BRIDGES, null));
        assertTrue(PhotoTopic.LANDSCAPES.filter().startsWith("incategory:Featured_pictures_of_landscapes|Featured_pictures_of_mountains|"));
        URI uri = URI.create(ArtApi.commonsUrl(ArtApi.commonsSearch(PhotoTopic.LANDSCAPES, "alps"), 40, 40));
        assertTrue(uri.getQuery().contains("gsroffset=40"));
        assertTrue(uri.getQuery().contains("iiprop=url|size|mime|extmetadata"));
    }

    @Test
    void parsesCommonsPaintings() throws Exception {
        String json = resource("commons_paintings_sample.json");
        ArtApi.Parsed parsed = ArtApi.parseCommons(json, ImportSource.MUSEUM, 1925);
        assertEquals(4, parsed.items().size());
        assertEquals(4, parsed.next());
        // multilingual templates: the English title, without the hidden "QS:" copies
        assertEquals(List.of("Springtime", "The Magpie", "The Saint-Lazare Station",
                        "The Rue Montorgueil in Paris. Celebration of June 30, 1878"),
                parsed.items().stream().map(ArtItem::title).toList());
        ArtItem magpie = parsed.items().get(1);
        assertEquals("Claude Monet", magpie.artist());
        assertEquals("1868", magpie.date());
        assertEquals("Public domain", magpie.license());
        assertEquals(ImportSource.MUSEUM, magpie.kind());
        assertEquals(Path.of("p", "art", "commons", magpie.fileName()), magpie.cacheFile(Path.of("p")));
        assertTrue(magpie.thumbUrl().startsWith("https://thumb.wikimedia.org/"));
        assertTrue(magpie.downloadUrl().contains("/3840px-"));
        // the year filter uses the file's date
        assertEquals(List.of("The Magpie"), ArtApi.parseCommons(json, ImportSource.MUSEUM, 1870).items().stream().map(ArtItem::title).toList());
    }

    @Test
    void commonsTitles() {
        assertEquals("Bridge", ArtApi.commonsTitle("<span lang=\"en\">Bridge</span>", "whatever"));
        assertEquals("Water Lilies", ArtApi.commonsTitle(null, "Claude Monet - Water Lilies - Google Art Project - edited"));
        assertEquals("Le Pont", ArtApi.commonsTitle("<i>Le Pont</i><div style=\"display: none;\">title QS:P1476,fr:\"Le Pont\"</div>", "Pont.jpg"));
        assertEquals("File name", ArtApi.commonsTitle("", "File name"));
    }

    // ---------------------------------------------------------------- SMK

    @Test
    void parsesSmkResponse() throws Exception {
        String url = ArtApi.smkUrl("", 20, 10, 1925);
        URI uri = URI.create(url);
        assertEquals("api.smk.dk", uri.getHost());
        assertTrue(uri.getQuery().contains("keys=*"));
        assertTrue(uri.getQuery().contains("[public_domain:true]") && uri.getQuery().contains("[object_names:painting]"));
        assertTrue(uri.getQuery().contains("[production_dates_end:{*;1925-12-31T23:59:59.999Z}]"));
        assertTrue(url.contains("offset=20") && url.contains("rows=10"));

        ArtApi.Parsed parsed = ArtApi.parseSmk(resource("smk_sample.json"), 0);
        assertEquals(4, parsed.items().size());
        assertEquals(4503, parsed.total());
        assertEquals(4, parsed.next());
        ArtItem wood = parsed.items().getFirst();
        assertEquals(ArtSource.SMK, wood.source());
        assertEquals(ImportSource.MUSEUM, wood.kind());
        assertEquals("KMS8914", wood.id());
        assertEquals("Wood with Crooked Tree - Mogens Ballin", wood.displayName());
        assertEquals("1891-1892", wood.date());
        // 9581 x 11258 master, downloaded confined to 3000
        assertEquals(2553, wood.width());
        assertEquals(3000, wood.height());
        assertEquals("https://iip.smk.dk/iiif/jp2/gf06g715p_kms8914.tif.jp2/full/!3000,3000/0/default.jpg", wood.downloadUrl());
        assertEquals("https://iip-thumb.smk.dk/iiif/jp2/gf06g715p_kms8914.tif.jp2/full/!400,400/0/default.jpg", wood.thumbUrl());
        assertEquals("https://open.smk.dk/artwork/image/KMS8914", wood.pageUrl());
        assertEquals("View from a Road near Næstved, Zealand", parsed.items().get(1).title());
        // the last page
        assertEquals(-1, ArtApi.parseSmk(resource("smk_sample.json"), 4500).next());
    }

    @Test
    void smkOlderRecordsWithoutIiifUseTheirSingleImage() {
        String json = """
                {"found":1,"items":[{"object_number":"KMS3109","titles":[{"title":"Job"}],"artist":["Kristian Zahrtmann"],
                  "image_thumbnail":"https://api.smk.dk/api/v1/thumbnail/x.jpg","image_width":1600,"image_height":1246,
                  "public_domain":true}]}""";
        ArtItem job = ArtApi.parseSmk(json, 0).items().getFirst();
        assertEquals("https://api.smk.dk/api/v1/thumbnail/x.jpg", job.downloadUrl());
        assertEquals(job.downloadUrl(), job.thumbUrl());
        assertEquals(1600, job.width());
    }

    // ---------------------------------------------------------------- items

    @Test
    void cacheFilesAreReadableAndSafe() {
        ArtItem item = new ArtItem(ArtSource.AIC, "16568", "Water: Lilies? \"Blue\"/Pond.", "Claude Monet", "1906",
                0, 0, "t", "p", "https://www.artic.edu/iiif/2/x/full/!3000,3000/0/default.jpg", "PD", "u");
        assertEquals("Water_ Lilies_ _Blue__Pond. - Claude Monet (16568).jpg", item.fileName());
        Path root = Path.of("paintings");
        assertEquals(root.resolve("art").resolve("aic").resolve(item.fileName()), item.cacheFile(root));

        ArtItem photo = new ArtItem(ArtSource.COMMONS, "42", "Castle.", null, null, 0, 0, "t", "p",
                "https://upload.wikimedia.org/a/b/Castle.png?utm_source=x", "CC0", "u");
        assertEquals("Castle", photo.displayName().replaceAll("[.]$", ""));
        assertEquals("png", photo.extension());
        assertEquals(root.resolve("photos").resolve("commons").resolve("Castle (42).png"), photo.cacheFile(root));
    }
}
