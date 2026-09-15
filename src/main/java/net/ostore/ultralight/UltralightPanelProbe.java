package net.ostore.ultralight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sonde de diagnostic — opt-in, sur le même modèle que {@link UltralightCssProbe}.
 *
 * <p>Trois phases. La <b>géométrie</b> parcourt les politiques de {@link UltralightPanel.Fit} et
 * des ratios simulés en capturant une copie d'écran à chaque étape (dans {@code run/screenshots}) ;
 * c'est l'outil qui a permis d'établir qu'il ne faut rien écrire sur le GPU pendant la phase
 * d'extraction de la GUI. L'<b>input</b> injecte de vrais événements souris/clavier/molette à des
 * coordonnées Minecraft calculées depuis des positions CSS connues, et vérifie ce que la page a
 * réellement reçu : c'est le seul moyen de valider le mapping « logique MC → pixels CSS », le
 * hit-test hors panneau, la saisie texte, le défilement et les curseurs. La <b>perf</b> mesure le
 * temps de frame du client avec et sans overlay, en deux tours pour que la dérive du chargement du
 * monde reste visible au lieu d'être confondue avec le résultat.
 *
 * <p><b>Inactive par défaut</b> : sans le drapeau, aucun écouteur n'est même enregistré. Elle est
 * aussi <b>non destructive</b> par défaut : elle attend que le joueur soit en jeu, ne charge aucune
 * sauvegarde et ne ferme pas le jeu. Les deux comportements agressifs, utiles pour un run
 * automatisé, sont derrière leurs propres drapeaux.
 *
 * <table>
 *   <tr><td>{@code -Dultralight.panelprobe=true} / {@code ULTRALIGHT_PANELPROBE=true}</td>
 *       <td>arme la sonde</td></tr>
 *   <tr><td>{@code ULTRALIGHT_PANELPROBE_WORLD=<dossier>}</td>
 *       <td>charge cette sauvegarde au lieu d'attendre le joueur</td></tr>
 *   <tr><td>{@code ULTRALIGHT_PANELPROBE_QUIT=true}</td>
 *       <td>quitte le jeu à la fin de la séquence</td></tr>
 *   <tr><td>{@code ULTRALIGHT_PANELPROBE_MODE=geometry|input|perf|all}</td>
 *       <td>phases à jouer (défaut {@code all})</td></tr>
 * </table>
 *
 * <p>Pour la phase perf, couper la synchro verticale et relever {@code maxFps} : sinon le temps de
 * frame est plafonné et la mesure ne dit rien.
 */
final class UltralightPanelProbe {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/panelprobe");

    private static final boolean ENABLED = flag("ultralight.panelprobe", "ULTRALIGHT_PANELPROBE");
    private static final boolean QUIT_AT_END =
            flag("ultralight.panelprobe.quit", "ULTRALIGHT_PANELPROBE_QUIT");
    private static final String AUTO_WORLD = env("ULTRALIGHT_PANELPROBE_WORLD");
    private static final String MODE = modeOrDefault(env("ULTRALIGHT_PANELPROBE_MODE"));

    private static String modeOrDefault(String v) {
        if (v == null) return "all";
        String m = v.trim().toLowerCase(java.util.Locale.ROOT);
        return (m.equals("geometry") || m.equals("input") || m.equals("perf")
                || m.equals("typing")) ? m : "all";
    }

    private static boolean runGeometry() { return MODE.equals("all") || MODE.equals("geometry"); }
    private static boolean runInput()    { return MODE.equals("all") || MODE.equals("input"); }
    private static boolean runPerf()     { return MODE.equals("all") || MODE.equals("perf"); }
    /** Phase manuelle : jamais dans "all", elle attend une frappe humaine. */
    private static boolean runTyping()   { return MODE.equals("typing"); }

