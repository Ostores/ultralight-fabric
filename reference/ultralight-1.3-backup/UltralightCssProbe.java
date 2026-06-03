package net.ostore.ultralight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sonde de diagnostic CSS — opt-in via {@code -Dultralight.cssprobe=true}.
 *
 * <p>Charge {@code assets/ultralight/css-probe.html} dans une vue jetable. La page
 * exécute des {@code CSS.supports()} + des vérifications comportementales (le grid
 * se met-il vraiment en page ? le {@code gap} flex ajoute-t-il de l'espace ?) puis
 * fait un {@code console.log('CSS-PROBE-RESULT …')}. Le message est remonté dans les
 * logs MC par {@code BridgeViewListener} — on connaît ainsi empiriquement ce que le
 * moteur embarqué supporte (et la version WebKit exacte via {@code navigator.userAgent}),
 * sans inspecteur graphique.
 *
 * <p>Aucun effet si la propriété n'est pas positionnée. Pilotée par le render thread.
 */
final class UltralightCssProbe {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/cssprobe");

    // Activable par propriété système (-Dultralight.cssprobe=true) OU variable
    // d'environnement (ULTRALIGHT_CSSPROBE=true). L'env est plus fiable car héritée
    // par le JVM Minecraft forké par Gradle, contrairement aux -D côté Gradle.
    private static final boolean ENABLED =
            Boolean.getBoolean("ultralight.cssprobe")
            || "true".equalsIgnoreCase(System.getenv("ULTRALIGHT_CSSPROBE"));
    private static final String  RESOURCE = "/assets/ultralight/css-probe.html";

    private static boolean started = false;
    private static UltralightBrowserView view;
    private static int ticksLeft = 0;

    private UltralightCssProbe() {}

    /** Appelé chaque frame depuis {@link UltralightEngine#tick()} (render thread). */
    static void tick() {
        if (!ENABLED) return;

        if (!started) {
            started = true;
            start();
            return;
        }

        // Maintien de la vue quelques secondes, le temps que la page charge et logge,
        // puis fermeture pour ne pas laisser tourner une vue de diagnostic.
        if (view != null && --ticksLeft <= 0) {
            view.close();
            view = null;
            LOG.info("[ul-cssprobe] Sonde fermée.");
        }
    }

    private static void start() {
        String html = readResource();
        if (html == null) {
            LOG.error("[ul-cssprobe] Ressource introuvable : {}", RESOURCE);
            return;
        }
        try {
            view = new UltralightBrowserView(512, 512, 1.0);
            // Vérifie le pont JS→Java natif : la page appelle window.ulQuery(...).
            view.setQueryHandler(msg ->
                    LOG.info("[ul-cssprobe] pont JS→Java reçu : {}", msg));
            view.loadHTML(html);
            ticksLeft = 600; // ~10 s @ 60 fps
            LOG.info("[ul-cssprobe] Sonde CSS lancée — chercher 'CSS-PROBE-RESULT' dans les logs.");
        } catch (Throwable t) {
            LOG.error("[ul-cssprobe] Lancement échoué", t);
        }
    }

    private static String readResource() {
        try (InputStream in = UltralightCssProbe.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[ul-cssprobe] Lecture ressource échouée", e);
            return null;
        }
    }
}
