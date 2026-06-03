package net.ostore.ultralight;

import java.util.function.Consumer;

/**
 * Wrapper autour du handler JS→Java pour {@link UltralightBrowserView}.
 *
 * <p>Contrairement à {@code McefJsBridge}, il n'y a pas de router natif à gérer :
 * le bridge est entièrement implémenté via polling JavaScript dans
 * {@link UltralightBrowserView}. Cette classe offre surtout
 * {@link #updateHandler} pour la réutilisation depuis le cache.
 */
public final class UltralightJsBridge {

    private volatile Consumer<String> handler;

    UltralightJsBridge(Consumer<String> handler) {
        this.handler = handler;
    }

    Consumer<String> getHandler() { return handler; }

    /** Redirige les requêtes JS vers un nouveau dispatcher sans recréer la vue. */
    void updateHandler(Consumer<String> newHandler) {
        this.handler = newHandler;
    }

    void dispose() {
        handler = null;
    }
}