    private static boolean flag(String property, String envName) {
        return Boolean.getBoolean(property) || "true".equalsIgnoreCase(System.getenv(envName));
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    /** Séquence terminée : on ne reprend plus la main. */
    private static volatile boolean done = false;
    /** Capture demandée depuis la phase de rendu, exécutée au tick suivant. */
    private static volatile boolean grabRequested = false;
    /** Dernière frame rendue par l'écran de sonde — sert à détecter qu'on nous a remplacés. */
    private static volatile long lastRenderNanos = 0L;
    /** Instance unique : la réafficher conserve l'avancement de la séquence. */
    private static PanelProbeScreen instance;
    private static boolean worldRequested = false;

    private UltralightPanelProbe() {}

    static void init() {
        if (!ENABLED) return;   // rien n'est enregistré : coût nul dans un jar publié

        final int[] ticks = {0};
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            // Capture ICI et nulle part ailleurs : le tick s'exécute entre deux frames, donc le
            // framebuffer contient une image complète. Prise pendant la phase d'extraction, elle
            // ramène une cible de rendu intermédiaire (écran noir, bribes d'atlas).
            if (grabRequested) {
                grabRequested = false;
                Screenshot.grab(mc, false);
            }
            if (done) return;

            if (mc.level == null) {
                // Par défaut on attend simplement que le joueur entre en jeu. Un run automatisé
                // peut demander le chargement d'une sauvegarde précise.
                if (AUTO_WORLD != null && !worldRequested && ++ticks[0] > 60) {
                    worldRequested = true;
                    LOG.info("[ul-panelprobe] ouverture du monde « {} »", AUTO_WORLD);
                    mc.createWorldOpenFlows().openWorld(AUTO_WORLD,
                            () -> LOG.error("[ul-panelprobe] impossible d'ouvrir « {} »", AUTO_WORLD));
                }
                return;
            }

            // En jeu : on affiche l'écran de sonde, et on le réaffiche s'il disparaît. L'instance
            // est conservée, donc on reprend là où on en était.
            long now = System.nanoTime();
            if (lastRenderNanos == 0L) { lastRenderNanos = now; show(); return; }
            if (now - lastRenderNanos > 1_500_000_000L) {
                lastRenderNanos = now;
                show();
            }
        });

