package com.pocketgallery.art;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pocketgallery.gallery.ImportSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pocketgallery.art.Json.arr;
import static com.pocketgallery.art.Json.bool;
import static com.pocketgallery.art.Json.enc;
import static com.pocketgallery.art.Json.first;
import static com.pocketgallery.art.Json.integer;
import static com.pocketgallery.art.Json.obj;
import static com.pocketgallery.art.Json.object;
import static com.pocketgallery.art.Json.str;

/**
 * Pure (network-free) helpers for the museum APIs and Wikimedia Commons: URL building and JSON parsing for the Art
 * Institute of Chicago, the Metropolitan Museum of Art, the Cleveland Museum of Art, SMK (National Gallery of
 * Denmark) and Wikimedia Commons. Museum searches are limited to public-domain paintings (and, at the Met, Japanese
 * prints) made up to a configurable year, which keeps modern art out.
 */
public final class ArtApi {
    /** Long side of the images we download from IIIF / Wikimedia (upload limit is 4096). */
    public static final int DOWNLOAD_SIDE = 3000;
    /** Largest standard Wikimedia thumbnail width (non-standard widths are refused). */
    public static final int COMMONS_DOWNLOAD_WIDTH = 3840;
    public static final int COMMONS_PREVIEW_WIDTH = 960;
    public static final int COMMONS_THUMB_WIDTH = 250;
    /** The Met department ids searched: European Paintings and Asian Art (ukiyo-e prints, scroll paintings). */
    public static final int MET_EUROPEAN_PAINTINGS = 11;
    public static final int MET_ASIAN_ART = 6;
    /** Wikimedia Commons searches for the Museums tab: featured pictures of paintings and Google Art Project scans. */
    public static final String COMMONS_FEATURED_PAINTINGS = "deepcat:\"Featured pictures of paintings\"";
    public static final String COMMONS_ART_PROJECT = "incategory:Google_Art_Project_paintings";

    private static final String AIC_FIELDS = "id,title,artist_title,artist_display,date_display,image_id,thumbnail";
    private static final String CLEVELAND_FIELDS = "id,title,creators,creation_date,images,url";
    private static final String SMK_FIELDS = "id,object_number,titles,artist,production_date,image_thumbnail,image_iiif_id,"
            + "image_width,image_height,public_domain";
    private static final Pattern THUMB_WIDTH = Pattern.compile("/(\\d+)px-");
    private static final Pattern YEAR = Pattern.compile("\\b(1[0-9]{3}|20[0-9]{2})\\b");
    private static final Pattern PARENTHESIS_SUFFIX = Pattern.compile("\\s*\\([^()]*\\)\\s*$");
    /** Commons' multilingual templates hide machine-readable copies of the text in invisible divs. */
    private static final Pattern HIDDEN_DIV = Pattern.compile("(?is)<div[^>]*display:\\s*none[^>]*>.*?</div>");
    private static final Pattern ENGLISH = Pattern.compile("(?is)<[a-z]+[^>]*\\blang=\"en\"[^>]*>(.*?)</(div|span)>");
    /** Google Art Project file names: "Artist - Title - Google Art Project[ - edited].jpg". */
    private static final Pattern ART_PROJECT_FILE = Pattern.compile("^.+? - (.+?) - Google Art Project.*$");

    private ArtApi() {
    }

    /** Items plus paging info: {@code total} results (or -1 when unknown), {@code next} cursor (-1 = end). */
    public record Parsed(List<ArtItem> items, int total, int next) {
    }

    // ================================================================= Art Institute of Chicago

    public static String aicSearchUrl(String query, int page, int limit, int latestYear) {
        StringBuilder u = new StringBuilder("https://api.artic.edu/api/v1/artworks/search?");
        if (query != null && !query.isBlank()) u.append("q=").append(enc(query.strip())).append('&');
        u.append("limit=").append(limit)
                .append("&page=").append(Math.max(1, page))
                .append("&fields=").append(AIC_FIELDS)
                .append('&').append(enc("query[bool][filter][0][term][is_public_domain]")).append("=true")
                .append('&').append(enc("query[bool][filter][1][term][artwork_type_id]")).append("=1")
                .append('&').append(enc("query[bool][filter][2][range][date_end][lte]")).append('=').append(latestYear)
                .append('&').append(enc("query[bool][filter][3][exists][field]")).append("=image_id");
        return u.toString();
    }

