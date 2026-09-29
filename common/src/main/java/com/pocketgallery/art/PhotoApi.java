package com.pocketgallery.art;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pocketgallery.gallery.ImportSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.pocketgallery.art.Json.arr;
import static com.pocketgallery.art.Json.enc;
import static com.pocketgallery.art.Json.first;
import static com.pocketgallery.art.Json.integer;
import static com.pocketgallery.art.Json.obj;
import static com.pocketgallery.art.Json.object;
import static com.pocketgallery.art.Json.str;

/**
 * Pure (network-free) URL building and JSON parsing for the Photos tab's other sources: the NASA Image and Video
 * Library, the Library of Congress, Wallhaven and Pixabay.
 */
public final class PhotoApi {
    /** NASA images smaller than this (long side, when known) are skipped - they look poor as paintings. */
    static final int NASA_MIN_SIDE = 700;
    /** NASA originals above these limits are replaced by the "medium" rendition (still >= 1280 px). */
    static final long NASA_MAX_ORIGINAL_BYTES = 60L * 1024 * 1024;
    static final int NASA_MAX_ORIGINAL_SIDE = 12_000;
    public static final int WALLHAVEN_PAGE_SIZE = 24;
    /** Pixabay's largest image without full API access. */
    public static final int PIXABAY_LARGE_SIDE = 1280;
    /** Pixabay returns at most this many hits per query. */
    public static final int PIXABAY_MAX_HITS = 500;

    private static final Pattern SIZE_FRAGMENT = Pattern.compile("#h=(\\d+)&w=(\\d+)");
    private static final Pattern LOC_ITEM_ID = Pattern.compile("/item/([^/]+)/?$");

    private PhotoApi() {
    }

    // ================================================================= NASA Image and Video Library

    public static String nasaUrl(String phrase, String text, int page, int pageSize) {
        String q = ((phrase == null ? "" : phrase) + " " + (text == null ? "" : text)).strip().replaceAll("\\s+", " ");
        return "https://images-api.nasa.gov/search?media_type=image&page=" + Math.max(1, page) + "&page_size=" + pageSize
                + (q.isEmpty() ? "&q=" + enc("space") : "&q=" + enc(q));
    }

