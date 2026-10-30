package org.telegram.thecat;

import android.content.Context;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.thecat.catnet.mobile.Client;
import org.telegram.thecat.catnet.mobile.Mobile;

/**
 * Finds a reachable Cat server address via the-cat-network-base (catnet), so the
 * app is not pinned to a single IP a censor can block. Source: {@code network-base/}.
 *
 * <p>Seed-first: callers keep using the configured host:port until — and unless —
 * this resolves a reachable endpoint. A resolve failure never breaks connectivity;
 * the caller simply falls back to the seed. Resolution runs off the main thread and
 * caches to {@code catnet-servers.json} in the app cache dir, so a second launch
 * resolves from cache before any network layer runs.
 */
public final class CatNetResolver {

    private static final long TIMEOUT_MS = 12000L;

    private static volatile CatNetResolver instance;

    private Client client;
    private String resolvedHost;
    private int resolvedPort;
    private boolean started;

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
    public synchronized void start(final String seedHost, final int seedPort, final Runnable onNewAddress) {
        if (started) {
            return;
        }
        started = true;
        final Context appCtx = ApplicationLoader.applicationContext;
        final String seeds = seedHost + ":" + seedPort;
        new Thread(() -> {
            try {
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
