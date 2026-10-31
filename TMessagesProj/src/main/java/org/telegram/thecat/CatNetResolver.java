package org.telegram.thecat;

import android.content.Context;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.thecat.catnet.mobile.Client;
import org.telegram.thecat.catnet.mobile.Mobile;

import java.io.File;

/**
 * Finds a reachable Cat server address via the-cat-network-base (catnet), so the
 * app is not pinned to a single IP a censor can block. Source: {@code network-base/}.
 *
 * <p>Seed-first: callers keep using the configured host:port until — and unless —
 * this resolves a reachable endpoint. A resolve failure never breaks connectivity;
 * the caller simply falls back to the seed. Resolution runs off the main thread and
 * caches to {@code catnet-servers.json} in the app cache dir, so a second launch
 * resolves from cache before any network layer runs.
 *
 * <p>{@link #start} is the once-per-launch, cache-first entry. {@link #refresh} is
 * the address-finder re-run for when the current endpoint stops answering: it
 * discards the cached registry document first, so Resolve skips its fresh-cache
 * fast-path and actually re-probes the seed / refetches the document instead of
 * returning the address that just failed (the mobile AAR exposes no force flag;
 * the Go cache reads the file from disk on every resolve, so deleting it is the
 * same thing).
 */
public final class CatNetResolver {

    private static final long TIMEOUT_MS = 12000L;
    // Rate-limit re-runs: the native connection-stall callback and the
    // connectivity receiver can both fire in bursts while an outage lasts.
    private static final long REFRESH_MIN_INTERVAL_MS = 15000L;
    // Must match the cache path mobile.New() builds inside the AAR.
    private static final String CACHE_FILE = "catnet-servers.json";

    private static volatile CatNetResolver instance;

    private Client client;
    private String resolvedHost;
    private int resolvedPort;
    private boolean started;
    private boolean running;
    private long lastAttemptAt;

    public static CatNetResolver getInstance() {
        if (instance == null) {
            synchronized (CatNetResolver.class) {
                if (instance == null) {
                    instance = new CatNetResolver();
                }
            }
        }
        return instance;
    }

    private CatNetResolver() {
    }

    /** Resolved reachable host, or null if not resolved yet / on failure. */
    public synchronized String getResolvedHost() {
        return resolvedHost;
    }

    public synchronized int getResolvedPort() {
        return resolvedPort;
    }

    public synchronized boolean hasResolved() {
        return resolvedHost != null;
    }

    /**
     * Kick off resolution in the background (idempotent). {@code seedHost:seedPort}
     * is the configured fallback: used as a probe target and kept if resolution
     * fails. {@code onNewAddress} runs (on a background thread) whenever the
     * resolved address CHANGES, so the caller can re-point a live connection.
     */
    public void start(final String seedHost, final int seedPort, final Runnable onNewAddress) {
        synchronized (this) {
            if (started) {
                return;
            }
            started = true;
        }
        if (!reserveAttempt()) {
            return;
        }
        runResolve(seedHost, seedPort, onNewAddress, false);
    }

    /**
     * Re-run discovery on demand — the connection-stall / network-returned path
     * where stock Telegram refetched its own DNS address book. Same seed-first
     * guarantees and change callback as {@link #start}, but the cached registry
     * document is discarded first so a dead endpoint is replaced now rather than
     * at cache expiry. Throttled and single-flight; safe to call from any thread.
     */
    public void refresh(final String seedHost, final int seedPort, final Runnable onNewAddress) {
        if (!reserveAttempt()) {
            FileLog.d("CatNetResolver: refresh skipped (throttled or already running)");
            return;
        }
        runResolve(seedHost, seedPort, onNewAddress, true);
    }

    /** Single-flight + minimum spacing across both start() and refresh(). */
    private synchronized boolean reserveAttempt() {
        if (running) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastAttemptAt != 0 && now - lastAttemptAt < REFRESH_MIN_INTERVAL_MS) {
            return false;
        }
        running = true;
        lastAttemptAt = now;
        return true;
    }

    private void runResolve(final String seedHost, final int seedPort, final Runnable onNewAddress, final boolean discardCache) {
        final Context appCtx = ApplicationLoader.applicationContext;
        final String seeds = seedHost + ":" + seedPort;
        new Thread(() -> {
            try {
                if (discardCache && appCtx != null) {
                    // Force path: without this the Go side returns the fresh
                    // cached document and never re-probes — i.e. it would keep
                    // offering the endpoint that just stopped answering.
                    File cacheFile = new File(appCtx.getCacheDir(), CACHE_FILE);
                    if (cacheFile.exists()) {
                        cacheFile.delete();
                    }
                }
                String cacheDir = appCtx != null ? appCtx.getCacheDir().getAbsolutePath() : "";
                Client c;
                synchronized (this) {
                    if (client == null) {
                        client = Mobile.new_(cacheDir, seeds, TIMEOUT_MS);
                    }
                    c = client;
                }
                String addr = c.firstAddr(TIMEOUT_MS);
                String host = null;
                int port = 0;
                if (addr != null) {
                    int colon = addr.lastIndexOf(':');
                    if (colon > 0 && colon < addr.length() - 1) {
                        try {
                            int p = Integer.parseInt(addr.substring(colon + 1));
                            if (p > 0 && p <= 65535) {
                                host = addr.substring(0, colon);
                                port = p;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                boolean changed = false;
                if (host != null) {
                    synchronized (this) {
                        if (!host.equals(resolvedHost) || port != resolvedPort) {
                            resolvedHost = host;
                            resolvedPort = port;
                            changed = true;
                        }
                    }
                }
                if (changed && onNewAddress != null) {
                    onNewAddress.run();
                }
            } catch (Throwable t) {
                // Total failure is fine: the caller keeps using the seed.
                FileLog.e(t);
            } finally {
                synchronized (this) {
                    running = false;
                }
            }
        }, "CatNetResolver").start();
    }

    /** For logs/support: the gomobile library build id. */
    public static String version() {
        try {
            return Mobile.version();
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