    /** {@code next} is the next page, or -1 at the end. */
    public static ArtApi.Parsed parseNasa(String json, int page) {
        JsonObject collection = obj(object(json, "NASA"), "collection");
        if (collection == null) throw new IllegalArgumentException("Unexpected answer from NASA");
        List<ArtItem> items = new ArrayList<>();
        JsonArray arr = arr(collection, "items");
        if (arr != null) {
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                JsonObject data = first(arr(o, "data"));
                if (data == null || !"image".equals(str(data, "media_type"))) continue;
                String nasaId = str(data, "nasa_id");
                if (nasaId == null) continue;
                JsonObject preview = null, small = null, medium = null, original = null;
                JsonArray links = arr(o, "links");
                if (links != null) {
                    for (JsonElement l : links) {
                        if (!l.isJsonObject()) continue;
                        JsonObject link = l.getAsJsonObject();
                        String href = str(link, "href");
                        if (href == null) continue;
                        String rel = str(link, "rel");
                        if ("preview".equals(rel)) preview = link;
                        else if ("canonical".equals(rel)) original = link;
                        else if (href.contains("~small.")) small = link;
                        else if (href.contains("~medium.") || href.contains("~large.")) {
                            if (medium == null || integer(link, "width") > integer(medium, "width")) medium = link;
                        }
                    }
                }
                if (preview == null && small == null) continue;
                JsonObject download = usableOriginal(original) ? original : medium != null ? medium : original;
                if (download == null) continue;
                int w = integer(download, "width");
                int h = integer(download, "height");
                if (w > 0 && h > 0 && Math.max(w, h) < NASA_MIN_SIDE) continue;
                String thumbUrl = str(preview != null ? preview : small, "href");
                String previewUrl = str(small != null ? small : preview, "href");
                if (medium != null && integer(medium, "width") <= 1280) previewUrl = str(medium, "href");
                String credit = firstNonBlank(str(data, "secondary_creator"), str(data, "photographer"), str(data, "center"));
                String created = str(data, "date_created");
                String year = created != null && created.length() >= 4 ? created.substring(0, 4) : null;
                items.add(new ArtItem(ArtSource.NASA, nasaId, str(data, "title"), credit, year, w, h,
                        https(thumbUrl), https(previewUrl), https(str(download, "href")),
                        "No copyright (NASA media)", "https://images.nasa.gov/details/" + enc(nasaId).replace("+", "%20")));
            }
        }
        boolean more = false;
        JsonArray links = arr(collection, "links");
        if (links != null) {
            for (JsonElement l : links) {
                if (l.isJsonObject() && "next".equals(str(l.getAsJsonObject(), "rel"))) more = true;
            }
        }
        int total = integer(obj(collection, "metadata"), "total_hits");
        return new ArtApi.Parsed(items, total, more ? page + 1 : -1);
    }

    private static boolean usableOriginal(JsonObject original) {
        if (original == null) return false;
        String href = str(original, "href");
        if (href == null) return false;
        String lower = href.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png"))) return false;
        long size = integer(original, "size");
        int w = integer(original, "width");
        int h = integer(original, "height");
        return size <= NASA_MAX_ORIGINAL_BYTES && w <= NASA_MAX_ORIGINAL_SIDE && h <= NASA_MAX_ORIGINAL_SIDE;
    }

    // ================================================================= Library of Congress

    public static String locUrl(String collection, String text, int page, int count) {
        String words = text == null ? "" : text.strip();
        return "https://www.loc.gov/collections/" + collection + "/?fo=json&at=results,pagination&c=" + count
                + "&sp=" + Math.max(1, page) + (words.isEmpty() ? "" : "&q=" + enc(words));
    }

    /** {@code next} is the next page, or -1 at the end. */
    public static ArtApi.Parsed parseLoc(String json, int page, ImportSource kind) {
        JsonObject root = object(json, "Library of Congress");
        List<ArtItem> items = new ArrayList<>();
        JsonArray results = arr(root, "results");
        if (results != null) {
            for (JsonElement el : results) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (Json.bool(o, "access_restricted")) continue;
                JsonArray urls = arr(o, "image_url");
                if (urls == null || urls.isEmpty()) continue;
                // image_url lists the same picture in growing sizes, each tagged "#h=..&w=.."
                String smallest = null, medium = null, largest = null;
                int smallestW = Integer.MAX_VALUE, largestW = 0, largestH = 0, mediumW = 0;
                for (JsonElement u : urls) {
                    if (!u.isJsonPrimitive()) continue;
                    String url = u.getAsString();
                    if (url.contains(".gif")) continue;
                    Matcher m = SIZE_FRAGMENT.matcher(url);
                    int h = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    int w = h > 0 ? Integer.parseInt(m.group(2)) : 0;
                    String clean = url.replaceFirst("#.*$", "");
                    if (w > 0 && w < smallestW) {
                        smallestW = w;
                        smallest = clean;
                    }
                    if (w > largestW) {
                        largestW = w;
                        largestH = h;
                        largest = clean;
                    }
                    if (w > mediumW && Math.max(w, h) <= 700) {
                        mediumW = w;
                        medium = clean;
                    }
                }
                if (largest == null) continue;
                if (smallest == null) smallest = largest;
                if (medium == null) medium = largest;
                String url = str(o, "url");
                if (url == null) url = str(o, "id");
                Matcher idm = url == null ? null : LOC_ITEM_ID.matcher(url);
                if (idm == null || !idm.find()) continue;
                String date = str(o, "date");
                String year = date != null && date.length() >= 4 && Character.isDigit(date.charAt(0)) ? date.substring(0, 4) : null;
                items.add(new ArtItem(ArtSource.LOC, kind, idm.group(1), locTitle(str(o, "title")), null, year,
                        largestW, largestH, smallest, medium, largest, "No known restrictions (Library of Congress)",
                        url.replaceFirst("^http:", "https:")));
            }
        }
        JsonObject pagination = obj(root, "pagination");
        String next = str(pagination, "next");
        return new ArtApi.Parsed(items, integer(pagination, "total"), next != null && !next.isBlank() ? page + 1 : -1);
    }

    /** LoC catalogers put supplied titles in brackets: "[Castle, Corfe Castle, England]". */
    static String locTitle(String title) {
        if (title == null) return null;
        String t = title.strip();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1).strip();
        return t.length() > 150 ? t.substring(0, 150).strip() : t;
    }

    // ================================================================= Wallhaven

    /**
     * General-category, safe-for-work wallpapers only (no API key is ever sent, so nothing else could be returned).
     * Without search words the most favourited come first; with words, the best matches.
     */
    public static String wallhavenUrl(String phrase, String text, int page) {
        String q = ((phrase == null ? "" : phrase) + " " + (text == null ? "" : text)).strip().replaceAll("\\s+", " ");
        boolean typed = text != null && !text.isBlank();
        return "https://wallhaven.cc/api/v1/search?categories=100&purity=100"
                + "&sorting=" + (typed ? "relevance" : "favorites") + "&order=desc"
                + "&page=" + Math.max(1, page) + (q.isEmpty() ? "" : "&q=" + enc(q));
    }

    /**
     * {@code next} is the next page, or -1 at the end.
     *
     * @param namePrefix Wallhaven has no titles, so paintings are named "{@code <namePrefix> (wallhaven <id>)}"
     */
    public static ArtApi.Parsed parseWallhaven(String json, int page, String namePrefix) {
        JsonObject root = object(json, "Wallhaven");
        String error = str(root, "error");
        if (error != null) throw new IllegalArgumentException("Wallhaven: " + error);
        List<ArtItem> items = new ArrayList<>();
        JsonArray data = arr(root, "data");
        if (data != null) {
            for (JsonElement el : data) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!"sfw".equals(str(o, "purity"))) continue; // never happens with purity=100; belt and braces
                String type = str(o, "file_type");
                if (type == null || !(type.equals("image/jpeg") || type.equals("image/png"))) continue;
                String id = str(o, "id");
                String path = str(o, "path");
                JsonObject thumbs = obj(o, "thumbs");
                // "original" thumbnails keep the aspect ratio (small/large are cropped to 3:2)
                String thumb = str(thumbs, "original");
                if (id == null || path == null || thumb == null) continue;
                String title = (namePrefix == null || namePrefix.isBlank() ? "Wallpaper" : namePrefix) + " (wallhaven " + id + ")";
                items.add(new ArtItem(ArtSource.WALLHAVEN, id, title, null, null,
                        integer(o, "dimension_x"), integer(o, "dimension_y"), thumb, thumb, path,
                        "Copyright: original artist (via Wallhaven)", str(o, "url")));
            }
        }
        JsonObject meta = obj(root, "meta");
        int last = integer(meta, "last_page");
        return new ArtApi.Parsed(items, integer(meta, "total"), page < last ? page + 1 : -1);
    }

    // ================================================================= Pixabay

    /** Safe search is always on. {@code imageType}: "illustration", "photo", "vector" or "all". */
    public static String pixabayUrl(String key, String imageType, String text, int page, int perPage) {
        String q = text == null ? "" : text.strip();
        if (q.length() > 100) q = q.substring(0, 100);
        return "https://pixabay.com/api/?key=" + enc(key) + "&image_type=" + enc(imageType)
                + "&safesearch=true&order=popular&editors_choice=" + q.isEmpty()
                + "&per_page=" + perPage + "&page=" + Math.max(1, page) + (q.isEmpty() ? "" : "&q=" + enc(q));
    }

    /** {@code next} is the next page, or -1 at the end. */
    public static ArtApi.Parsed parsePixabay(String json, int page, int perPage) {
        JsonObject root = object(json, "Pixabay");
        List<ArtItem> items = new ArrayList<>();
        JsonArray hits = arr(root, "hits");
        if (hits != null) {
            for (JsonElement el : hits) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String id = str(o, "id");
                String large = str(o, "largeImageURL");
                String web = str(o, "webformatURL");
                String preview = str(o, "previewURL");
                if (id == null || large == null) continue;
                int[] size = ArtApi.fitWithin(integer(o, "imageWidth"), integer(o, "imageHeight"), PIXABAY_LARGE_SIDE);
                items.add(new ArtItem(ArtSource.PIXABAY, id, pixabayTitle(str(o, "tags")), str(o, "user"), null,
                        size[0], size[1], web != null ? web : preview != null ? preview : large, web != null ? web : large,
                        large, "Pixabay Content License", str(o, "pageURL")));
            }
        }
        int totalHits = Math.min(PIXABAY_MAX_HITS, integer(root, "totalHits"));
        return new ArtApi.Parsed(items, totalHits, page * perPage < totalHits ? page + 1 : -1);
    }

    /** "blossom, bloom, flower" -> "Blossom, bloom". */
    static String pixabayTitle(String tags) {
        if (tags == null || tags.isBlank()) return "Pixabay image";
        String[] parts = tags.split(",");
        String t = parts[0].strip() + (parts.length > 1 ? ", " + parts[1].strip() : "");
        return t.isEmpty() ? "Pixabay image" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // ================================================================= helpers

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.strip();
        return null;
    }

    /** NASA's asset links are plain http and may contain spaces. */
    private static String https(String url) {
        return url == null ? null : url.replaceFirst("^http://", "https://").replace(" ", "%20");
    }
}
