package com.pocketgallery.web;

import com.pocketgallery.BuildInfo;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Blocking HTTP client for the museum and photo sources (call from worker threads). Rate limits (HTTP 429/503) are
 * retried a couple of times with a pause, honouring {@code Retry-After}, because Wikimedia in particular throttles
 * bursts quickly. Errors are {@link WebException}s whose message can be shown to the player as-is.
 */
public final class WebClient {
    /**
     * Identifies the mod to the sites (Wikimedia asks for a contact URL; it comes from {@code mod_homepage} in
     * gradle.properties when set).
     */
    public static final String USER_AGENT = "PocketGallery/" + BuildInfo.VERSION + " ("
            + (BuildInfo.HOMEPAGE.isBlank() ? "" : "+" + BuildInfo.HOMEPAGE + "; ") + "Minecraft mod; client-side image importer)";
    private static final long MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024;
    private static final int MAX_RETRIES = 2;
    private static final long MAX_RETRY_WAIT_MS = 8_000;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** Folder for {@link #getTextCached} (resolved lazily: the game folder isn't known at construction). */
    private final Supplier<Path> cacheDir;

    public WebClient(Supplier<Path> cacheDir) {
        this.cacheDir = cacheDir;
    }

    /** Exception carrying a message that can be shown to the user as-is. */
    public static final class WebException extends IOException {
        public WebException(String message) {
            super(message);
        }

        public WebException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** GET returning the body as text (JSON APIs). */
    public String getText(String url, String site) throws IOException {
        HttpResponse<String> r = sendWithRetry(request(url, Duration.ofSeconds(30)).header("Accept", "application/json").build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), site);
        if (r.statusCode() != 200) throw new WebException(errorMessage(site, r.statusCode()));
        return r.body();
    }

    /**
     * Like {@link #getText}, but answers younger than {@code maxAge} come from a disk cache (some APIs require
     * caching). The file name is a hash of the URL, so API keys in the URL never end up in file names.
     */
    public String getTextCached(String url, String site, Duration maxAge) throws IOException {
        Path file = null;
        try {
            Path dir = cacheDir.get();
            if (dir != null) file = dir.resolve(sha256(url) + ".json");
        } catch (RuntimeException ignored) {
            // no cache folder: just fetch
        }
        if (file != null && Files.isRegularFile(file)) {
            try {
                if (Files.getLastModifiedTime(file).toMillis() > System.currentTimeMillis() - maxAge.toMillis()) {
                    return Files.readString(file, StandardCharsets.UTF_8);
                }
            } catch (IOException ignored) {
                // unreadable cache entry: fetch again
            }
        }
        String text = getText(url, site);
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                pruneCache(file.getParent(), maxAge);
            } catch (IOException ignored) {
                // caching is best effort
            }
        }
        return text;
    }

    /** Deletes cache entries older than {@code maxAge}. */
    private static void pruneCache(Path dir, Duration maxAge) {
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // try again next time
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** Downloads a small file fully into memory (thumbnails, previews). */
    public byte[] fetchBytes(String url, String site) throws IOException {
        return fetchBytes(url, site, null);
    }

    /** As {@link #fetchBytes(String, String)}, sending {@code referer} (null for none) for sites that block hotlinking. */
    public byte[] fetchBytes(String url, String site, String referer) throws IOException {
        HttpResponse<byte[]> r = sendWithRetry(withReferer(request(url, Duration.ofSeconds(30)), referer).build(),
                HttpResponse.BodyHandlers.ofByteArray(), site);
        if (r.statusCode() != 200) throw new WebException(errorMessage(site, r.statusCode()));
        return r.body();
    }

    /** Downloads to {@code target} via a temp file, so a cancelled download never leaves a broken cache entry. */
    public void download(String url, Path target, String site) throws IOException {
        download(url, target, site, null);
    }

    /** As {@link #download(String, Path, String)}, sending {@code referer} (null for none). */
    public void download(String url, Path target, String site, String referer) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        try {
            HttpResponse<Path> r = sendWithRetry(withReferer(request(url, Duration.ofMinutes(5)), referer).build(), HttpResponse.BodyHandlers.ofFile(tmp), site);
            if (r.statusCode() != 200) throw new WebException("Download failed: " + errorMessage(site, r.statusCode()));
            if (Files.size(tmp) > MAX_DOWNLOAD_BYTES) throw new WebException("File too large");
            if (Files.size(tmp) == 0) throw new WebException("Download failed: empty file from " + site);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static String errorMessage(String site, int status) {
        return switch (status) {
            case 429 -> site + " is rate limiting requests - wait a minute and try again.";
            case 403 -> site + " refused the request (HTTP 403).";
            case 404 -> "Not found on " + site + " (HTTP 404).";
            case 500, 502, 503, 504 -> site + " is having trouble (HTTP " + status + "), try again later.";
            default -> site + " error HTTP " + status;
        };
    }

    private static HttpRequest.Builder request(String url, Duration timeout) throws WebException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new WebException("Invalid URL: " + url, e);
        }
        return HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT).GET();
    }

    private static HttpRequest.Builder withReferer(HttpRequest.Builder builder, String referer) {
        return referer == null || referer.isBlank() ? builder : builder.header("Referer", referer);
    }

    private <T> HttpResponse<T> sendWithRetry(HttpRequest request, HttpResponse.BodyHandler<T> handler, String site) throws IOException {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<T> r = send(request, handler, site);
            int status = r.statusCode();
            if ((status != 429 && status != 503) || attempt >= MAX_RETRIES) return r;
            long wait = retryAfterMs(r, attempt);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Cancelled");
            }
        }
    }

    private static long retryAfterMs(HttpResponse<?> r, int attempt) {
        long fallback = 1_500L * (attempt + 1);
        return r.headers().firstValue("Retry-After").map(v -> {
            try {
                return Math.min(MAX_RETRY_WAIT_MS, Math.max(500, Long.parseLong(v.trim()) * 1000));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }).orElse(fallback);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler, String site) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled");
        } catch (UnknownHostException | ConnectException | HttpConnectTimeoutException e) {
            throw new WebException("Can't reach " + request.uri().getHost() + " - are you offline?", e);
        } catch (HttpTimeoutException e) {
            throw new WebException(site + " took too long to answer, try again.", e);
        } catch (IOException e) {
            throw new WebException("Network error: " + e.getMessage(), e);
        }
    }
}
