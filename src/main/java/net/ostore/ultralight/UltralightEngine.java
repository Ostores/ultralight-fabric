package net.ostore.ultralight;

import me.ayydxn.luminescence.config.ULConfig;
import me.ayydxn.luminescence.platform.ULPlatform;
import me.ayydxn.luminescence.platform.impl.StandardULFileSystem;
import me.ayydxn.luminescence.renderer.ULRenderer;
import me.ayydxn.luminescence.view.ULView;
import me.ayydxn.luminescence.view.ULViewConfig;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
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
 * client MC) : le binding JNI n'est pas thread-safe.
 *
 * <p><b>Le cycle du moteur ne doit PAS tourner pendant la phase d'extraction de la GUI.</b> En
 * MC 26.x, l'interface est construite en deux temps (extraction de l'état, puis soumission GPU) ;
 * écrire dans une texture GPU au milieu de l'extraction corrompt le lot de dessins de Minecraft :
 * son fond de menu se met à échantillonner notre texture (page répétée en damier plein écran) et
 * une vue de la largeur du framebuffer s'affiche en noir plein. Vérifié en jeu, et corrigé en
 * pompant depuis {@link LevelExtractionEvents#END_EXTRACTION}, qui est par frame et antérieur à la GUI.
 */
public final class UltralightEngine {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/engine");

    private static volatile boolean ready = false;
    private static boolean initAttempted = false;
    /** Le pilote de frame n'est enregistré qu'une fois, quel que soit le nombre d'appels à init(). */
    private static boolean frameDriverRegistered = false;
    private static ULRenderer renderer;

    /** Vues actives — notifiées à chaque frame (render thread). */
    static final Set<UltralightBrowserView> activeViews =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    static final AtomicInteger VIEW_COUNTER = new AtomicInteger(0);

    // ── Instrumentation optionnelle du coût du cycle moteur (activée par la sonde de perf).
    //    Deux appels à nanoTime par frame quand elle est active, rien du tout sinon.
    static volatile boolean perfEnabled = false;
    private static long perfFrames = 0L;
    private static long perfNanos  = 0L;

    static void perfReset() { perfFrames = 0L; perfNanos = 0L; }

    /** Coût moyen d'un cycle update/render/paint, en microsecondes. */
    static double perfAverageMicros() {
        long f = perfFrames;
        return f == 0L ? 0.0 : perfNanos / 1000.0 / f;
    }

    static long perfFrameCount() { return perfFrames; }

    private UltralightEngine() {}

    public static boolean isReady() { return ready; }

    /**
     * Enregistre le pilote de frame. L'initialisation native réelle (plateforme + renderer) est
     * <b>différée au premier frame</b> : pendant {@code onInitializeClient}, la fenêtre/GL de MC
     * n'existe pas encore et créer le renderer Ultralight 1.4 y plante (ACCESS_VIOLATION).
     */
    public static void init() {
        // IDEMPOTENT : cette bibliothèque est faite pour être consommée par plusieurs mods, et la
        // doc dit à chacun d'appeler init(). Sans ce garde-fou, deux mods = deux écouteurs = le
        // moteur pompé deux fois par frame (double update/render, double peinture, double upload).
        if (frameDriverRegistered) {
            LOG.debug("[ul] init() rappelé — le pilote de frame est déjà en place, on ignore.");
            return;
        }
        frameDriverRegistered = true;

        // Point de pompage : par frame, hors de toute passe de rendu, et avant la GUI.
        //
        // MC 26.3 a rendu ce choix nettement plus contraint. LevelRenderEvents.START_MAIN, qui
        // convenait en 26.2, est branche a l'interieur du frame graph : une passe de rendu y est
        // ouverte et renderpearl refuse alors toute ecriture de texture
        // ("Close the existing render pass before performing additional commands").
        // L'extraction du MONDE, elle, precede le frame graph, donc aucune passe n'est ouverte, et
        // elle precede aussi la construction de la GUI, ce qui preserve la regle etablie en 26.2.
        LevelExtractionEvents.END_EXTRACTION.register(ctx -> onFrame());
    }

    /** Initialisation native — appelée une seule fois, sur le render thread, au premier frame. */
    private static void doInit() {
        // Désactivation manuelle (support / machines à problème). Aussi via env ULTRALIGHT_DISABLE.
        if (isDisabledByUser()) {
            LOG.info("[ul] Rendu HTML désactivé par configuration (-Dultralight.disable).");
            return;
        }
        // Les natifs WebKit 615 utilisent en réalité des instructions AVX2 (ex. VPSRAVD), pas
        // seulement AVX. Sur un CPU avec AVX mais sans AVX2 (Intel Ivy Bridge et antérieur,
        // AMD pré-Excavator), la 1re instruction AVX2 lève un SIGILL natif = crash JVM dur, NON
        // rattrapable par try/catch. On refuse donc d'appeler le moindre code natif Luminescence.
        // Contournable via -Dultralight.skipCpuCheck=true (tests uniquement).
        if (!"true".equalsIgnoreCase(System.getProperty("ultralight.skipCpuCheck"))
                && !cpuSupportsAvx2()) {
            LOG.warn("[ul] CPU sans support AVX2 détecté — WebKit 615 requiert AVX2. "
                    + "Rendu HTML désactivé pour éviter un crash natif (EXCEPTION_ILLEGAL_INSTRUCTION). "
                    + "Force via -Dultralight.skipCpuCheck=true (à vos risques).");
            return;
        }
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

    /** Interrupteur de désactivation manuelle : -Dultralight.disable=true ou ULTRALIGHT_DISABLE=true. */
    private static boolean isDisabledByUser() {
        if ("true".equalsIgnoreCase(System.getProperty("ultralight.disable"))) return true;
        String env = System.getenv("ULTRALIGHT_DISABLE");
        return env != null && "true".equalsIgnoreCase(env.trim());
    }

    /**
     * Vrai si le CPU supporte AVX2. On lit le flag interne {@code UseAVX} de la JVM HotSpot
     * (0 = pas d'AVX ; 1 = AVX seulement ; >=2 = AVX2/AVX512), déterminé par HotSpot à partir des
     * flags CPU réels. Zéro dépendance, zéro appel natif. En cas d'indisponibilité (JVM non-HotSpot,
     * flag absent, erreur), on renvoie {@code true} pour ne pas bloquer inutilement (comportement
     * historique).
     */
    private static boolean cpuSupportsAvx2() {
        try {
            com.sun.management.HotSpotDiagnosticMXBean bean =
                    java.lang.management.ManagementFactory.getPlatformMXBean(
                            com.sun.management.HotSpotDiagnosticMXBean.class);
            if (bean == null) return true;
            String v = bean.getVMOption("UseAVX").getValue();
            return Integer.parseInt(v.trim()) >= 2;
        } catch (Throwable t) {
            LOG.debug("[ul] Détection AVX2 impossible ({}), on tente l'init.", t.toString());
            return true;
        }
    }

    /**
     * Pompe un cycle update/render/paint. Le moteur le fait déjà tout seul à chaque frame tant
     * qu'un monde est rendu : <b>les mods n'ont normalement pas à appeler ceci</b>.
     *
     * <p>⚠️ À n'appeler que depuis un contexte qui n'est PAS une phase d'extraction de la GUI
     * (donc ni {@code Screen.extractRenderState}, ni un {@code HudElement}) : voir l'en-tête de
     * cette classe. Un tick client convient.
     */
    public static void renderFrame() { onFrame(); }

    static void onFrame() {
        if (!initAttempted) { initAttempted = true; doInit(); }
        if (!ready || renderer == null) return;
        UltralightCssProbe.tick(); // sonde de diagnostic opt-in
        if (activeViews.isEmpty()) return;
        long t0 = perfEnabled ? System.nanoTime() : 0L;
        renderer.update();
        for (UltralightBrowserView v : activeViews) {
            v.prepareFrame(); // force la re-rastérisation après input (survol/scroll fluides)
        }
        renderer.render();
        for (UltralightBrowserView v : activeViews) {
            v.onRendererTick();
        }
        if (perfEnabled) { perfNanos += System.nanoTime() - t0; perfFrames++; }
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
