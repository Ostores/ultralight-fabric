package net.ostore.ultralight;

import com.labymedia.ultralight.UltralightPlatform;
import com.labymedia.ultralight.UltralightRenderer;
import com.labymedia.ultralight.UltralightView;
import com.labymedia.ultralight.config.FontHinting;
import com.labymedia.ultralight.config.UltralightConfig;
import com.labymedia.ultralight.config.UltralightViewConfig;
import com.labymedia.ultralight.plugin.filesystem.UltralightFileSystem;
import com.labymedia.ultralight.plugin.logging.UltralightLogger;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Singleton gérant le cycle de vie du moteur Ultralight.
 *
 * <p>Toutes les opérations Ultralight (update, render, evaluateScript, surface lock)
 * se font sur le <b>render thread</b> (thread principal client MC) via {@link HudRenderCallback}.
 * ultralight-java 0.4.12 n'est pas thread-safe : ses JNI bindings cachent le JNIEnv du
 * thread d'initialisation — appeler depuis un autre thread provoque une corruption mémoire native.
 *
 * <p>Optimisations actives :
 * <ul>
 *   <li>Early-exit si {@code activeViews} est vide — évite {@code renderer.update/render} inutile.</li>
 *   <li>JS bridge throttlé à 100 ms dans {@link UltralightBrowserView}.</li>
 *   <li>{@code view.loadURL("about:blank")} à la fermeture pour tuer les timers JS natifs.</li>
 * </ul>
 */
public final class UltralightEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/engine");

    private static volatile boolean ready = false;
    private static UltralightRenderer renderer;

    /** Vues actives — notifiées à chaque frame (render thread). */
    static final Set<UltralightBrowserView> activeViews =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static final AtomicInteger VIEW_COUNTER = new AtomicInteger(0);

    private UltralightEngine() {}

    public static boolean isReady() { return ready; }

    /**
     * Initialise la plateforme et le renderer Ultralight sur le render thread.
     * No-op si déjà initialisé ou si les natives ne sont pas disponibles.
     */
    @SuppressWarnings("deprecation")
    public static void init() {
        if (ready) return;

        if (!UltralightNativeLoader.load()) {
            LOG.warn("[ul] Natives non disponibles — rendu HTML désactivé.");
            return;
        }

        try {
            Path sdkDir = UltralightNativeLoader.getSdkDir();
            UltralightPlatform platform = UltralightPlatform.instance();
            platform.setConfig(new UltralightConfig()
                    .forceRepaint(false)
                    .fontHinting(FontHinting.SMOOTH)
                    .resourcePath("resources/"));
            platform.usePlatformFontLoader();
            platform.setFileSystem(new SdkFileSystem(sdkDir));
            platform.setLogger(new BridgeLogger());

            renderer = UltralightRenderer.create();

            HudRenderCallback.EVENT.register((ctx, tick) -> tick());

            ready = true;
            LOG.info("[ul] Moteur Ultralight initialisé.");
        } catch (Throwable t) {
            LOG.error("[ul] Initialisation échouée", t);
        }
    }

    /**
     * Appelé chaque frame sur le render thread.
     * Early-exit si aucune vue active pour ne pas payer renderer.update/render à vide.
     */
    /**
     * Pompe un cycle update/render/paint des vues actives.
     *
     * <p>À appeler depuis {@code Screen.render()} pour un overlay interactif : quand un
     * écran MC est ouvert, {@link HudRenderCallback} ne se déclenche pas, donc les vues ne
     * se mettent plus à jour sans cet appel. Ne PAS appeler en plus du tick HUD dans la
     * même frame (les deux cas s'excluent : écran ouvert ⇒ pas de HUD).
     */
    public static void renderFrame() { tick(); }

    static void tick() {
        if (!ready || renderer == null) return;
        // Sonde de diagnostic opt-in — doit pouvoir s'amorcer avant l'early-exit,
        // car au premier tick aucune vue n'est encore active.
        UltralightCssProbe.tick();
        if (activeViews.isEmpty()) return;
        renderer.update();
        renderer.render();
        for (UltralightBrowserView v : activeViews) {
            v.onRendererTick();
        }
    }

    /** Crée une nouvelle view — doit être appelé sur le render thread. */
    @SuppressWarnings("deprecation")
    static UltralightView createView(int width, int height, boolean transparent, double deviceScale) {
        return renderer.createView(width, height,
                new UltralightViewConfig()
                        .isAccelerated(false)
                        .isTransparent(transparent)
                        .initialDeviceScale(deviceScale)
                        .enableJavascript(true)
                        .enableImages(true));
    }

    static void registerView(UltralightBrowserView view)   { activeViews.add(view); }
    static void unregisterView(UltralightBrowserView view) { activeViews.remove(view); }

    // ─── FileSystem ───────────────────────────────────────────────────────────

    private static final class SdkFileSystem implements UltralightFileSystem {

        private final Path sdkDir;
        private final AtomicLong nextHandle = new AtomicLong(1);
        private final ConcurrentHashMap<Long, FileEntry> openFiles = new ConcurrentHashMap<>();

        SdkFileSystem(Path sdkDir) { this.sdkDir = sdkDir; }

        private Path resolve(String pathStr) {
            Path p = Paths.get(pathStr);
            return p.isAbsolute() ? p : sdkDir.resolve(pathStr);
        }

        @Override public boolean fileExists(String path) { return Files.isRegularFile(resolve(path)); }
        @Override public long getFileSize(long handle)   { FileEntry e = openFiles.get(handle); return e != null ? e.size : -1L; }
        @Override public String getFileMimeType(String path) { return null; }

        @Override
        public long openFile(String path, boolean write) {
            try {
                Path resolved = resolve(path);
                long size = Files.size(resolved);
                InputStream stream = Files.newInputStream(resolved);
                long handle = nextHandle.getAndIncrement();
                openFiles.put(handle, new FileEntry(stream, size));
                return handle;
            } catch (IOException e) {
                LOG.warn("[ul-fs] Impossible d'ouvrir : {}", path);
                return INVALID_FILE_HANDLE;
            }
        }

        @Override
        public void closeFile(long handle) {
            FileEntry e = openFiles.remove(handle);
            if (e != null) try { e.stream.close(); } catch (IOException ignored) {}
        }

        @Override
        public long readFromFile(long handle, ByteBuffer data, long length) {
            FileEntry e = openFiles.get(handle);
            if (e == null) return -1L;
            try {
                int toRead = (int) Math.min(length, data.remaining());
                byte[] buf = new byte[toRead];
                int read = e.stream.read(buf, 0, toRead);
                if (read > 0) data.put(buf, 0, read);
                return read;
            } catch (IOException ex) { return -1L; }
        }

        private static final class FileEntry {
            final InputStream stream;
            final long size;
            FileEntry(InputStream s, long sz) { stream = s; size = sz; }
        }
    }

    private static final class BridgeLogger implements UltralightLogger {
        @Override
        public void logMessage(com.labymedia.ultralight.plugin.logging.UltralightLogLevel level, String message) {
            switch (level) {
                case ERROR   -> LOG.error("[ul-js] {}", message);
                case WARNING -> LOG.warn("[ul-js] {}", message);
                default      -> LOG.debug("[ul-js] {}", message);
            }
        }
    }
}
