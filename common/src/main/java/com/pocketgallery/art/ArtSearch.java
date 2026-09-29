package com.pocketgallery.art;

import com.pocketgallery.gallery.ImportSource;
import com.pocketgallery.web.WebClient;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One running search across one or more sources. Each source keeps its own paging cursor; {@link #next()} asks every
 * source that still has results for a batch and interleaves them, so "All museums" mixes the collections. A source
 * that fails is dropped with a warning while the others continue. Not thread safe: callers run one {@code next()} at a
 * time (on a worker thread).
 */
public final class ArtSearch {
    /** Results per source and page for museum searches ("All museums" = 5 x 10). */
    public static final int MUSEUM_BATCH = 10;
    public static final int SINGLE_MUSEUM_BATCH = 30;
    public static final int PHOTO_BATCH = 40;
    /** Pause between requests to the Met (its firewall bans bursts). */
    static final long MET_PAUSE_MS = 100;
    /** Time the Met may spend on one page of results. */
    static final long MET_PAGE_BUDGET_MS = 6_000;
    /** Pixabay requires search results to be cached for 24 hours. */
    static final Duration PIXABAY_CACHE = Duration.ofHours(24);
    /** Sources are queried in parallel so a page takes as long as the slowest museum, not the sum. */
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "PocketGallery-art-search");
        t.setDaemon(true);
        return t;
    });

    public record Page(List<ArtItem> items, boolean hasMore, String warning) {
    }

    interface Cursor {
        ArtSource source();

        List<ArtItem> next() throws IOException;

        boolean hasMore();
    }

    private final List<Cursor> cursors;
    private final boolean[] failed;

    private ArtSearch(List<Cursor> cursors) {
        this.cursors = cursors;
        this.failed = new boolean[cursors.size()];
    }

    public static ArtSearch museums(WebClient web, String query, MuseumFilter filter, int latestYear) {
        List<ArtSource> sources = filter.sources();
        int batch = sources.size() > 1 ? MUSEUM_BATCH : SINGLE_MUSEUM_BATCH;
        List<Cursor> cursors = new ArrayList<>();
        for (ArtSource s : sources) {
            switch (s) {
                case AIC -> cursors.add(new AicCursor(web, query, latestYear, batch));
                case MET -> cursors.add(new MetCursor(web, query, latestYear, batch));
                case CLEVELAND -> cursors.add(new ClevelandCursor(web, query, latestYear, batch));
                case SMK -> cursors.add(new SmkCursor(web, query, latestYear, batch));
                case COMMONS -> {
                    // famous paintings (featured pictures) first, then the large Google Art Project scans
                    boolean popular = query == null || query.isBlank();
                    cursors.add(new CommonsCursor(web, ArtApi.commonsSearch(ArtApi.COMMONS_FEATURED_PAINTINGS, query),
                            batch, popular, ImportSource.MUSEUM, latestYear));
                    cursors.add(new CommonsCursor(web, ArtApi.commonsSearch(ArtApi.COMMONS_ART_PROJECT, query),
                            batch, popular, ImportSource.MUSEUM, latestYear));
                }
                default -> throw new IllegalArgumentException(s + " is not a museum");
            }
        }
        return new ArtSearch(cursors);
    }

    /**
     * @param pixabayKey the player's Pixabay API key (only needed for Pixabay topics)
     * @throws IllegalStateException for a Pixabay topic without a key (the message tells the player what to do)
     */
    public static ArtSearch photos(WebClient web, PhotoTopic topic, String text, String pixabayKey) {
        String words = text == null ? "" : text.strip();
        Cursor cursor = switch (topic.source()) {
            case COMMONS -> new CommonsCursor(web, ArtApi.commonsSearch(topic, words), PHOTO_BATCH, words.isEmpty(),
                    topic.kind(), Integer.MAX_VALUE);
            case NASA -> new NasaCursor(web, topic.filter(), words);
            case LOC -> new LocCursor(web, topic.filter(), words, topic.kind());
            case WALLHAVEN -> new WallhavenCursor(web, topic.filter(), words, topic.label());
            case PIXABAY -> {
                if (pixabayKey == null || pixabayKey.isBlank()) {
                    throw new IllegalStateException("Pixabay needs a free API key: log in at pixabay.com, open "
                            + "pixabay.com/api/docs and copy your key into Settings > Pixabay API key.");
                }
                yield new PixabayCursor(web, pixabayKey.strip(), topic.filter(), words);
            }
            default -> throw new IllegalArgumentException(topic.source() + " has no photo search");
        };
        return new ArtSearch(List.of(cursor));
    }

    public boolean hasMore() {
        for (int i = 0; i < cursors.size(); i++) if (!failed[i] && cursors.get(i).hasMore()) return true;
        return false;
    }

    public Page next() throws IOException {
        List<List<ArtItem>> parts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        IOException firstError = null;
        int attempted = 0;
        List<Integer> active = new ArrayList<>();
        List<Future<List<ArtItem>>> futures = new ArrayList<>();
        for (int i = 0; i < cursors.size(); i++) {
            Cursor c = cursors.get(i);
            if (failed[i] || !c.hasMore()) continue;
            attempted++;
            active.add(i);
            futures.add(POOL.submit(c::next));
        }
        for (int k = 0; k < futures.size(); k++) {
            int i = active.get(k);
            Cursor c = cursors.get(i);
            try {
                parts.add(futures.get(k).get());
            } catch (InterruptedException e) {
                futures.forEach(f -> f.cancel(true));
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            } catch (ExecutionException ex) {
                Throwable e = ex.getCause() == null ? ex : ex.getCause();
                failed[i] = true;
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                String problem = c.source().shortName() + ": " + msg;
                if (!problems.contains(problem)) problems.add(problem);
                if (firstError == null) firstError = e instanceof IOException io ? io : new IOException(msg, e);
            }
        }
        // nothing worked at all: report the error instead of an empty page
        if (attempted > 0 && parts.isEmpty() && firstError != null) {
            throw problems.size() == 1 ? new WebClient.WebException(firstError.getMessage(), firstError)
                    : new WebClient.WebException(String.join("; ", problems), firstError);
        }
        List<ArtItem> items = new ArrayList<>();
        int max = parts.stream().mapToInt(List::size).max().orElse(0);
        for (int k = 0; k < max; k++) {
            for (List<ArtItem> part : parts) if (k < part.size()) items.add(part.get(k));
        }
        String warning = problems.isEmpty() ? null : "Skipped " + String.join("; ", problems);
        return new Page(items, hasMore(), warning);
    }

    /** Parser errors (the site answered something unexpected) become messages for the player. */
    private static ArtApi.Parsed parse(java.util.function.Supplier<ArtApi.Parsed> parser) throws WebClient.WebException {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            throw new WebClient.WebException(e.getMessage(), e);
        }
    }

    // ================================================================= museum cursors

    private static final class AicCursor implements Cursor {
        /** The AIC API refuses results past offset 10,000. */
        private static final int MAX_RESULTS = 10_000;
        private final WebClient web;
        private final String query;
        private final int latestYear;
        private final int batch;
        private int page = 1;

        AicCursor(WebClient web, String query, int latestYear, int batch) {
            this.web = web;
            this.query = query;
            this.latestYear = latestYear;
            this.batch = batch;
        }

        @Override
        public ArtSource source() {
            return ArtSource.AIC;
        }

        @Override
        public boolean hasMore() {
            return page > 0 && page * batch <= MAX_RESULTS;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(ArtApi.aicSearchUrl(query, page, batch, latestYear), "Art Institute of Chicago");
            ArtApi.Parsed parsed = parse(() -> ArtApi.parseAic(json));
            page = parsed.next();
            return parsed.items();
        }
    }

    private static final class MetCursor implements Cursor {
        private final WebClient web;
        private final String query;
        private final int latestYear;
        private final int batch;
        private List<Long> ids;
        private int pos;

        MetCursor(WebClient web, String query, int latestYear, int batch) {
            this.web = web;
            this.query = query;
            this.latestYear = latestYear;
            this.batch = batch;
        }

        @Override
        public ArtSource source() {
            return ArtSource.MET;
        }

        @Override
        public boolean hasMore() {
            return ids == null || pos < ids.size();
        }

        @Override
        public List<ArtItem> next() throws IOException {
            if (ids == null) loadIds();
            List<ArtItem> items = new ArrayList<>();
            int checked = 0;
            long deadline = System.currentTimeMillis() + MET_PAGE_BUDGET_MS;
            // The search only returns ids, so every object is fetched on its own. The Met's firewall blocks the
            // whole IP for a while after short bursts, so this goes one request at a time with a pause. Many ids
            // turn out unusable (the API marks some famous paintings as not public domain, without image), so a
            // time budget keeps the page from waiting on the Met; the rest comes with the next page.
            while (items.size() < batch && pos < ids.size() && checked < batch * 3 && System.currentTimeMillis() < deadline) {
                long id = ids.get(pos++);
                checked++;
                ArtItem item;
                try {
                    item = ArtApi.parseMetObject(web.getText(ArtApi.metObjectUrl(id), "The Met"), latestYear);
                } catch (WebClient.WebException e) {
                    if (e.getMessage() != null && e.getMessage().contains("HTTP 403")) throw blocked(e);
                    continue; // one missing object doesn't matter
                }
                if (item != null) items.add(item);
                pause();
            }
            return items;
        }

        private void loadIds() throws IOException {
            List<Long> all = new ArrayList<>();
            IOException error = null;
            int ok = 0;
            // European paintings first, then Asian art (ukiyo-e, scroll paintings) - interleaving made artist
            // searches noisy because the Met's search matches loosely
            for (int dept : new int[]{ArtApi.MET_EUROPEAN_PAINTINGS, ArtApi.MET_ASIAN_ART}) {
                try {
                    String json = web.getText(ArtApi.metSearchUrl(query, dept, latestYear), "The Met");
                    try {
                        all.addAll(ArtApi.parseMetIds(json));
                    } catch (IllegalArgumentException e) {
                        throw new WebClient.WebException(e.getMessage(), e);
                    }
                    ok++;
                } catch (IOException e) {
                    if (e instanceof InterruptedIOException) throw e;
                    error = e.getMessage() != null && e.getMessage().contains("HTTP 403") ? blocked(e) : e;
                }
                pause();
            }
            if (ok == 0) throw error;
            ids = ArtApi.interleave(List.of(all)); // removes duplicates
        }

        private static WebClient.WebException blocked(IOException e) {
            return new WebClient.WebException("temporarily blocking requests (too many too fast) - try it again in a few minutes", e);
        }

        private static void pause() throws InterruptedIOException {
            try {
                Thread.sleep(MET_PAUSE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            }
        }
    }

    private static final class ClevelandCursor implements Cursor {
        private final WebClient web;
        private final String query;
        private final int latestYear;
        private final int batch;
        private int skip;

        ClevelandCursor(WebClient web, String query, int latestYear, int batch) {
            this.web = web;
            this.query = query;
            this.latestYear = latestYear;
            this.batch = batch;
        }

        @Override
        public ArtSource source() {
            return ArtSource.CLEVELAND;
        }

        @Override
        public boolean hasMore() {
            return skip >= 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(ArtApi.clevelandUrl(query, skip, batch, latestYear), "Cleveland Museum of Art");
            int from = skip;
            ArtApi.Parsed parsed = parse(() -> ArtApi.parseCleveland(json, from));
            skip = parsed.next();
            return parsed.items();
        }
    }

    private static final class SmkCursor implements Cursor {
        private final WebClient web;
        private final String query;
        private final int latestYear;
        private final int batch;
        private int offset;

        SmkCursor(WebClient web, String query, int latestYear, int batch) {
            this.web = web;
            this.query = query;
            this.latestYear = latestYear;
            this.batch = batch;
        }

        @Override
        public ArtSource source() {
            return ArtSource.SMK;
        }

        @Override
        public boolean hasMore() {
            return offset >= 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(ArtApi.smkUrl(query, offset, batch, latestYear), "SMK");
            int from = offset;
            ArtApi.Parsed parsed = parse(() -> ArtApi.parseSmk(json, from));
            offset = parsed.next();
            return parsed.items();
        }
    }

    // ================================================================= photo cursors

    private static final class CommonsCursor implements Cursor {
        private final WebClient web;
        private final String search;
        private final int batch;
        private final boolean popular;
        private final ImportSource kind;
        private final int latestYear;
        private int offset;

        CommonsCursor(WebClient web, String search, int batch, boolean popular, ImportSource kind, int latestYear) {
            this.web = web;
            this.search = search;
            this.batch = batch;
            this.popular = popular;
            this.kind = kind;
            this.latestYear = latestYear;
        }

        @Override
        public ArtSource source() {
            return ArtSource.COMMONS;
        }

        @Override
        public boolean hasMore() {
            return offset >= 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(ArtApi.commonsUrl(search, offset, batch, popular), "Wikimedia Commons");
            try {
                ArtApi.Parsed parsed = parse(() -> ArtApi.parseCommons(json, kind, latestYear));
                offset = parsed.next();
                return parsed.items();
            } catch (WebClient.WebException e) {
                offset = -1;
                throw e;
            }
        }
    }

    private static final class NasaCursor implements Cursor {
        private static final int PAGE_SIZE = 40;
        /** The NASA API refuses pages beyond 10,000 results. */
        private static final int MAX_PAGE = 10_000 / PAGE_SIZE;
        private final WebClient web;
        private final String phrase;
        private final String words;
        private int page = 1;

        NasaCursor(WebClient web, String phrase, String words) {
            this.web = web;
            this.phrase = phrase;
            this.words = words;
        }

        @Override
        public ArtSource source() {
            return ArtSource.NASA;
        }

        @Override
        public boolean hasMore() {
            return page > 0 && page <= MAX_PAGE;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(PhotoApi.nasaUrl(phrase, words, page, PAGE_SIZE), "NASA");
            int current = page;
            ArtApi.Parsed parsed = parse(() -> PhotoApi.parseNasa(json, current));
            page = parsed.next();
            return parsed.items();
        }
    }

    private static final class LocCursor implements Cursor {
        private static final int PAGE_SIZE = 40;
        private final WebClient web;
        private final String collection;
        private final String words;
        private final ImportSource kind;
        private int page = 1;

        LocCursor(WebClient web, String collection, String words, ImportSource kind) {
            this.web = web;
            this.collection = collection;
            this.words = words;
            this.kind = kind;
        }

        @Override
        public ArtSource source() {
            return ArtSource.LOC;
        }

        @Override
        public boolean hasMore() {
            return page > 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(PhotoApi.locUrl(collection, words, page, PAGE_SIZE), "Library of Congress");
            int current = page;
            ArtApi.Parsed parsed = parse(() -> PhotoApi.parseLoc(json, current, kind));
            page = parsed.next();
            return parsed.items();
        }
    }

    private static final class WallhavenCursor implements Cursor {
        private final WebClient web;
        private final String phrase;
        private final String words;
        private final String namePrefix;
        private int page = 1;

        WallhavenCursor(WebClient web, String phrase, String words, String topicLabel) {
            this.web = web;
            this.phrase = phrase;
            this.words = words;
            this.namePrefix = words.isEmpty() ? topicLabel : Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }

        @Override
        public ArtSource source() {
            return ArtSource.WALLHAVEN;
        }

        @Override
        public boolean hasMore() {
            return page > 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json = web.getText(PhotoApi.wallhavenUrl(phrase, words, page), "Wallhaven");
            int current = page;
            ArtApi.Parsed parsed = parse(() -> PhotoApi.parseWallhaven(json, current, namePrefix));
            page = parsed.next();
            return parsed.items();
        }
    }

    private static final class PixabayCursor implements Cursor {
        private static final int PAGE_SIZE = 50;
        private final WebClient web;
        private final String key;
        private final String imageType;
        private final String words;
        private int page = 1;

        PixabayCursor(WebClient web, String key, String imageType, String words) {
            this.web = web;
            this.key = key;
            this.imageType = imageType;
            this.words = words;
        }

        @Override
        public ArtSource source() {
            return ArtSource.PIXABAY;
        }

        @Override
        public boolean hasMore() {
            return page > 0;
        }

        @Override
        public List<ArtItem> next() throws IOException {
            String json;
            try {
                json = web.getTextCached(PhotoApi.pixabayUrl(key, imageType, words, page, PAGE_SIZE), "Pixabay", PIXABAY_CACHE);
            } catch (WebClient.WebException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTTP 400")) {
                    throw new WebClient.WebException("Pixabay refused the request - check your API key in Settings", e);
                }
                throw e;
            }
            int current = page;
            ArtApi.Parsed parsed = parse(() -> PhotoApi.parsePixabay(json, current, PAGE_SIZE));
            page = parsed.next();
            return parsed.items();
        }
    }
}
