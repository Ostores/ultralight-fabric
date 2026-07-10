package net.ostore.ultralight;

import me.ayydxn.luminescence.config.ULConfig;
import me.ayydxn.luminescence.platform.ULPlatform;
import me.ayydxn.luminescence.platform.impl.StandardULFileSystem;
import me.ayydxn.luminescence.renderer.ULRenderer;
import me.ayydxn.luminescence.view.ULView;
import me.ayydxn.luminescence.view.ULViewConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton gérant le cycle de vie du moteur Ultralight 1.4 (via Luminescence / WebKit 615).
 *
 * <p>Toutes les opérations Ultralight se font sur le <b>render thread</b> (thread principal
 * client MC) via {@link HudRenderCallback} : le binding JNI n'est pas thread-safe.
 */
public final class UltralightEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/engine");

    private static volatile boolean ready = false;
    private static boolean initAttempted = false;
    private static ULRenderer renderer;

    /** Vues actives — notifiées à chaque frame (render thread). */
    static final Set<UltralightBrowserView> activeViews =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static final AtomicInteger VIEW_COUNTER = new AtomicInteger(0);

    private UltralightEngine() {}

    public static boolean isReady() { return ready; }

    /**
     * Enregistre le pilote de frame. L'initialisation native réelle (plateforme + renderer) est
     * <b>différée au premier frame</b> : pendant {@code onInitializeClient}, la fenêtre/GL de MC
     * n'existe pas encore et créer le renderer Ultralight 1.4 y plante (ACCESS_VIOLATION).
     */
    public static void init() {
        HudRenderCallback.EVENT.register((ctx, tick) -> onFrame());
    }

    /** Initialisation native — appelée une seule fois, sur le render thread, au premier frame. */
    private static void doInit() {
        Path sdkDir = UltralightNativeLoader.load();
        if (sdkDir == null) {
            LOG.warn("[ul] Natifs non disponibles — rendu HTML désactivé.");
            return;
        }
        try {
            // Police de secours emoji/symboles embarquée (routage Arial-aware). Désactivable via
            // -Dultralight.emojifallback=false → loader standard. Utile sur macOS où notre loader
            // initialise AWT/CoreText dans son constructeur, ce qui peut interagir avec la pile de
            // polices système (voir collision WebCore système/Ultralight sur macOS).
            boolean emojiFallback = !"false".equalsIgnoreCase(
                    System.getProperty("ultralight.emojifallback", "true"));
            if (emojiFallback) {
                ULPlatform.setFontLoader(new EmojiFallbackFontLoader());
            } else {
                ULPlatform.setFontLoader(new me.ayydxn.luminescence.platform.impl.StandardULFontLoader());
                LOG.info("[ul] Police de secours emoji désactivée (-Dultralight.emojifallback=false) → loader standard.");
            }
            ULPlatform.setFileSystem(new StandardULFileSystem());

            try (ULConfig config = new ULConfig()) {
                // resourcePathPrefix est préfixé DIRECTEMENT aux noms de ressources (icudt67l.dat,
                // cacert.pem…) → il doit pointer le dossier resources/ lui-même, pas la racine SDK.
                // Slashes avant (le C++ Ultralight n'aime pas les backslashes Windows).
                String prefix = sdkDir.resolve("resources").toFile().getAbsolutePath().replace('\\', '/') + "/";
                config.setResourcePathPrefix(prefix);
                renderer = new ULRenderer(config);
            }

            ready = true;
            LOG.info("[ul] Moteur Ultralight 1.4 initialisé.");
        } catch (Throwable t) {
            LOG.error("[ul] Initialisation échouée", t);
        }
    }

    /**
     * Pompe un cycle update/render/paint. À appeler depuis {@code Screen.render()} (le
     * {@link HudRenderCallback} ne tourne pas quand un écran est ouvert). Ne pas appeler en
     * plus du tick HUD dans la même frame.
     */
    public static void renderFrame() { onFrame(); }

    static void onFrame() {
        if (!initAttempted) { initAttempted = true; doInit(); }
        if (!ready || renderer == null) return;
        UltralightCssProbe.tick(); // sonde de diagnostic opt-in
        if (activeViews.isEmpty()) return;
        renderer.update();
        for (UltralightBrowserView v : activeViews) {
            v.prepareFrame(); // force la re-rastérisation après input (survol/scroll fluides)
        }
        renderer.render();
        for (UltralightBrowserView v : activeViews) {
            v.onRendererTick();
        }
    }

    /** Crée une nouvelle vue — render thread. */
    static ULView createView(int width, int height, boolean transparent, double deviceScale) {
        try (ULViewConfig viewConfig = new ULViewConfig()) {
            viewConfig.isAccelerated(false);
            viewConfig.setTransparent(transparent);
            viewConfig.setInitialDeviceScale(deviceScale);
            viewConfig.setEnableJavaScript(true);
            viewConfig.setEnableImages(true);
            return new ULView(renderer, width, height, viewConfig, null);
        }
    }

    static void registerView(UltralightBrowserView view)   { activeViews.add(view); }
    static void unregisterView(UltralightBrowserView view) { activeViews.remove(view); }
}
