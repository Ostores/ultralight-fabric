package net.ostore.ultralight;

import me.ayydxn.luminescence.console.ULMessageLevel;
import me.ayydxn.luminescence.console.ULMessageSource;
import me.ayydxn.luminescence.events.KeyEventType;
import me.ayydxn.luminescence.events.MouseButton;
import me.ayydxn.luminescence.events.MouseEventType;
import me.ayydxn.luminescence.events.ScrollEventType;
import me.ayydxn.luminescence.events.ULKeyEvent;
import me.ayydxn.luminescence.events.ULMouseEvent;
import me.ayydxn.luminescence.events.ULScrollEvent;
import me.ayydxn.luminescence.geometry.ULIntRect;
import me.ayydxn.luminescence.javascript.JSContext;
import me.ayydxn.luminescence.javascript.JSException;
import me.ayydxn.luminescence.javascript.JSFunction;
import me.ayydxn.luminescence.javascript.JSValue;
import me.ayydxn.luminescence.surface.ULBitmapSurface;
import me.ayydxn.luminescence.surface.ULSurface;
import me.ayydxn.luminescence.view.ULCursor;
import me.ayydxn.luminescence.view.ULView;
import me.ayydxn.luminescence.view.ULViewListener;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Wrapper autour d'une {@link ULView} (Ultralight 1.4 / Luminescence) — pipeline CPU mode.
 *
 * <p>Tout s'exécute sur le <b>render thread</b>. Le pipeline pixel (surface BGRA prémultiplié →
 * texture MC RGBA straight-alpha, via un buffer natif que l'on possède) et le mapping d'input
 * sont identiques à la version 1.3 ; seuls les appels au moteur passent par l'API Luminescence.
 *
 * <p>Pont JS : {@code window.ulQuery(data)} (fonction native via {@link JSFunction} ;
 * nom configurable via {@link #setBridgeName}).
 */
public final class UltralightBrowserView {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/view");

    /** Nom par défaut de la fonction JS du pont, pour les vues créées ensuite. */
    private static volatile String defaultBridgeName = "ulQuery";

    /**
     * Change le nom de pont par défaut des <b>prochaines</b> vues.
     *
     * <p>⚠️ C'est un réglage <b>global au jeu</b> : si deux mods l'appellent, le dernier gagne et
     * casse le pont de l'autre. Pour une bibliothèque partagée, préférer
     * {@link #setBridgeName(String)} sur la vue, ou {@code UltralightPanel.Builder.bridgeName(...)}.
     */
    public static void setDefaultBridgeName(String name) {
        defaultBridgeName = validateBridgeName(name, defaultBridgeName);
    }

    /**
     * Le nom est concaténé dans du JS ({@code window['<nom>']}) : un nom contenant une apostrophe
     * casserait le script, voire y injecterait du code. On n'accepte donc qu'un identifiant.
     */
    private static String validateBridgeName(String name, String fallback) {
        if (name == null || name.isEmpty()) return fallback;
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException(
                    "Nom de pont JS invalide : « " + name + " ». Attendu un identifiant JavaScript "
                    + "([A-Za-z_$][A-Za-z0-9_$]*), le nom étant injecté dans window['<nom>'].");
        }
        return name;
    }

    /** recip[a] ≈ (255/a) << 16 — dé-prémultiplication sans division par pixel. */
    private static final int[] UNPREMULT_RECIP = new int[256];
    static {
        UNPREMULT_RECIP[0] = 0;
        for (int a = 1; a < 256; a++) UNPREMULT_RECIP[a] = (int) ((255L * 65536L) / a);
    }

    private final ULView view;
    private final int viewId;

    // MC texture adossée à un buffer natif que nous possédons.
    private DynamicTexture mcTexture;
    private Identifier texIdentifier;
    private int texW, texH;          // taille de la texture courante
    private ByteBuffer pixelBuffer;   // mémoire native libérée par NativeImage.close()
    private IntBuffer  pixelInts;
    private boolean textureReady   = false;
    private boolean needsFullUpload = true;
    /** Force un upload complet pendant N frames après un input : Ultralight ne signale pas
     *  toujours via dirtyBounds les changements de survol/scroll/saisie → on uploade quand même. */
    private int forcePaint = 0;
    private static final int REPAINT_AFTER_INPUT = 12;

    // JS bridge + curseur
    /** Nom du pont pour CETTE vue : deux mods peuvent ainsi cohabiter sans se marcher dessus. */
    private volatile String bridgeName = defaultBridgeName;
    private volatile Consumer<String> queryHandler;
    private volatile Consumer<CursorType> cursorHandler;

    // Page lifecycle
    private final AtomicBoolean pageReady = new AtomicBoolean(false);
    private Consumer<Void> onPageReadyCallback;

    /**
     * @throws IllegalStateException si le moteur n'est pas encore prêt. L'initialisation native est
     *         différée au premier frame rendu : créer une vue avant (typiquement depuis
     *         {@code onInitializeClient}) passerait un renderer nul au code natif, ce qui produit
     *         un {@code ACCESS_VIOLATION}, donc un crash JVM impossible à rattraper. Mieux vaut
     *         une exception claire. {@link UltralightPanel} gère cette attente tout seul.
     */
    public UltralightBrowserView(int width, int height, double deviceScale) {
        UltralightEngine.checkRenderThread("new UltralightBrowserView");
        if (!UltralightEngine.isReady()) {
            throw new IllegalStateException(
                    "UltralightEngine n'est pas prêt : l'init native est différée au 1er frame rendu. "
                    + "Vérifier UltralightEngine.isReady() avant de créer une vue, ou utiliser "
                    + "UltralightPanel qui attend pour vous.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Taille de vue invalide : " + width + "x" + height);
        }
        this.viewId = UltralightEngine.VIEW_COUNTER.getAndIncrement();
        this.view   = UltralightEngine.createView(width, height, true, deviceScale);
        this.view.setListener(new BridgeListener());
        UltralightEngine.registerView(this);
    }

    /** Nom de la fonction JS du pont pour cette vue. Prend effet au prochain chargement de page. */
    public void setBridgeName(String name) {
        this.bridgeName = validateBridgeName(name, this.bridgeName);
    }

    // =========================================================================
    //  API publique — render thread
    // =========================================================================

    public void loadHTML(String html) {
        UltralightEngine.checkRenderThread("loadHTML");
        pageReady.set(false); bridgeSignaled = true; view.loadHTML(sanitizeForLoad(html));
    }

    public void loadURL(String url) {
        UltralightEngine.checkRenderThread("loadURL");
        pageReady.set(false); bridgeSignaled = true; view.loadURL(url);
    }

    /**
     * Convertit les caractères hors du plan multilingue de base (BMP) — c.-à-d. les paires de
     * substitution, comme les emoji 🗑/🎯/… — en entités numériques HTML ({@code &#NNN;}).
     *
     * <p>Le passage {@code String} → natif de {@code view.loadHTML} échoue sur les surrogates et
     * renvoie un document VIDE (page blanche, scripts jamais exécutés). On rend donc la chaîne
     * ASCII-safe pour les caractères hors-BMP avant de la passer au moteur ; WebKit décode les
     * entités normalement.
     */
    private static String sanitizeForLoad(String html) {
        if (html == null) return "";
        StringBuilder sb = new StringBuilder(html.length() + 16);
        for (int i = 0; i < html.length(); ) {
            int cp = html.codePointAt(i);
            if (cp > 0xFFFF) { sb.append("&#").append(cp).append(';'); i += Character.charCount(cp); }
            else { sb.append(html.charAt(i)); i++; }
        }
        return sb.toString();
    }

    /**
     * Redimensionne la vue (pixels device). La texture est détruite puis recréée : pendant une à
     * trois frames {@link #getTextureIdentifier()} renvoie {@code null} et il n'y a rien à dessiner.
     *
     * <p>On a essayé de garder l'ancienne texture vivante sous un nouvel identifiant pour éviter ce
     * trou : le renderer de GUI de MC 26.x n'aime pas qu'un identifiant de texture apparaisse et
     * disparaisse à ce rythme, et rend la texture en damier. Un trou d'une frame vaut mieux.
     * Le vrai remède au clignotement est de ne pas redimensionner à chaque frame, ce dont
     * {@code UltralightPanel} se charge (redimensionnement temporisé).
     */
    public void resize(int physicalWidth, int physicalHeight) {
        UltralightEngine.checkRenderThread("resize");
        if (physicalWidth <= 0 || physicalHeight <= 0) return;   // fenêtre minimisée
        if (physicalWidth == texW && physicalHeight == texH && mcTexture != null) return;
        view.resize(physicalWidth, physicalHeight);
        // Libération immédiate ici, et elle est sûre : UltralightPanel redimensionne AVANT de
        // dessiner et ne dessine rien tant que la nouvelle texture n'est pas prête. La reporter
        // serait même faux : la nouvelle texture reprend le même identifiant, et une libération
        // différée par identifiant emporterait la nouvelle.
        destroyMcTexture(false);
        needsFullUpload = true;
        textureReady    = false;
        // Sans ça, Ultralight ne re-rastérise que ce qu'il juge sale : sur la surface fraîchement
        // agrandie, tout ce qui est hors de cette zone reste non peint et on publie une texture à
        // moitié vide pendant quelques frames (bordure au bon endroit, fond absent).
        forcePaint      = REPAINT_AFTER_INPUT;
    }

    public void setDeviceScale(double deviceScale) {
        try { view.setDeviceScale(deviceScale); } catch (Throwable ignored) {}
    }

    public void executeJavaScript(String script) {
        UltralightEngine.checkRenderThread("executeJavaScript");
        try { view.evaluateScript(script, new String[1]); }
        catch (Throwable e) { LOG.debug("[ul-view:{}] JS error: {}", viewId, e.getMessage()); }
    }

    public void setQueryHandler(Consumer<String> handler)       { this.queryHandler = handler; }
    public void updateQueryHandler(Consumer<String> handler)    { this.queryHandler = handler; }
    public void setOnPageReadyCallback(Consumer<Void> callback) { this.onPageReadyCallback = callback; }
    /**
     * Handler de curseur. Par defaut la vue applique elle-meme le curseur demande par la page
     * ({@link CursorType#select()}) : il n'y a plus rien a faire cote mod. Poser un handler
     * remplace ce comportement, par exemple pour ignorer les curseurs d'une page tierce.
     */
    public void setCursorHandler(Consumer<CursorType> handler) { this.cursorHandler = handler; }

    public Identifier getTextureIdentifier() { return textureReady ? texIdentifier : null; }
    /** Largeur en pixels de la texture courante — à passer en argument de région au blit. */
    public int getTextureWidth()             { return textureReady ? texW : 0; }
    /** Hauteur en pixels de la texture courante — à passer en argument de région au blit. */
    public int getTextureHeight()            { return textureReady ? texH : 0; }
    public boolean isTextureReady()          { return textureReady; }
    public boolean isPageReady()             { return pageReady.get(); }
    public void    setPageReady(boolean v)   { pageReady.set(v); }

    public ULView getView() { return view; }

    public void close() {
        UltralightEngine.checkRenderThread("close");
        // Sans ca, Minecraft resterait persuade qu'une saisie texte est en cours dans une vue
        // detruite, et le backend SDL avalerait les caracteres suivants.
        if (textInputActive) { textInputActive = false; notifyTextInput(false); }
        UltralightEngine.unregisterView(this);
        destroyMcTexture(true);
        textureReady = false;
        pageReady.set(false);
        try { view.destroy(); } catch (Throwable ignored) {}
    }

    // =========================================================================
    //  Input — render thread. Coords en pixels CSS de la vue (= device ÷ deviceScale).
    //  Codes touches/boutons/modifiers : ceux de InputConstants (scancodes SDL depuis MC 26.3).
    // =========================================================================

    public void mouseMoved(int x, int y) {
        UltralightEngine.checkRenderThread("mouseMoved");
        forcePaint = REPAINT_AFTER_INPUT;
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_MOVED, x, y, MouseButton.NONE)) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { inputFailed("souris", t); }
    }

    public void mousePressed(int x, int y, int mcButton) {
        UltralightEngine.checkRenderThread("mousePressed");
        forcePaint = REPAINT_AFTER_INPUT;
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_DOWN, x, y, mapButton(mcButton))) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { inputFailed("souris", t); }
    }

    public void mouseReleased(int x, int y, int mcButton) {
        UltralightEngine.checkRenderThread("mouseReleased");
        forcePaint = REPAINT_AFTER_INPUT;
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_UP, x, y, mapButton(mcButton))) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { inputFailed("souris", t); }
    }

    public void scroll(int deltaXpixels, int deltaYpixels) {
        UltralightEngine.checkRenderThread("scroll");
        forcePaint = REPAINT_AFTER_INPUT;
        try (ULScrollEvent e = new ULScrollEvent(ScrollEventType.SCROLL_BY_PIXEL, deltaXpixels, deltaYpixels)) {
            view.fireScrollEvent(e);
        } catch (Throwable t) { inputFailed("molette", t); }
    }

    public void charTyped(String text) {
        UltralightEngine.checkRenderThread("charTyped");
        if (text == null || text.isEmpty()) return;
        forcePaint = REPAINT_AFTER_INPUT;
        try (ULKeyEvent e = new ULKeyEvent(KeyEventType.CHAR, 0, 0, 0, text, text, false, false, false)) {
            view.fireKeyEvent(e);
        } catch (Throwable t) { inputFailed("texte", t); }
    }

    /** Echec d'input deja signale : une page qui ne recoit plus rien doit se voir sans debug. */
    private boolean inputFailureWarned = false;

    private void inputFailed(String kind, Throwable t) {
        if (!inputFailureWarned) {
            inputFailureWarned = true;
            LOG.warn("[ul-view:{}] echec d'envoi d'un evenement ({}) a la page, signale une seule fois : {}",
                    viewId, kind, t.toString());
        }
        LOG.debug("[ul-view:{}] {} : {}", viewId, kind, t.getMessage());
    }

    /**
     * Touche pressée, codes tels que Minecraft les fournit ({@code KeyEvent.key()} = position
     * physique). Préférer {@link #keyPressed(KeyEvent)} : cette forme ne connaît pas la
     * disposition du clavier, donc en AZERTY la touche A arrive à la page comme un Q
     * (Ctrl+A devient Ctrl+Q, Ctrl+Z devient Ctrl+W).
     */
    public void keyPressed(int mcKey, int mcModifiers) {
        UltralightEngine.checkRenderThread("keyPressed");
        forcePaint = REPAINT_AFTER_INPUT;
        fireKey(KeyEventType.RAW_KEY_DOWN, mcKey, 0, mcModifiers);
    }

    public void keyReleased(int mcKey, int mcModifiers) {
        UltralightEngine.checkRenderThread("keyReleased");
        forcePaint = REPAINT_AFTER_INPUT;
        fireKey(KeyEventType.KEY_UP, mcKey, 0, mcModifiers);
    }

    /**
     * Touche pressée, à partir de l'événement Minecraft complet. Forme recommandée : elle tient
     * compte de la disposition du clavier pour les lettres, donc des raccourcis (Ctrl+A, Ctrl+Z…)
     * et de {@code event.keyCode} côté page.
     */
    public void keyPressed(KeyEvent event) {
        UltralightEngine.checkRenderThread("keyPressed");
        forcePaint = REPAINT_AFTER_INPUT;
        fireKey(KeyEventType.RAW_KEY_DOWN, event.key(), event.keycode(), event.modifiers());
    }

    public void keyReleased(KeyEvent event) {
        UltralightEngine.checkRenderThread("keyReleased");
        forcePaint = REPAINT_AFTER_INPUT;
        fireKey(KeyEventType.KEY_UP, event.key(), event.keycode(), event.modifiers());
    }

    /**
     * Force la re-rastérisation pour les prochaines frames. À appeler chaque frame pour un
     * overlay <b>animé sans input</b> (ex. HUD à canvas) : sinon, faute de {@code setNeedsPaint},
     * la vue ne se repeint pas après la frame initiale et l'animation (requestAnimationFrame)
     * se fige.
     */
    public void requestRepaint() { forcePaint = REPAINT_AFTER_INPUT; }

    /** Repeindre à chaque frame : voir {@link #setAnimated(boolean)}. */
    private volatile boolean animated = false;

    /**
     * Page animée (CSS, {@code requestAnimationFrame}, vidéo…) : la vue est repeinte à chaque
     * frame. Sans ce réglage, Ultralight ne repeint qu'après une entrée ou à son battement lent, et
     * une animation sans interaction se fige à l'écran. Coût : la re-rastérisation permanente
     * (voir docs/API.md, performance). Inutile pour une page statique.
     */
    public void setAnimated(boolean animated) { this.animated = animated; }
    public boolean isAnimated()               { return animated; }

    public void focus() {
        UltralightEngine.checkRenderThread("focus");
        try { view.focus(); } catch (Throwable ignored) {}
    }

    public void unfocus() {
        UltralightEngine.checkRenderThread("unfocus");
        try { view.unfocus(); } catch (Throwable ignored) {}
    }
    public boolean hasInputFocus() { try { return view.hasInputFocus(); } catch (Throwable t) { return false; } }

    private void fireKey(KeyEventType type, int mcKey, int sdlKeycode, int mcModifiers) {
        try (ULKeyEvent e = new ULKeyEvent(type, mapModifiers(mcModifiers),
                toWindowsVK(mcKey, sdlKeycode), 0, "", "", isKeypad(mcKey), false, false)) {
            view.fireKeyEvent(e);
        } catch (Throwable t) { inputFailed("clavier", t); }
    }

    /**
     * MC 26.3 est passe de GLFW a SDL : la numerotation des boutons a change (gauche/milieu/droite
     * valaient 0/1/2 en GLFW, ils valent 1/2/3 en SDL). Ne jamais coder ces valeurs en dur.
     */
    private static MouseButton mapButton(int mcButton) {
        return switch (mcButton) {
            case InputConstants.MOUSE_BUTTON_RIGHT  -> MouseButton.RIGHT;
            case InputConstants.MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE;
            default                                 -> MouseButton.LEFT;
        };
    }

    /** Modifiers Ultralight : ALT=1, CTRL=1<<1, META=1<<2, SHIFT=1<<3. */
    private static int mapModifiers(int mcModifiers) {
        int m = 0;
        if ((mcModifiers & InputConstants.MOD_ALT)     != 0) m |= 1;
        if ((mcModifiers & InputConstants.MOD_CONTROL) != 0) m |= 1 << 1;
        if ((mcModifiers & InputConstants.MOD_SUPER)   != 0) m |= 1 << 2;
        if ((mcModifiers & InputConstants.MOD_SHIFT)   != 0) m |= 1 << 3;
        return m;
    }

    /**
     * Code de touche Minecraft (scancode SDL depuis la 26.3) vers virtual key code Windows,
     * ce qu'attend Ultralight.
     *
     * <p>En GLFW les codes de A-Z et 0-9 coincidaient avec l'ASCII, donc avec les VK : un simple
     * passe-plat suffisait. Les scancodes SDL ne coincident avec rien (A vaut 4, espace 44), il
     * faut donc une vraie table. Une touche inconnue renvoie 0 : Ultralight l'ignore, ce qui vaut
     * mieux que d'envoyer un VK arbitraire.
     */
    static int toWindowsVK(int mcKey, int sdlKeycode) {
        // Lettres : le keycode SDL suit la disposition du clavier ('a'..'z' pour la touche qui
        // PORTE cette lettre), comme les VK Windows. La position physique, elle, ferait d'un A
        // AZERTY un Q. Chiffres et ponctuation restent sur la position : c'est aussi ce que fait
        // Windows (la rangée du haut d'un AZERTY donne VK_1..VK_9, pas '&', 'é'...).
        if (sdlKeycode >= 'a' && sdlKeycode <= 'z') return 0x41 + (sdlKeycode - 'a');
        return mcKeyToWindowsVK(mcKey);
    }

    /** Pavé numérique : absents des constantes d'InputConstants, scancodes SDL stables. */
    private static final int SDL_KP_DIVIDE = 84, SDL_KP_MINUS = 86, SDL_KP_PERIOD = 99;

    private static boolean isKeypad(int mcKey) {
        return (mcKey >= InputConstants.KEY_NUMPAD1 && mcKey <= InputConstants.KEY_NUMPAD9)
                || mcKey == InputConstants.KEY_NUMPAD0 || mcKey == InputConstants.KEY_NUMPADENTER
                || mcKey == InputConstants.KEY_MULTIPLY || mcKey == InputConstants.KEY_ADD
                || mcKey == SDL_KP_DIVIDE || mcKey == SDL_KP_MINUS || mcKey == SDL_KP_PERIOD;
    }

    static int mcKeyToWindowsVK(int mcKey) {
        // Lettres et chiffres : plages contigues cote SDL, on translate.
        if (mcKey >= InputConstants.KEY_A && mcKey <= InputConstants.KEY_Z) {
            return 0x41 + (mcKey - InputConstants.KEY_A);          // 'A'..'Z'
        }
        if (mcKey >= InputConstants.KEY_F1 && mcKey <= InputConstants.KEY_F12) {
            return 0x70 + (mcKey - InputConstants.KEY_F1);         // VK_F1..VK_F12
        }
        if (mcKey >= InputConstants.KEY_F13 && mcKey <= InputConstants.KEY_F24) {
            return 0x7C + (mcKey - InputConstants.KEY_F13);        // VK_F13..VK_F24
        }
        if (mcKey >= InputConstants.KEY_NUMPAD1 && mcKey <= InputConstants.KEY_NUMPAD9) {
            return 0x61 + (mcKey - InputConstants.KEY_NUMPAD1);    // VK_NUMPAD1..9
        }
        if (mcKey >= InputConstants.KEY_1 && mcKey <= InputConstants.KEY_9) {
            return 0x31 + (mcKey - InputConstants.KEY_1);          // '1'..'9'
        }
        if (mcKey == InputConstants.KEY_0) return 0x30;            // le 0 suit le 9 cote SDL

        return switch (mcKey) {
            case InputConstants.KEY_BACKSPACE   -> 0x08;
            case InputConstants.KEY_TAB         -> 0x09;
            case InputConstants.KEY_RETURN,
                 InputConstants.KEY_NUMPADENTER -> 0x0D;
            case InputConstants.KEY_ESCAPE      -> 0x1B;
            case InputConstants.KEY_SPACE       -> 0x20;
            case InputConstants.KEY_PAGEUP      -> 0x21;
            case InputConstants.KEY_PAGEDOWN    -> 0x22;
            case InputConstants.KEY_END         -> 0x23;
            case InputConstants.KEY_HOME        -> 0x24;
            case InputConstants.KEY_LEFT        -> 0x25;
            case InputConstants.KEY_UP          -> 0x26;
            case InputConstants.KEY_RIGHT       -> 0x27;
            case InputConstants.KEY_DOWN        -> 0x28;
            case InputConstants.KEY_INSERT      -> 0x2D;
            case InputConstants.KEY_DELETE      -> 0x2E;
            // Pavé numérique (le 0 suit le 9 cote SDL, comme la rangée du haut).
            case InputConstants.KEY_NUMPAD0     -> 0x60;
            case InputConstants.KEY_MULTIPLY    -> 0x6A;
            case InputConstants.KEY_ADD         -> 0x6B;
            case SDL_KP_MINUS                   -> 0x6D;
            case SDL_KP_PERIOD                  -> 0x6E;
            case SDL_KP_DIVIDE                  -> 0x6F;
            // Ponctuation (VK_OEM_*, positions US comme sous Windows).
            case InputConstants.KEY_SEMICOLON   -> 0xBA;
            case InputConstants.KEY_EQUALS      -> 0xBB;
            case InputConstants.KEY_COMMA       -> 0xBC;
            case InputConstants.KEY_MINUS       -> 0xBD;
            case InputConstants.KEY_PERIOD      -> 0xBE;
            case InputConstants.KEY_SLASH       -> 0xBF;
            case InputConstants.KEY_GRAVE       -> 0xC0;
            case InputConstants.KEY_LBRACKET    -> 0xDB;
            case InputConstants.KEY_BACKSLASH   -> 0xDC;
            case InputConstants.KEY_RBRACKET    -> 0xDD;
            case InputConstants.KEY_APOSTROPHE  -> 0xDE;
            // Modificateurs seuls : WebKit attend les VK génériques, pas gauche/droite.
            case InputConstants.KEY_LSHIFT,
                 InputConstants.KEY_RSHIFT      -> 0x10;
            case InputConstants.KEY_LCONTROL,
                 InputConstants.KEY_RCONTROL    -> 0x11;
            case InputConstants.KEY_LALT,
                 InputConstants.KEY_RALT        -> 0x12;
            case InputConstants.KEY_LGUI        -> 0x5B;
            case InputConstants.KEY_RGUI        -> 0x5C;
            case InputConstants.KEY_CAPSLOCK    -> 0x14;
            case InputConstants.KEY_PAUSE       -> 0x13;
            case InputConstants.KEY_PRINTSCREEN -> 0x2C;
            case InputConstants.KEY_NUMLOCK     -> 0x90;
            case InputConstants.KEY_SCROLLLOCK  -> 0x91;
            default                             -> 0;
        };
    }

    // =========================================================================
    //  Tick (render thread, chaque frame)
    // =========================================================================

    /**
     * Appelé AVANT {@code renderer.render()} : force la vue à se re-rastériser pendant quelques
     * frames après un input. Sinon Ultralight ne repeint qu'à son battement ~1 Hz → survol/scroll
     * en retard de ~1 s (alors qu'un drag, qui invalide en continu, reste fluide).
     */
    void prepareFrame() {
        if (forcePaint > 0 || animated) {
            try { view.setNeedsPaint(true); } catch (Throwable ignored) {}
        }
    }

    void onRendererTick() {
        paintSurface();
        if (UltralightEngine.perfEnabled) {
            long t0 = System.nanoTime();
            drainBridge();
            UltralightEngine.perfBridgeNanos += System.nanoTime() - t0;
        } else {
            drainBridge();
        }
        syncTextInputFocus();
    }

    /** Focus texte deja signale a Minecraft. */
    private boolean textInputActive = false;

    /**
     * Previent Minecraft quand un champ editable de la PAGE prend ou perd le focus.
     *
     * <p>Obligatoire depuis MC 26.3 : le backend SDL doit savoir qu'une saisie texte est en cours,
     * sinon il se desynchronise et la saisie de caracteres cesse completement de fonctionner. Notre
     * page joue exactement le role d'un widget de saisie personnalise, sauf que le focus vit cote
     * WebKit : on le sonde donc a chaque frame et on ne signale que les changements.
     */
    private void syncTextInputFocus() {
        boolean focused;
        try { focused = view.hasInputFocus(); } catch (Throwable t) { return; }
        if (focused == textInputActive) return;
        textInputActive = focused;
        notifyTextInput(focused);
    }

    private void notifyTextInput(boolean focused) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.textInputManager().onTextInputFocusChange(this, focused);
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] TextInputManager: {}", viewId, t.getMessage());
        }
    }

    // =========================================================================
    //  Pipeline surface → NativeImageBackedTexture (mémoire native directe)
    // =========================================================================

    private void paintSurface() {
        ULSurface surface = view.getSurface();
        if (surface == null || surface.getHandle() == 0L) return;

        ULIntRect dirty = surface.getDirtyBounds();
        int w = surface.getWidth();
        int h = surface.getHeight();
        if (w <= 0 || h <= 0) return;

        // forcePaint pilote setNeedsPaint(true) dans prepareFrame() (= reactivite survol/scroll : on
        // force Ultralight a re-rasteriser). Mais il ne doit PAS forcer un upload PLEIN ECRAN : on
        // s'appuie sur les dirty bounds rapportes par Ultralight pour n'uploader que le rectangle sale
        // (NativeImageBackedTexture.upload() reenverrait toute la texture → bande passante → chute FPS).
        boolean full = needsFullUpload; // plein upload UNIQUEMENT au 1er paint / apres resize
        if (forcePaint > 0) forcePaint--;
        // MC 26.x n'accepte plus d'upload d'un sous-rectangle arbitraire : writeToTexture prend un
        // ByteBuffer CONTIGU de width*height. On reduit donc la zone sale a une BANDE pleine largeur
        // (les lignes y sont contigues dans notre buffer) : on garde l'essentiel du gain de bande
        // passante (un HUD ne salit que quelques lignes) sans recopie intermediaire.
        int dy = 0, dh = h;
        if (!full) {
            if (dirty == null || dirty.bottom <= dirty.top) return; // rien de sale
            dy = Math.max(0, dirty.top);
            dh = Math.min(h, dirty.bottom) - dy;
            if (dh <= 0) return;
        }

        ensureMcTexture(w, h);
        if (pixelInts == null) return;

        ULBitmapSurface bitmap = ULBitmapSurface.fromSurface(surface);
        try (ULSurface.LockedPixels locked = bitmap.acquirePixelLock()) {
            ByteBuffer pixels = locked.pixels();
            IntBuffer src = pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer(); // BGRA prémult → int LE 0xAARRGGBB
            // Ultralight aligne le pas de ligne (1409 px de large → 1412) : toujours passer
            // par getRowBytes(), jamais supposer largeur == pas.
            int srcStride = surface.getRowBytes() / 4;
            blit(src, srcStride, w, 0, dy, w, dh);
            needsFullUpload = false;
            if (full) mcTexture.upload();      // 1er paint / resize : upload plein (cree aussi la GpuTexture)
            else      uploadBand(dy, dh, w);   // sinon : seulement la bande sale
            textureReady = true;
            paintCount++;
            if (full && DUMP_TEXTURE) dumpTexture(w, h, srcStride, surface.getRowBytes() / 4);
        } catch (Throwable t) {
            // Un echec de peinture durable se traduit par un ecran vide. En debug seul, personne ne
            // le voit : on le signale une fois au niveau par defaut.
            if (!paintFailureWarned) {
                paintFailureWarned = true;
                LOG.warn("[ul-view:{}] echec de peinture, la vue ne se mettra plus a jour : {}",
                        viewId, t.toString());
            }
            LOG.debug("[ul-view:{}] paint: {}", viewId, t.getMessage());
        } finally {
            surface.clearDirtyBounds();
        }
    }

    /** Diagnostic : vider la texture produite sur disque à chaque repaint complet. */
    private static final boolean DUMP_TEXTURE =
            Boolean.getBoolean("ultralight.dumptexture")
            || "true".equalsIgnoreCase(System.getenv("ULTRALIGHT_DUMPTEXTURE"));

    private int dumpCount = 0;

    /** Écrit l'image telle qu'on l'a composée, pour distinguer « notre texture est fausse » de
     *  « Minecraft la dessine mal ». */
    private void dumpTexture(int w, int h, int usedStride, int declaredStride) {
        try {
            java.nio.file.Path out = java.nio.file.Path.of("ul-dump",
                    "view" + viewId + "_" + (dumpCount++) + "_" + w + "x" + h
                            + "_stride" + usedStride + "_declared" + declaredStride + ".png");
            java.nio.file.Files.createDirectories(out.getParent());
            mcTexture.getPixels().writeToFile(out);
            LOG.info("[ul-view:{}] dump texture → {}", viewId, out.toAbsolutePath());
        } catch (Throwable t) {
            LOG.warn("[ul-view:{}] dump texture échoué : {}", viewId, t.toString());
        }
    }

    /**
     * Upload GPU d'une bande de lignes seulement (vs {@code mcTexture.upload()} qui reenvoie toute
     * la texture). La bande [y0, y0+rows[ est contigue dans notre buffer natif, on la passe donc
     * telle quelle via {@code memSlice} — aucune copie intermediaire.
     *
     * <p>{@code writeToTexture(tex, buffer, mipLevel, depthOrLayer, destX, destY, width, height)} :
     * depuis MC 26.x, la surcharge qui prenait un sous-rectangle source (srcX/srcY) n'existe plus.
     * Repli sur l'upload plein si la GpuTexture n'existe pas encore ou si l'appel echoue.
     */
    private void uploadBand(int y0, int rows, int w) {
        GpuTexture gpu = mcTexture.getTexture();
        if (gpu == null || pixelBuffer == null) { mcTexture.upload(); return; }
        try {
            ByteBuffer band = MemoryUtil.memSlice(pixelBuffer, y0 * w * 4, rows * w * 4);
            RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(gpu, band, 0, 0, 0, y0, w, rows);
        } catch (Throwable t) {
            // Signalé UNE fois : le repli fonctionne mais coûte toute la bande passante de la
            // texture à chaque frame, c'est une régression de perf que le dev doit voir.
            if (!bandUploadFallbackWarned) {
                bandUploadFallbackWarned = true;
                LOG.warn("[ul-view:{}] upload par bande indisponible, repli sur l'upload plein : {}",
                        viewId, t.toString());
            }
            mcTexture.upload();
        }
    }

    /** Repli d'upload déjà signalé (on ne veut pas un log par frame). */
    private boolean bandUploadFallbackWarned = false;
    /** Échec de peinture déjà signalé (idem). */
    private boolean paintFailureWarned = false;

    private int[] blitRow; // tampon de ligne réutilisé (évite la réallocation)

    /**
     * Région surface (BGRA prémultiplié) → buffer texture (RGBA straight-alpha), par lignes en BLOC.
     * Transferts {@code IntBuffer} groupés + traitement en {@code int[]} (bien plus rapide que des
     * get/put pixel-par-pixel), avec chemins rapides opaque (simple swap R↔B) et transparent.
     */
    private void blit(IntBuffer src, int srcStride, int dstStride, int x0, int y0, int rw, int rh) {
        int[] row = blitRow;
        if (row == null || row.length < rw) { row = new int[rw]; blitRow = row; }
        for (int y = y0; y < y0 + rh; y++) {
            src.position(y * srcStride + x0);
            src.get(row, 0, rw);                       // lecture en bloc de la ligne sale
            for (int i = 0; i < rw; i++) {
                int v = row[i];                        // 0xAARRGGBB prémultiplié
                int a = v >>> 24;
                if (a == 255) {                        // opaque : simple swap R↔B
                    row[i] = (v & 0xFF00FF00) | ((v & 0xFF) << 16) | ((v >>> 16) & 0xFF);
                } else if (a == 0) {
                    row[i] = 0;
                } else {                               // dé-prémultiplication
                    int rcp = UNPREMULT_RECIP[a];
                    int r = Math.min(255, (((v >>> 16) & 0xFF) * rcp + 32768) >> 16);
                    int g = Math.min(255, (((v >>> 8)  & 0xFF) * rcp + 32768) >> 16);
                    int b = Math.min(255, (( v         & 0xFF) * rcp + 32768) >> 16);
                    row[i] = (a << 24) | (b << 16) | (g << 8) | r;
                }
            }
            pixelInts.position(y * dstStride + x0);
            pixelInts.put(row, 0, rw);                 // écriture en bloc
        }
    }

    private void ensureMcTexture(int w, int h) {
        if (mcTexture != null) return;
        long bytes = (long) w * h * 4L;
        // memAlloc prend un int : au-dela de 2 Go la conversion tronquerait silencieusement et on
        // ecrirait hors du tampon. On refuse, en le disant.
        if (bytes <= 0L || bytes > Integer.MAX_VALUE) {
            if (!textureTooLargeWarned) {
                textureTooLargeWarned = true;
                LOG.error("[ul-view:{}] taille de vue irrealiste ({}x{} = {} octets) : rendu abandonne. "
                        + "Plafonner la resolution (UltralightPanel.Builder.maxViewPixels).",
                        viewId, w, h, bytes);
            }
            return;
        }

        ByteBuffer buf = MemoryUtil.memAlloc((int) bytes);
        NativeImage img = null;
        try {
            // Des que la NativeImage detient l'adresse, c'est SA fermeture qui libere le tampon.
            img = new NativeImage(NativeImage.Format.RGBA, w, h, false, MemoryUtil.memAddress(buf));
            DynamicTexture tex = new DynamicTexture(() -> "ul_view_" + viewId, img);
            Identifier id = Identifier.fromNamespaceAndPath("ultralight", "ul_view_" + viewId);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) mc.getTextureManager().register(id, tex);

            pixelBuffer   = buf;
            pixelInts     = buf.order(ByteOrder.nativeOrder()).asIntBuffer();
            mcTexture     = tex;
            texIdentifier = id;
            texW = w;
            texH = h;
            needsFullUpload = true;
        } catch (Throwable t) {
            // Sans ceci, un echec apres l'allocation fuirait le tampon natif a chaque tentative.
            if (img != null) img.close(); else MemoryUtil.memFree(buf);
            LOG.error("[ul-view:{}] creation de texture echouee ({}x{})", viewId, w, h, t);
        }
    }

    /** Frames ou la texture a effectivement ete mise a jour (lu par la sonde). */
    int paintCount = 0;

    /** Refus de taille deja signale. */
    private boolean textureTooLargeWarned = false;

    /**
     * @param deferred reporter la libération au prochain tick (fermeture de la vue) : un dessin de
     *        la frame en cours peut encore référencer la texture. L'identifiant n'est plus jamais
     *        réutilisé ensuite, la libération différée par identifiant est donc sans ambiguïté.
     */
    private void destroyMcTexture(boolean deferred) {
        Minecraft mc = Minecraft.getInstance();
        // release() ferme la texture, donc la NativeImage, donc libere notre tampon natif. Sans
        // TextureManager (arret du jeu), on ferme nous-memes : sinon le tampon fuit.
        Identifier id = texIdentifier;
        if (id != null && mc != null) {
            if (deferred) UltralightEngine.deferRelease(() -> mc.getTextureManager().release(id));
            else          mc.getTextureManager().release(id);
        } else if (mcTexture != null) {
            mcTexture.close();
        }
        texIdentifier = null;
        mcTexture     = null;
        texW = texH   = 0;
        pixelInts     = null;
        pixelBuffer   = null;
    }

    // =========================================================================
    //  Pont JS↔Java — par FILE, vidée à la demande
    // =========================================================================
    // Avec Luminescence 1.4, le callback natif d'un JSFunction ne se déclenche PAS pour les
    // appels initiés par la page (seulement pour les appels Java via ctx.evaluate). On installe
    // donc une fonction JS pure qui EMPILE les messages dans window.__ulq, et on vide cette file
    // côté Java via evaluate (qui, lui, fonctionne).
    //
    // Vider à chaque frame coûtait un verrou de contexte JS et une évaluation par vue et par
    // frame, même sans le moindre message. La fonction signale donc le passage de la file de vide
    // à non vide par un message console marqueur, que le listener console (lui fiable) intercepte :
    // on ne vide que sur signal. Un vidage de secours tourne quand même toutes les
    // BRIDGE_FALLBACK_FRAMES frames : il réinstalle la fonction si la page l'a perdue et rattrape
    // un signal manqué, qu'on signale alors, car la latence du pont en souffrirait.

    private static final String ULQ = "__ulq";
    /** Message console émis par la page quand la file passe de vide à non vide. */
    private static final String BRIDGE_SIGNAL = "\u0001ulq";
    private static final int BRIDGE_FALLBACK_FRAMES = 30;

    /** La page a signalé des messages en attente (écrit par le listener console). */
    private volatile boolean bridgeSignaled = true;
    private int framesSinceDrain = 0;
    /** Vidages déclenchés par le signal, et messages rattrapés par le secours (lus par la sonde). */
    int bridgeSignalDrains = 0;
    int bridgeFallbackCatches = 0;
    private boolean bridgeFallbackWarned = false;

    /** La fonction du pont, en JS : empile, et signale le passage de vide à non vide. */
    private static String bridgeFunctionJs(String name) {
        return "window['" + name + "']=function(d){var q=(window." + ULQ + "=window." + ULQ
                + "||[]);q.push(String(d));if(q.length===1)console.log('\\u0001ulq');};";
    }

    private void installBridge() {
        try (JSContext ctx = view.acquireJSContextLock()) {
            String name = bridgeName;
            ctx.evaluate(
                bridgeFunctionJs(name) + "window." + ULQ + "=window." + ULQ + "||[];"
                // Capture des erreurs JS non-catchées pour diagnostic (lisible via probe Java).
                + "if(!window.__ulErrHook){window.__ulErrHook=1;window.addEventListener('error',function(e){"
                + "window.__ulErr=(window.__ulErr?window.__ulErr+' | ':'')+((e&&e.message)||e)+' @'+((e&&e.filename)||'')+':'+((e&&e.lineno)||0);});}");
            LOG.debug("[ul-view:{}] pont JS installé (file) : window.{}", viewId, name);
        } catch (Throwable t) {
            LOG.warn("[ul-view:{}] Bridge install failed: {}", viewId, t.getMessage());
        }
    }

    /**
     * Évalue du JS côté Java via {@code ctx.evaluate} (chemin fiable, indépendant du pont in-page
     * et du console capture) et renvoie le résultat en chaîne. Outil de diagnostic réutilisable.
     */
    public String evalString(String js) {
        UltralightEngine.checkRenderThread("evalString");
        try (JSContext ctx = view.acquireJSContextLock()) {
            JSValue r = ctx.evaluate(js);
            return r == null ? null : r.toString();
        } catch (Throwable t) {
            return "EVAL_ERR:" + t.getMessage();
        }
    }

    /**
     * Dépile window.__ulq côté Java et dispatch chaque message au handler. Appelé chaque frame,
     * mais ne touche au contexte JS que sur signal de la page, ou toutes les
     * {@link #BRIDGE_FALLBACK_FRAMES} frames par sécurité.
     */
    private void drainBridge() {
        Consumer<String> handler = queryHandler;
        if (handler == null) return;
        boolean signaled = bridgeSignaled;
        if (!signaled && ++framesSinceDrain < BRIDGE_FALLBACK_FRAMES) return;
        bridgeSignaled = false;
        framesSinceDrain = 0;
        String joined;
        try (JSContext ctx = view.acquireJSContextLock()) {
            // On (ré)installe le pont dans CE contexte (le monde JS vivant de la page) : à
            // onWindowObjectReady, Luminescence 1.4 fournit un contexte qui est ensuite remplacé
            // quand la page charge, si bien que window.<bridge> posée là n'atterrit pas dans le
            // monde de la page. Le drain, lui, tape le contexte vivant : on y garantit donc la
            // fonction pour que ses push aillent dans le même window.__ulq que celui qu'on draine.
            // Un chargement de page lève le signal, pour que cette installation suive aussitôt.
            String name = bridgeName;
            JSValue r = ctx.evaluate("(function(){"
                + "if(typeof window['" + name + "']!=='function')" + bridgeFunctionJs(name)
                + "var q=window." + ULQ + ";if(!q||!q.length)return '';window." + ULQ
                + "=[];return q.join('\\u0001');})()");
            joined = (r == null) ? null : r.toString();
        } catch (Throwable t) {
            return;
        }
        if (joined == null || joined.isEmpty()) return;
        if (signaled) {
            bridgeSignalDrains++;
        } else {
            bridgeFallbackCatches++;
            if (!bridgeFallbackWarned) {
                bridgeFallbackWarned = true;
                LOG.warn("[ul-view:{}] messages du pont JS rattrapés par le vidage de secours : le "
                        + "signal de la page n'arrive pas, la latence du pont monte à ~{} frames.",
                        viewId, BRIDGE_FALLBACK_FRAMES);
            }
        }
        for (String msg : joined.split("\u0001")) {
            if (msg.isEmpty()) continue;
            try { handler.accept(msg); }
            catch (Throwable t) { LOG.warn("[ul-view:{}] Bridge handler error: {}", viewId, t.getMessage()); }
        }
    }

    // =========================================================================
    //  Listener combiné (load + view) Luminescence
    // =========================================================================

    private final class BridgeListener implements ULViewListener {
        @Override
        public void onWindowObjectReady(long frameID, boolean isMainFrame, String url) {
            if (isMainFrame) installBridge();
        }

        @Override
        public void onDOMReady(long frameID, boolean isMainFrame, String url) {
            if (!isMainFrame) return;
            pageReady.set(true);
            bridgeSignaled = true;   // le contexte JS vivant est là : y installer le pont sans attendre
            Consumer<Void> cb = onPageReadyCallback;
            if (cb != null) Minecraft.getInstance().execute(() -> cb.accept(null));
        }

        @Override
        public void onLoadingFail(long frameID, boolean isMainFrame, String url,
                                  String description, String errorDomain, int errorCode) {
            LOG.warn("[ul-view:{}] Load failed ({}): {} — {}", viewId, errorCode, url, description);
        }

        @Override
        public void onCursorChange(ULCursor cursor) {
            CursorType type = cursorType(cursor);
            Consumer<CursorType> h = cursorHandler;
            if (h != null) h.accept(type);
            else           type.select();   // MC 26.3 : plus besoin de passer par GLFW
        }

        @Override
        public void onConsoleMessageAdded(ULMessageSource source, ULMessageLevel level,
                                          String message, int line, int column, String sourceID) {
            // Signal interne du pont, pas un message de la page : on ne le logge pas.
            if (BRIDGE_SIGNAL.equals(message)) { bridgeSignaled = true; return; }
            String tag = "[ul-console:" + viewId + "/" + source + "] " + message;
            switch (level) {
                case ERROR   -> LOG.error(tag);
                case WARNING -> LOG.warn(tag);
                default      -> LOG.info(tag);
            }
        }
    }

    /** Curseur Ultralight vers le jeu vanilla de MC 26.3 (SDL). */
    private static CursorType cursorType(ULCursor c) {
        return switch (c) {
            case HAND                 -> CursorTypes.POINTING_HAND;
            case IBEAM                -> CursorTypes.IBEAM;
            case CROSS                -> CursorTypes.CROSSHAIR;
            case EAST_WEST_RESIZE, EAST_RESIZE, WEST_RESIZE, COLUMN_RESIZE -> CursorTypes.RESIZE_EW;
            case NORTH_SOUTH_RESIZE, NORTH_RESIZE, SOUTH_RESIZE, ROW_RESIZE -> CursorTypes.RESIZE_NS;
            case MOVE                 -> CursorTypes.RESIZE_ALL;
            case NO_DROP, NOT_ALLOWED -> CursorTypes.NOT_ALLOWED;
            default                   -> CursorTypes.ARROW;   // POINTER inclus
        };
    }
}