    /** {@code next} is the next page number, or -1 on the last page. */
    public static Parsed parseAic(String json) {
        JsonObject root = object(json, "Art Institute of Chicago");
        String iiif = "https://www.artic.edu/iiif/2";
        JsonObject config = obj(root, "config");
        if (config != null && str(config, "iiif_url") != null) iiif = str(config, "iiif_url");
        List<ArtItem> items = new ArrayList<>();
        JsonArray data = arr(root, "data");
        if (data != null) {
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String imageId = str(o, "image_id");
                String id = str(o, "id");
                if (imageId == null || imageId.isBlank() || id == null) continue;
                String artist = str(o, "artist_title");
                if (artist == null) artist = firstLine(str(o, "artist_display"));
                int w = 0, h = 0;
                JsonObject thumb = obj(o, "thumbnail");
                if (thumb != null) {
                    int[] fit = fitWithin(integer(thumb, "width"), integer(thumb, "height"), DOWNLOAD_SIDE);
                    w = fit[0];
                    h = fit[1];
                }
                String base = iiif + "/" + imageId + "/full/";
                items.add(new ArtItem(ArtSource.AIC, id, str(o, "title"), artist, str(o, "date_display"), w, h,
                        base + "200,/0/default.jpg",
                        base + "843,/0/default.jpg",
                        base + "!" + DOWNLOAD_SIDE + "," + DOWNLOAD_SIDE + "/0/default.jpg",
                        "Public domain (CC0)", "https://www.artic.edu/artworks/" + id));
            }
        }
        JsonObject pagination = obj(root, "pagination");
        int total = pagination == null ? -1 : integer(pagination, "total");
        int current = pagination == null ? 1 : integer(pagination, "current_page");
        int pages = pagination == null ? 1 : integer(pagination, "total_pages");
        return new Parsed(items, total, current < pages ? current + 1 : -1);
    }

    // ================================================================= The Met

    public static String metSearchUrl(String query, int departmentId, int latestYear) {
        String q = query == null || query.isBlank() ? "painting" : query.strip();
        return "https://collectionapi.metmuseum.org/public/collection/v1/search?hasImages=true&departmentId=" + departmentId
                + "&dateBegin=0&dateEnd=" + latestYear + "&q=" + enc(q);
    }

    public static String metObjectUrl(long id) {
        return "https://collectionapi.metmuseum.org/public/collection/v1/objects/" + id;
    }

    public static List<Long> parseMetIds(String json) {
        JsonObject root = object(json, "The Met");
        List<Long> ids = new ArrayList<>();
        JsonArray arr = arr(root, "objectIDs");
        if (arr != null) {
            for (JsonElement el : arr) {
                try {
                    ids.add(el.getAsLong());
                } catch (RuntimeException ignored) {
                    // skip odd entries
                }
            }
        }
        return ids;
    }

    /** Interleaves several id lists (a, b, a, b, ...) without duplicates. */
    public static List<Long> interleave(List<List<Long>> lists) {
        List<Long> out = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int max = lists.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < max; i++) {
            for (List<Long> l : lists) {
                if (i < l.size() && seen.add(l.get(i))) out.add(l.get(i));
            }
        }
        return out;
    }

    /** One Met object, or null when it is not a public-domain painting/print with an image, or too recent. */
    public static ArtItem parseMetObject(String json, int latestYear) {
        JsonObject o;
        try {
            o = object(json, "The Met");
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!bool(o, "isPublicDomain")) return null;
        String image = str(o, "primaryImage");
        if (image == null || image.isBlank()) return null;
        String classification = str(o, "classification");
        String c = classification == null ? "" : classification.toLowerCase(Locale.ROOT);
        if (!c.contains("painting") && !c.contains("print")) return null;
        int end = integer(o, "objectEndDate");
        if (end > latestYear) return null;
        String small = str(o, "primaryImageSmall");
        if (small == null || small.isBlank()) small = image;
        String id = str(o, "objectID");
        if (id == null) return null;
        String url = str(o, "objectURL");
        return new ArtItem(ArtSource.MET, id, str(o, "title"), str(o, "artistDisplayName"), str(o, "objectDate"), 0, 0,
                small, small, image, "Public domain (CC0)", url == null ? "https://www.metmuseum.org/art/collection/search/" + id : url);
    }

    // ================================================================= Cleveland Museum of Art

    public static String clevelandUrl(String query, int skip, int limit, int latestYear) {
        StringBuilder u = new StringBuilder("https://openaccess-api.clevelandart.org/api/artworks/?type=Painting&cc0=1&has_image=1")
                .append("&created_before=").append(latestYear)
                .append("&limit=").append(limit)
                .append("&skip=").append(Math.max(0, skip))
                .append("&fields=").append(CLEVELAND_FIELDS);
        if (query != null && !query.isBlank()) u.append("&q=").append(enc(query.strip()));
        return u.toString();
    }

    /** {@code next} is the next skip offset, or -1 at the end. */
    public static Parsed parseCleveland(String json, int skip) {
        JsonObject root = object(json, "Cleveland Museum of Art");
        List<ArtItem> items = new ArrayList<>();
        JsonArray data = arr(root, "data");
        int raw = 0;
        if (data != null) {
            for (JsonElement el : data) {
                raw++;
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                JsonObject images = obj(o, "images");
                JsonObject web = obj(images, "web");
                JsonObject print = obj(images, "print");
                String webUrl = str(web, "url");
                if (webUrl == null) continue;
                JsonObject big = print != null && str(print, "url") != null ? print : web;
                String artist = str(first(arr(o, "creators")), "description");
                if (artist != null) artist = PARENTHESIS_SUFFIX.matcher(artist).replaceFirst("");
                String id = str(o, "id");
                if (id == null) continue;
                String page = str(o, "url");
                items.add(new ArtItem(ArtSource.CLEVELAND, id, str(o, "title"), artist, str(o, "creation_date"),
                        integer(big, "width"), integer(big, "height"), webUrl, webUrl, str(big, "url"),
                        "Public domain (CC0)", page == null ? "https://www.clevelandart.org/art/collection/search" : page));
            }
        }
        JsonObject info = obj(root, "info");
        int total = info == null ? -1 : integer(info, "total");
        int next = skip + raw;
        return new Parsed(items, total, raw == 0 || (total >= 0 && next >= total) ? -1 : next);
    }

    // ================================================================= SMK - National Gallery of Denmark

    /** Public-domain paintings with an image, finished by {@code latestYear}; English titles. */
    public static String smkUrl(String query, int offset, int rows, int latestYear) {
        String keys = query == null || query.isBlank() ? "*" : query.strip();
        return "https://api.smk.dk/api/v1/art/search/?keys=" + enc(keys)
                + "&filters=" + enc("[has_image:true],[public_domain:true],[object_names:painting]")
                + "&range=" + enc("[production_dates_end:{*;" + latestYear + "-12-31T23:59:59.999Z}]")
                + "&offset=" + Math.max(0, offset) + "&rows=" + rows + "&lang=en&fields=" + SMK_FIELDS;
    }

    /** {@code next} is the next offset, or -1 at the end. */
    public static Parsed parseSmk(String json, int offset) {
        JsonObject root = object(json, "SMK");
        List<ArtItem> items = new ArrayList<>();
        JsonArray data = arr(root, "items");
        int raw = 0;
        if (data != null) {
            for (JsonElement el : data) {
                raw++;
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (o.has("public_domain") && !bool(o, "public_domain")) continue;
                String number = str(o, "object_number");
                String iiif = str(o, "image_iiif_id");
                String native_ = str(o, "image_thumbnail");
                if (number == null || ((iiif == null || iiif.isBlank()) && native_ == null)) continue;
                int w = integer(o, "image_width");
                int h = integer(o, "image_height");
                String thumb, preview, download;
                if (iiif != null && !iiif.isBlank()) {
                    // small sizes come from SMK's thumbnail server, the download from the full IIIF server
                    String small = iiif.replace("://iip.smk.dk/", "://iip-thumb.smk.dk/");
                    thumb = small + "/full/!400,400/0/default.jpg";
                    preview = small + "/full/!1024,1024/0/default.jpg";
                    download = iiif + "/full/!" + DOWNLOAD_SIDE + "," + DOWNLOAD_SIDE + "/0/default.jpg";
                    int[] fit = fitWithin(w, h, DOWNLOAD_SIDE);
                    w = fit[0];
                    h = fit[1];
                } else {
                    thumb = preview = download = native_; // older records: one 1600 px image
                }
                String title = null;
                JsonArray titles = arr(o, "titles");
                if (titles != null) {
                    for (JsonElement t : titles) {
                        if (t.isJsonObject() && str(t.getAsJsonObject(), "title") != null) {
                            title = str(t.getAsJsonObject(), "title");
                            break;
                        }
                    }
                }
                JsonArray artists = arr(o, "artist");
                String artist = artists != null && !artists.isEmpty() && artists.get(0).isJsonPrimitive()
                        ? artists.get(0).getAsString() : null;
                String date = str(first(arr(o, "production_date")), "period");
                items.add(new ArtItem(ArtSource.SMK, number, title, artist, date, w, h, thumb, preview, download,
                        "Public domain (SMK Open)", "https://open.smk.dk/artwork/image/" + enc(number)));
            }
        }
        int found = integer(root, "found");
        int next = offset + raw;
        return new Parsed(items, found, raw == 0 || next >= found ? -1 : next);
    }

    // ================================================================= Wikimedia Commons

    /** CirrusSearch query for a topic plus optional user words; bitmap files only (no SVG diagrams). */
    public static String commonsSearch(PhotoTopic topic, String text) {
        return commonsSearch(topic.filter(), text);
    }

    public static String commonsSearch(String filter, String text) {
        StringBuilder q = new StringBuilder(filter);
        if (text != null) {
            // quotes/colons from free text would turn into (broken) search operators
            String words = text.replaceAll("[\":]", " ").strip();
            if (!words.isEmpty()) q.append(' ').append(words);
        }
        q.append(" filetype:bitmap");
        return q.toString().replaceAll("\\s+", " ").strip();
    }

    public static String commonsUrl(String search, int offset, int limit) {
        return commonsUrl(search, offset, limit, false);
    }

    /**
     * @param popular sort by how many pages use the image, which puts the iconic shots first (used when the player
     *                typed no words; otherwise relevance works better)
     */
    public static String commonsUrl(String search, int offset, int limit, boolean popular) {
        return "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=search&gsrnamespace=6&gsrlimit=" + limit + "&gsroffset=" + Math.max(0, offset)
                + (popular ? "&gsrsort=incoming_links_desc" : "")
                + "&gsrsearch=" + enc(search)
                + "&prop=imageinfo&iiprop=" + enc("url|size|mime|extmetadata")
                + "&iiurlwidth=" + COMMONS_THUMB_WIDTH
                + "&iiextmetadatafilter=" + enc("ObjectName|Artist|LicenseShortName|DateTimeOriginal");
    }

    public static Parsed parseCommons(String json) {
        return parseCommons(json, ImportSource.PHOTO, Integer.MAX_VALUE);
    }

    /**
     * {@code next} is the next search offset, or -1 at the end.
     *
     * @param kind       what the items count as (photos, or museum art for the paintings search)
     * @param latestYear files dated after this year are skipped (for paintings)
     */
    public static Parsed parseCommons(String json, ImportSource kind, int latestYear) {
        JsonObject root = object(json, "Wikimedia Commons");
        JsonObject error = obj(root, "error");
        if (error != null) {
            String info = str(error, "info");
            throw new IllegalArgumentException("Wikimedia Commons: " + (info == null ? str(error, "code") : info));
        }
        List<JsonObject> pages = new ArrayList<>();
        JsonArray arr = arr(obj(root, "query"), "pages");
        if (arr != null) for (JsonElement el : arr) if (el.isJsonObject()) pages.add(el.getAsJsonObject());
        pages.sort(Comparator.comparingInt(p -> integer(p, "index")));

        List<ArtItem> items = new ArrayList<>();
        for (JsonObject p : pages) {
            JsonObject info = first(arr(p, "imageinfo"));
            if (info == null) continue;
            String mime = str(info, "mime");
            if (mime == null || !(mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp"))) continue;
            String url = str(info, "url");
            String thumb = str(info, "thumburl");
            if (url == null) continue;
            if (thumb == null) thumb = url;
            int w = integer(info, "width");
            int h = integer(info, "height");
            String preview = w > COMMONS_PREVIEW_WIDTH ? resizeThumb(thumb, COMMONS_PREVIEW_WIDTH, url) : url;
            String download = url;
            if (w > COMMONS_DOWNLOAD_WIDTH) {
                download = resizeThumb(thumb, COMMONS_DOWNLOAD_WIDTH, url);
                if (!download.equals(url)) {
                    h = (int) Math.round((double) h * COMMONS_DOWNLOAD_WIDTH / w);
                    w = COMMONS_DOWNLOAD_WIDTH;
                }
            }
            JsonObject meta = obj(info, "extmetadata");
            String title = commonsTitle(metaValue(meta, "ObjectName"), fileTitle(str(p, "title")));
            String artist = stripHtml(metaValue(meta, "Artist"));
            String license = metaValue(meta, "LicenseShortName");
            String date = metaValue(meta, "DateTimeOriginal");
            if (date != null) {
                Matcher m = YEAR.matcher(stripHtml(date));
                date = m.find() ? m.group(1) : null;
            }
            if (date != null && Integer.parseInt(date) > latestYear) continue;
            String id = str(p, "pageid");
            if (id == null) continue;
            String page = str(info, "descriptionurl");
            items.add(new ArtItem(ArtSource.COMMONS, kind, id, title, artist, date, w, h, thumb, preview, download,
                    license == null ? "see file page" : license, page == null ? "https://commons.wikimedia.org/?curid=" + id : page));
        }
        int next = -1;
        JsonObject cont = obj(root, "continue");
        if (cont != null && cont.has("gsroffset")) next = integer(cont, "gsroffset");
        return new Parsed(items, -1, next);
    }

    /**
     * A readable title from Commons' ObjectName (often a multilingual template): the English version if there is
     * one, else the text without the hidden machine-readable parts; for Google Art Project scans without an English
     * title, the (English) title part of the file name.
     */
    public static String commonsTitle(String objectName, String fileTitle) {
        if (objectName != null) {
            Matcher en = ENGLISH.matcher(objectName);
            if (en.find()) {
                String english = stripHtml(en.group(1));
                if (english != null && !english.isBlank()) return english;
            }
        }
        if (fileTitle != null) {
            Matcher gap = ART_PROJECT_FILE.matcher(fileTitle);
            if (gap.matches()) return gap.group(1).strip();
        }
        String plain = objectName == null ? null : stripHtml(objectName);
        return plain == null || plain.isBlank() ? fileTitle : plain;
    }

    /** Swaps the width of a Wikimedia thumbnail URL ({@code .../250px-Name.jpg}); the original when it isn't one. */
    static String resizeThumb(String thumbUrl, int width, String original) {
        if (thumbUrl == null || !thumbUrl.contains("/thumb/")) return original;
        Matcher m = THUMB_WIDTH.matcher(thumbUrl);
        if (!m.find()) return original;
        return thumbUrl.substring(0, m.start()) + "/" + width + "px-" + thumbUrl.substring(m.end());
    }

    static String fileTitle(String title) {
        if (title == null) return null;
        String t = title.startsWith("File:") ? title.substring(5) : title;
        int dot = t.lastIndexOf('.');
        return dot > 0 ? t.substring(0, dot) : t;
    }

    /** Commons metadata is HTML (links around user names, templates with hidden copies of the text, etc.). */
    static String stripHtml(String s) {
        if (s == null) return null;
        String text = HIDDEN_DIV.matcher(s).replaceAll(" ");
        text = text.replaceAll("(?i)<br\\s*/?>|</?(p|div|li)[^>]*>", " ")
                .replaceAll("(?s)<[^>]*>", "")
                .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").replace(" ,", ",").strip();
        return text.length() > 120 ? text.substring(0, 120).strip() : text;
    }

    // ================================================================= helpers

    static int[] fitWithin(int w, int h, int maxSide) {
        if (w <= 0 || h <= 0) return new int[]{0, 0};
        int longest = Math.max(w, h);
        if (longest <= maxSide) return new int[]{w, h};
        double s = (double) maxSide / longest;
        return new int[]{Math.max(1, (int) Math.round(w * s)), Math.max(1, (int) Math.round(h * s))};
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String metaValue(JsonObject meta, String key) {
        return str(obj(meta, key), "value");
    }
}