        LOG.info("[ul-panelprobe] sonde armée{}{}.",
                AUTO_WORLD != null ? " (monde « " + AUTO_WORLD + " »)" : " — entre en jeu pour la lancer",
                QUIT_AT_END ? ", fermeture du jeu à la fin" : "");
    }

    private static void show() {
        if (done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (instance == null) instance = new PanelProbeScreen();
        mc.setScreenAndShow(instance);
    }

    /** Une étape : politique, ratio simulé éventuel, et bascule de ratio en cours d'étape. */
    private record Step(String label, UltralightPanel.Fit fit, double previewAspect, double switchTo) {
        Step(String label, UltralightPanel.Fit fit, double previewAspect) {
            this(label, fit, previewAspect, 0);
        }
    }

    private static final class PanelProbeScreen extends Screen {

        private static final Step[] STEPS = {
                new Step("CONTAIN / fenetre",    UltralightPanel.Fit.CONTAIN,      0),
                new Step("FILL_CLAMPED / 21:9",  UltralightPanel.Fit.FILL_CLAMPED, 21.0 / 9.0),
                new Step("FILL / 21:9",          UltralightPanel.Fit.FILL,         21.0 / 9.0),
                new Step("FILL / 4:3",           UltralightPanel.Fit.FILL,         4.0 / 3.0),
                // Captures juste après un redimensionnement, puis une seconde plus tard.
                new Step("resize 4:3 -> 21:9",   UltralightPanel.Fit.FILL_CLAMPED, 4.0 / 3.0, 21.0 / 9.0),
                new Step("resize 21:9 -> 4:3",   UltralightPanel.Fit.FILL_CLAMPED, 21.0 / 9.0, 4.0 / 3.0),
        };

        private UltralightPanel panel;
        private int step = -1;
        private int frames;
        private String html;

        // -- phase input --
        private boolean inputPhase = false;
        private String inputHtml;
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private final List<String> results = new ArrayList<>();
        private volatile CursorType cursorShape = null;

        // -- phase typing (frappe reelle) --
        private boolean typingPhase = false;
        private String lastTyped = "";

        // -- phase perf --
        private boolean perfPhase = false;
        private String perfHtml;
        private int perfCase = -1;
        private final List<Long> frameNanos = new ArrayList<>();
        private long lastFrameNano = 0L;

        PanelProbeScreen() { super(Component.literal("Ultralight — sonde de géométrie")); }

        @Override
        protected void init() {
            super.init();
            if (html == null)      html = read("/assets/ultralight/panel-probe.html");
            if (inputHtml == null) inputHtml = read("/assets/ultralight/panel-input.html");
            if (perfHtml == null)  perfHtml  = read("/assets/ultralight/panel-perf.html");
            if (step < 0) nextStep();
        }

        private void nextStep() {
            if (panel != null) { panel.close(); panel = null; }
            step++;
            if (runTyping() && !typingPhase) { startTypingPhase(); return; }
            if (!runGeometry() || step >= STEPS.length) {
                if (runInput() && !inputPhase) { startInputPhase(); return; }
                if (runPerf()  && !perfPhase)  { startPerfPhase();  return; }
                finish();
                return;
            }
            Step s = STEPS[step];
            LOG.info("[ul-panelprobe] etape {}/{} : {}", step + 1, STEPS.length, s.label());
            panel = UltralightPanel.builder()
                    .design(1280, 720)
                    .fit(s.fit())
                    .build();
            if (s.previewAspect() > 0) panel.setPreviewAspect(s.previewAspect());
            if (html != null) panel.loadHTML(html);
            frames = 0;
        }

        private void finish() {
            LOG.info("[ul-panelprobe] termine.");
            done = true;
            Minecraft mc = Minecraft.getInstance();
            if (QUIT_AT_END) mc.execute(mc::stop);   // hors phase de rendu
            else             onClose();
        }

        // =====================================================================
        //  Phase input : on injecte a des coordonnees Minecraft calculees depuis des positions
        //  CSS connues, puis on verifie ce que la page a reellement recu.
        // =====================================================================

        private void startInputPhase() {
            inputPhase = true;
            events.clear();
            results.clear();
            cursorShape = null;
            panel = UltralightPanel.builder()
                    .design(1280, 720)
                    .fit(UltralightPanel.Fit.CONTAIN)   // viewport CSS exact et previsible
                    .build();
            panel.setQueryHandler(events::add);
            panel.setCursorHandler(type -> cursorShape = type);
            if (inputHtml != null) panel.loadHTML(inputHtml);
            panel.focus();

            // Simule un DEUXIEME mod consommateur : la doc dit a chacun d'appeler init(). Si
            // l'enregistrement du pilote de frame n'est pas idempotent, le moteur sera pompe deux
            // fois par frame, ce que checkSinglePump() detectera.
            UltralightEngine.init();
            UltralightEngine.perfEnabled = true;
            UltralightEngine.perfReset();

            frames = 0;
            LOG.info("[ul-panelprobe] phase input - injection a des positions CSS connues.");
        }

        /** CSS vers coordonnees logiques Minecraft (l'inverse exact de ce que fait le panneau). */
        private double logX(double cssX) {
            return panel.drawX() + cssX / panel.cssWidth()  * panel.drawWidth();
        }

        private double logY(double cssY) {
            return panel.drawY() + cssY / panel.cssHeight() * panel.drawHeight();
        }

        private void inputTick() {
            if (!UltralightEngine.isReady() || panel.cssWidth() <= 0) return;
            frames++;
            switch (frames) {
                case 30  -> checkGuards();
                case 40  -> panel.mouseMoved(logX(640), logY(40));            // zone neutre
                case 50  -> panel.mouseMoved(logX(200), logY(130));           // sur le bouton
                case 60  -> check("souris mappee", "move", 200, 130);
                case 65  -> panel.mouseClicked(logX(200), logY(130), InputConstants.MOUSE_BUTTON_LEFT);
                case 68  -> panel.mouseReleased(logX(200), logY(130), InputConstants.MOUSE_BUTTON_LEFT);
                case 78  -> checkClickTarget("clic sur le bouton", "btn");
                case 82  -> checkOutside();
                case 86  -> panel.mouseMoved(logX(250), logY(220));
                case 90  -> panel.mouseClicked(logX(250), logY(220), InputConstants.MOUSE_BUTTON_LEFT);
                case 93  -> panel.mouseReleased(logX(250), logY(220), InputConstants.MOUSE_BUTTON_LEFT);
                case 100 -> { panel.charTyped("a"); panel.charTyped("b"); panel.charTyped("c"); }
                case 112 -> checkText();
                case 118 -> panel.mouseMoved(logX(200), logY(320));           // element cursor:pointer
                case 130 -> checkCursor();
                case 134 -> panel.mouseMoved(logX(350), logY(500));           // zone defilable
                case 138 -> panel.mouseScrolled(logX(350), logY(500), 0, -3);
                case 150 -> checkScroll();
                case 158 -> checkSinglePump();
                case 168 -> {
                    UltralightEngine.perfEnabled = false;
                    report();
                    if (runPerf() && !perfPhase) { inputPhase = false; startPerfPhase(); }
                    else finish();
                }
                default  -> { }
            }
        }

        /** Les garde-fous doivent refuser les entrees absurdes, et le dire. */
        private void checkGuards() {
            try {
                new UltralightBrowserView(0, 100, 1.0);
                fail("garde-fou taille de vue", "une vue 0x100 a ete acceptee");
            } catch (IllegalArgumentException e) {
                pass("garde-fou taille de vue", "refusee comme prevu");
            } catch (Throwable t) {
                fail("garde-fou taille de vue", "exception inattendue : " + t);
            }

            try {
                UltralightPanel.builder().bounds(0.5f, 0f, 0.8f, 1f).build();
                fail("garde-fou bounds", "un rectangle debordant de [0,1] a ete accepte");
            } catch (IllegalArgumentException e) {
                pass("garde-fou bounds", "refuse comme prevu");
            }

            try {
                panel.view().setBridgeName("mauvais'nom");
                fail("garde-fou nom de pont", "un nom non-identifiant a ete accepte");
            } catch (IllegalArgumentException e) {
                pass("garde-fou nom de pont", "refuse comme prevu");
            }
        }

        /**
         * Le moteur doit etre pompe UNE fois par frame, meme apres un second appel a init().
         * On compare le compteur de cycles du moteur au nombre de frames rendues par la sonde.
         */
        private void checkSinglePump() {
            long pumps = UltralightEngine.perfFrameCount();
            long rendered = frames;
            // Les deux compteurs ne s'incrementent pas au meme point de la frame : on tolere un
            // ecart de quelques unites, mais surement pas un facteur deux.
            if (Math.abs(pumps - rendered) <= 5) {
                pass("un seul pompage par frame", pumps + " cycles pour " + rendered + " frames");
            } else {
                fail("un seul pompage par frame",
                        pumps + " cycles pour " + rendered + " frames rendues"
                        + (pumps > rendered * 1.5 ? " (init() n'est pas idempotent)" : ""));
            }
        }

        /**
         * Ouvre la page de saisie et attend une frappe HUMAINE. La phase input tape en
         * synthetique, ce qui contourne le backend SDL : seule une vraie frappe prouve que la
         * notification a TextInputManager evite la desynchronisation annoncee par Fabric.
         */
        private void startTypingPhase() {
            typingPhase = true;
            lastTyped = "";
            panel = UltralightPanel.builder()
                    .design(1280, 720)
                    .fit(UltralightPanel.Fit.CONTAIN)
                    .build();
            if (inputHtml != null) panel.loadHTML(inputHtml);
            panel.focus();
            frames = 0;
            LOG.info("[ul-panelprobe] phase TYPING : clique dans le champ texte, puis tape au clavier.");
            LOG.info("[ul-panelprobe] (le champ est a ~250,220 en CSS ; la sonde s'arrete des que 3 caracteres arrivent)");
        }

        private void typingTick() {
            if (!UltralightEngine.isReady() || panel.view() == null) return;
            frames++;

            // Au demarrage, on place le curseur dans le champ pour que l'humain n'ait qu'a taper.
            if (frames == 60) {
                panel.mouseMoved(logX(250), logY(220));
                panel.mouseClicked(logX(250), logY(220), InputConstants.MOUSE_BUTTON_LEFT);
                panel.mouseReleased(logX(250), logY(220), InputConstants.MOUSE_BUTTON_LEFT);
                LOG.info("[ul-panelprobe] champ focalise (hasInputFocus={}) — a toi de taper.",
                        panel.hasInputFocus());
            }

            if (frames % 20 != 0) return;
            String v = panel.view().evalString("document.getElementById('txt').value");
            if (v == null) v = "";
            if (!v.equals(lastTyped)) {
                lastTyped = v;
                LOG.info("[ul-panelprobe] champ = \"{}\"", v);
            }
            if (v.length() >= 3) {
                pass("saisie clavier REELLE (chemin SDL)", "recu \"" + v + "\"");
                report();
                finish();
            } else if (frames > 12000) {  // ~3 min : le temps 'un humain tape
                fail("saisie clavier REELLE (chemin SDL)",
                        "rien recu en 60 s — TextInputManager probablement desynchronise");
                report();
                finish();
            }
        }

        private void pass(String what, String detail) {
            results.add("PASS " + what);
            LOG.info("[ul-panelprobe]   PASS {} - {}", what, detail);
        }

        private void fail(String what, String detail) {
            results.add("FAIL " + what);
            LOG.error("[ul-panelprobe]   FAIL {} - {}", what, detail);
        }

        /** Dernier evenement du type demande, ou null. */
        private String last(String type) {
            synchronized (events) {
                for (int i = events.size() - 1; i >= 0; i--) {
                    String e = events.get(i);
                    if (type.equals(field(e, "t"))) return e;
                }
            }
            return null;
        }

        /** Extraction minimale d'un champ JSON : la sonde n'embarque pas de parseur. */
        private static String field(String json, String key) {
            if (json == null) return null;
            int i = json.indexOf("\"" + key + "\"");
            if (i < 0) return null;
            int c = json.indexOf(':', i);
            if (c < 0) return null;
            int b = c + 1;
            while (b < json.length() && (json.charAt(b) == ' ' || json.charAt(b) == '"')) b++;
            int e = b;
            while (e < json.length() && "\",}".indexOf(json.charAt(e)) < 0) e++;
            return json.substring(b, e);
        }

        private void check(String what, String type, int cssX, int cssY) {
            String ev = last(type);
            if (ev == null) { fail(what, "aucun evenement " + type + " recu"); return; }
            double x = num(field(ev, "x")), y = num(field(ev, "y"));
            // 2 px CSS de tolerance : les coordonnees transitent en entiers a travers deux
            // changements d'echelle (logique MC -> device -> CSS).
            String detail = "attendu " + cssX + "," + cssY + " - recu " + (int) x + "," + (int) y;
            if (Math.abs(x - cssX) <= 2 && Math.abs(y - cssY) <= 2) pass(what, detail);
            else                                                    fail(what, detail);
        }

        private void checkClickTarget(String what, String id) {
            String ev = last("click");
            if (ev == null) { fail(what, "aucun clic recu par la page"); return; }
            String got = field(ev, "id");
            if (id.equals(got)) pass(what, "cible " + got);
            else                fail(what, "cible attendue " + id + ", recue " + got);
        }

        /** Un clic hors du rectangle ne doit ni etre transmis a la page, ni etre consomme. */
        private void checkOutside() {
            double ox, oy;
            if (panel.drawY() >= 4)      { ox = logX(200); oy = panel.drawY() - 3; }
            else if (panel.drawX() >= 4) { ox = panel.drawX() - 3; oy = logY(130); }
            else                         { ox = panel.drawX() + panel.drawWidth() + 3; oy = logY(130); }
            int before = events.size();
            boolean handled = panel.mouseClicked(ox, oy, InputConstants.MOUSE_BUTTON_LEFT);
            if (handled)                     fail("hit-test hors panneau", "le clic a ete consomme");
            else if (panel.contains(ox, oy)) fail("hit-test hors panneau", "point juge dans le panneau");
            else if (events.size() != before) fail("hit-test hors panneau", "la page a recu l'evenement");
            else pass("hit-test hors panneau", "non consomme et non transmis");
        }

        private void checkText() {
            String v = panel.view() == null ? null
                     : panel.view().evalString("document.getElementById('txt').value");
            if ("abc".equals(v)) pass("saisie clavier", "champ = abc");
            else                 fail("saisie clavier", "champ = " + v + " (attendu abc)");

            if (panel.hasInputFocus()) pass("focus champ editable", "hasInputFocus() vrai apres clic");
            else                       fail("focus champ editable", "hasInputFocus() faux");
        }

        private void checkCursor() {
            if (cursorShape == CursorTypes.POINTING_HAND) {
                pass("curseur pointer", "CursorTypes.POINTING_HAND recu");
            } else {
                fail("curseur pointer", "recu " + cursorShape + " (attendu POINTING_HAND)");
            }
        }

        private void checkScroll() {
            String v = panel.view() == null ? null
                     : panel.view().evalString("document.getElementById('scroll').scrollTop");
            double top = num(v);
            if (top > 0) pass("molette", "scrollTop = " + (int) top);
            else         fail("molette", "scrollTop = " + v);
        }

        private static double num(String v) {
            try { return v == null ? Double.NaN : Double.parseDouble(v.trim()); }
            catch (NumberFormatException e) { return Double.NaN; }
        }

        private void report() {
            long ko = results.stream().filter(r -> r.startsWith("FAIL")).count();
            if (ko == 0) LOG.info("[ul-panelprobe] input : {} verifications, tout passe.", results.size());
            else         LOG.error("[ul-panelprobe] input : {} ECHECS sur {} verifications.",
                                   ko, results.size());
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick);
            lastRenderNanos = System.nanoTime();

            // La phase perf comporte une configuration SANS panneau (la reference) : son compteur
            // de frames doit tourner meme quand panel == null, sinon elle ne se termine jamais.
            if (perfPhase) {
                if (panel != null) panel.render(graphics);
                perfTick();
                return;
            }

            if (panel == null) return;
            panel.render(graphics);

            if (typingPhase) { typingTick(); return; }
            if (inputPhase) { inputTick(); return; }

            if (!UltralightEngine.isReady()) return;   // init native en cours : on ne compte pas
            frames++;

            if (frames == 60 && STEPS[step].switchTo() > 0) {
                panel.setPreviewAspect(STEPS[step].switchTo());
            }
            if (frames == 75 || (frames == 135 && STEPS[step].switchTo() > 0)) {
                LOG.info("[ul-panelprobe] capture {}{} : CSS {}x{}, rect {},{} {}x{}",
                        STEPS[step].label(), frames == 135 ? " [+1s]" : " [juste apres]",
                        panel.cssWidth(), panel.cssHeight(),
                        panel.drawX(), panel.drawY(), panel.drawWidth(), panel.drawHeight());
                grabRequested = true;   // prise au prochain tick, entre deux frames
            } else if (frames > (STEPS[step].switchTo() > 0 ? 155 : 95)) {
                nextStep();
            }
        }


        // =====================================================================
        //  Phase perf : coût réel d'un overlay, mesuré sur le temps de frame du client.
        // =====================================================================

        /** Une configuration mesurée. */
        private record PerfCase(String label, boolean withPanel, boolean animated, boolean fullScreen) { }

        private static final PerfCase[] PERF_CASES = {
                new PerfCase("sans panneau (reference)", false, false, false),
                new PerfCase("page statique, quart d'ecran", true, false, false),
                new PerfCase("page animee, quart d'ecran", true, true, false),
                new PerfCase("page animee, plein ecran", true, true, true),
        };

        // Le monde continue de se charger pendant plusieurs secondes apres l'entree en jeu et les
        // FPS montent tout du long. Sans une longue stabilisation, cette derive ecrase l'effet
        // mesure : la 1re configuration parait toujours la plus lente, quelle qu'elle soit.
        private static final int PERF_SETTLE = 600;   // frames avant la 1re mesure
        private static final int PERF_WARMUP = 90;    // frames ignorees au debut de chaque cas
        private static final int PERF_SAMPLE = 240;   // frames mesurees
        private static final int PERF_ROUNDS = 2;     // 2 tours : la derive residuelle devient visible
        private boolean perfSettled = false;
        private int perfSettleFrames = 0;
        private int perfRound = 0;

        private void startPerfPhase() {
            perfPhase = true;
            perfCase = -1;
            perfRound = 0;
            UltralightEngine.perfEnabled = true;
            LOG.info("[ul-panelprobe] phase perf - stabilisation ({} frames), puis {} tours de {} configurations.",
                    PERF_SETTLE, PERF_ROUNDS, PERF_CASES.length);
        }

        private void nextPerfCase() {
            if (perfCase >= 0) reportPerfCase();
            if (panel != null) { panel.close(); panel = null; }
            perfCase++;
            if (perfCase >= PERF_CASES.length) {
                perfRound++;
                if (perfRound >= PERF_ROUNDS) {
                    UltralightEngine.perfEnabled = false;
                    finish();
                    return;
                }
                perfCase = 0;
                LOG.info("[ul-panelprobe] perf - tour {}/{}", perfRound + 1, PERF_ROUNDS);
            }
            PerfCase c = PERF_CASES[perfCase];
            LOG.info("[ul-panelprobe] perf {}/{} : {}", perfCase + 1, PERF_CASES.length, c.label());
            if (c.withPanel()) {
                UltralightPanel.Builder b = UltralightPanel.builder().design(1280, 720);
                if (!c.fullScreen()) b.bounds(0.25f, 0.25f, 0.5f, 0.5f);
                panel = b.fit(UltralightPanel.Fit.FILL).build();
                String page = perfHtml == null ? null
                        : (c.animated() ? perfHtml.replace("/*ANIM*/", "startAnimation();") : perfHtml);
                if (page != null) panel.loadHTML(page);
            }
            frames = 0;
            frameNanos.clear();
            lastFrameNano = 0L;
            UltralightEngine.perfReset();
        }

        private void perfTick() {
            if (!perfSettled) {
                // On laisse le monde finir de se charger avant toute mesure.
                if (++perfSettleFrames < PERF_SETTLE) return;
                perfSettled = true;
                LOG.info("[ul-panelprobe] perf - stabilise, debut des mesures.");
                LOG.info("[ul-panelprobe] perf - tour 1/{}", PERF_ROUNDS);
                nextPerfCase();
                return;
            }
            frames++;
            long now = System.nanoTime();
            if (frames > PERF_WARMUP && lastFrameNano != 0L) frameNanos.add(now - lastFrameNano);
            lastFrameNano = now;
            // Overlay anime sans input : sans ceci la vue se fige apres la 1re frame.
            if (panel != null && PERF_CASES[perfCase].animated()) panel.requestRepaint();
            if (frames >= PERF_WARMUP + PERF_SAMPLE) nextPerfCase();
        }

        private void reportPerfCase() {
            if (frameNanos.isEmpty()) return;
            List<Long> sorted = new ArrayList<>(frameNanos);
            Collections.sort(sorted);
            double mean = frameNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
            double p95  = sorted.get((int) (sorted.size() * 0.95)) / 1e6;
            PerfCase c = PERF_CASES[perfCase];
            String pump = c.withPanel()
                    ? String.format(java.util.Locale.ROOT, " - cycle moteur %.2f ms/frame",
                                    UltralightEngine.perfAverageMicros() / 1000.0)
                    : "";
            LOG.info(String.format(java.util.Locale.ROOT,
                    "[ul-panelprobe]   [tour %d] %-28s frame %.2f ms (%.0f FPS) - p95 %.2f ms%s",
                    perfRound + 1, c.label(), mean, 1000.0 / mean, p95, pump));
        }

        /** Trace ce que Minecraft livre a l'ecran, pour distinguer "SDL ne produit rien" de
         *  "la page ne recoit pas". */
        @Override
        public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
            if (typingPhase) {
                LOG.info("[ul-panelprobe] MC -> ecran : charTyped \"{}\"", input.codepointAsString());
                if (panel != null) panel.charTyped(input.codepointAsString());
                return true;
            }
            return super.charTyped(input);
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
            if (typingPhase) {
                LOG.info("[ul-panelprobe] MC -> ecran : keyPressed code={} mods={}", key.key(), key.modifiers());
                if (panel != null) panel.keyPressed(key.key(), key.modifiers());
                return true;
            }
            return super.keyPressed(key);
        }

        @Override
        public void removed() {
            if (panel != null) { panel.close(); panel = null; }
        }

        @Override public boolean shouldCloseOnEsc() { return false; }
        @Override public boolean isPauseScreen()    { return false; }

        private static String read(String resource) {
            try (InputStream in = PanelProbeScreen.class.getResourceAsStream(resource)) {
                return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOG.error("[ul-panelprobe] lecture {} échouée", resource, e);
                return null;
            }
        }
    }
}
