package net.ostore.ultralight;

import com.labymedia.ultralight.UltralightSurface;
import com.labymedia.ultralight.UltralightView;
import com.labymedia.ultralight.input.UltralightCursor;
import com.labymedia.ultralight.input.UltralightInputModifier;
import com.labymedia.ultralight.input.UltralightKey;
import com.labymedia.ultralight.input.UltralightKeyEvent;
import com.labymedia.ultralight.input.UltralightKeyEventType;
import com.labymedia.ultralight.input.UltralightMouseEvent;
import com.labymedia.ultralight.input.UltralightMouseEventButton;
import com.labymedia.ultralight.input.UltralightMouseEventType;
import com.labymedia.ultralight.input.UltralightScrollEvent;
import com.labymedia.ultralight.input.UltralightScrollEventType;
import com.labymedia.ultralight.javascript.JavascriptClass;
import com.labymedia.ultralight.javascript.JavascriptClassDefinition;
import com.labymedia.ultralight.javascript.JavascriptContext;
import com.labymedia.ultralight.javascript.JavascriptContextLock;
import com.labymedia.ultralight.javascript.JavascriptEvaluationException;
import com.labymedia.ultralight.javascript.JavascriptObject;
import com.labymedia.ultralight.javascript.JavascriptPropertyAttributes;
import com.labymedia.ultralight.javascript.JavascriptValue;
import com.labymedia.ultralight.math.IntRect;
import com.labymedia.ultralight.plugin.loading.UltralightLoadListener;
import com.labymedia.ultralight.plugin.view.MessageLevel;
import com.labymedia.ultralight.plugin.view.MessageSource;
import com.labymedia.ultralight.plugin.view.UltralightViewListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Wrapper autour d'une {@link UltralightView} — pipeline CPU mode.
 *
 * <p>Tout s'exécute sur le <b>render thread</b> (contrainte ultralight-java 0.4.12).
 *
 * <p>Pipeline pixel ({@link #paintSurface()}) :
 * <ul>
 *   <li>La texture MC est adossée à un buffer natif que <b>nous</b> allouons
 *       ({@link MemoryUtil#memAlloc}) et fournissons à {@link NativeImage} via son
 *       constructeur à pointeur — on écrit donc directement en mémoire native sans
 *       les ~1M d'appels {@code setColorArgb} par frame de l'ancienne implémentation,
 *       et sans réflexion (remap-safe).</li>
 *   <li>Conversion BGRA prémultiplié → RGBA <b>straight-alpha</b> : les surfaces
 *       Ultralight sont en alpha prémultiplié ; le blending GUI par défaut de MC
 *       attend de l'alpha straight. Sans dé-prémultiplication, les zones
 *       semi-transparentes apparaissent assombries (halos). Table de réciproques
 *       pour éviter une division par pixel.</li>
 *   <li>{@code rowBytes()} de la surface est respecté (le stride peut différer de
 *       {@code width*4}).</li>
 *   <li>Mise à jour limitée au {@code dirtyBounds()} hors premier upload.</li>
 * </ul>
 *
 * <p>Pont JS↔Java ({@link #installBridge}) : {@code window.ulQuery(data)} est une
 * vraie fonction native (JavaScriptCore) — plus de polling ni de throttle. Le contrat
 * public est inchangé : côté JS {@code window.ulQuery(...)}, côté Java
 * {@code Consumer<String>}.
 *
 * <p>{@link #close()} charge {@code about:blank} pour tuer les timers JS natifs.
 */
public final class UltralightBrowserView {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/view");

    /** recip[a] ≈ (255/a) << 16 — dé-prémultiplication sans division par pixel. */
    private static final int[] UNPREMULT_RECIP = new int[256];
    static {
        UNPREMULT_RECIP[0] = 0;
        for (int a = 1; a < 256; a++) {
            UNPREMULT_RECIP[a] = (int) ((255L * 65536L) / a);
        }
    }

    /** Code touche GLFW (celui de MC) → virtual key code Ultralight (VK Windows). */
    private static final Map<Integer, UltralightKey> KEY_MAP = new HashMap<>();
    static {
        for (int k = GLFW.GLFW_KEY_A; k <= GLFW.GLFW_KEY_Z; k++)   // lettres
            KEY_MAP.put(k, UltralightKey.valueOf(String.valueOf((char) k)));
        for (int d = 0; d <= 9; d++)                               // chiffres
            KEY_MAP.put(GLFW.GLFW_KEY_0 + d, UltralightKey.valueOf("NUM_" + d));
        KEY_MAP.put(GLFW.GLFW_KEY_BACKSPACE,     UltralightKey.BACK);
        KEY_MAP.put(GLFW.GLFW_KEY_TAB,           UltralightKey.TAB);
        KEY_MAP.put(GLFW.GLFW_KEY_ENTER,         UltralightKey.RETURN);
        KEY_MAP.put(GLFW.GLFW_KEY_KP_ENTER,      UltralightKey.RETURN);
        KEY_MAP.put(GLFW.GLFW_KEY_ESCAPE,        UltralightKey.ESCAPE);
        KEY_MAP.put(GLFW.GLFW_KEY_SPACE,         UltralightKey.SPACE);
        KEY_MAP.put(GLFW.GLFW_KEY_DELETE,        UltralightKey.DELETE);
        KEY_MAP.put(GLFW.GLFW_KEY_INSERT,        UltralightKey.INSERT);
        KEY_MAP.put(GLFW.GLFW_KEY_LEFT,          UltralightKey.LEFT);
        KEY_MAP.put(GLFW.GLFW_KEY_RIGHT,         UltralightKey.RIGHT);
        KEY_MAP.put(GLFW.GLFW_KEY_UP,            UltralightKey.UP);
        KEY_MAP.put(GLFW.GLFW_KEY_DOWN,          UltralightKey.DOWN);
        KEY_MAP.put(GLFW.GLFW_KEY_HOME,          UltralightKey.HOME);
        KEY_MAP.put(GLFW.GLFW_KEY_END,           UltralightKey.END);
        KEY_MAP.put(GLFW.GLFW_KEY_PAGE_UP,       UltralightKey.PRIOR);
        KEY_MAP.put(GLFW.GLFW_KEY_PAGE_DOWN,     UltralightKey.NEXT);
        KEY_MAP.put(GLFW.GLFW_KEY_LEFT_SHIFT,    UltralightKey.SHIFT);
        KEY_MAP.put(GLFW.GLFW_KEY_RIGHT_SHIFT,   UltralightKey.SHIFT);
        KEY_MAP.put(GLFW.GLFW_KEY_LEFT_CONTROL,  UltralightKey.CONTROL);
        KEY_MAP.put(GLFW.GLFW_KEY_RIGHT_CONTROL, UltralightKey.CONTROL);
        KEY_MAP.put(GLFW.GLFW_KEY_LEFT_ALT,      UltralightKey.MENU);
        KEY_MAP.put(GLFW.GLFW_KEY_RIGHT_ALT,     UltralightKey.MENU);
    }

    private final UltralightView view;
    private final int viewId;

    // MC texture adossée à un buffer natif que nous possédons.
    private NativeImageBackedTexture mcTexture;
    private Identifier texIdentifier;
    private ByteBuffer pixelBuffer;   // mémoire native libérée par NativeImage.close()
    private IntBuffer  pixelInts;     // vue int (ordre natif) sur pixelBuffer
    private boolean textureReady   = false;
    private boolean needsFullUpload = true;

    // JS bridge
    private volatile Consumer<String> queryHandler;
    /** Classe JSC du callback — gardée pour éviter le GC natif du callback Java. */
    private JavascriptClass bridgeClass;

    /** Reçoit une forme de curseur GLFW (GLFW_*_CURSOR) quand la page change de curseur. */
    private volatile IntConsumer cursorHandler;

    // Page lifecycle
    private final AtomicBoolean pageReady = new AtomicBoolean(false);
    private Consumer<Void> onPageReadyCallback;

    public UltralightBrowserView(int width, int height, double deviceScale) {
        this.viewId = UltralightEngine.VIEW_COUNTER.getAndIncrement();
        this.view   = UltralightEngine.createView(width, height, true, deviceScale);
        this.view.setLoadListener(new LoadAdapter());
        this.view.setViewListener(new BridgeViewListener());
        UltralightEngine.registerView(this);
    }

    // =========================================================================
    //  API publique — render thread
    // =========================================================================

    public void loadHTML(String html)  { pageReady.set(false); view.loadHTML(html); }
    public void loadURL(String url)    { pageReady.set(false); view.loadURL(url);   }

    public void resize(int physicalWidth, int physicalHeight) {
        view.resize((long) physicalWidth, (long) physicalHeight);
        destroyMcTexture();
        needsFullUpload = true;
        textureReady    = false;
    }

    /** Ajuste le device scale (DPI/zoom CSS) à chaud — utile au redimensionnement fenêtre. */
    public void setDeviceScale(double deviceScale) {
        try { view.setDeviceScale(deviceScale); } catch (Throwable ignored) {}
    }

    public void executeJavaScript(String script) {
        try { view.evaluateScript(script); }
        catch (JavascriptEvaluationException e) {
            LOG.debug("[ul-view:{}] JS error: {}", viewId, e.getMessage());
        }
    }

    public void setQueryHandler(Consumer<String> handler)        { this.queryHandler = handler; }
    public void updateQueryHandler(Consumer<String> handler)     { this.queryHandler = handler; }
    public void setOnPageReadyCallback(Consumer<Void> callback)  { this.onPageReadyCallback = callback; }
    /** Le handler reçoit une forme de curseur GLFW (p.ex. {@code GLFW_HAND_CURSOR}). */
    public void setCursorHandler(IntConsumer handler)           { this.cursorHandler = handler; }

    public Identifier getTextureIdentifier() { return textureReady ? texIdentifier : null; }
    public boolean isTextureReady()          { return textureReady; }
    public boolean isPageReady()             { return pageReady.get(); }
    public void    setPageReady(boolean v)   { pageReady.set(v); }

    public UltralightView getView() { return view; }

    /**
     * Fermeture : désenregistre la vue du moteur, détruit la texture MC,
     * et navigue vers about:blank pour tuer les timers JS encore actifs en natif.
     */
    public void close() {
        UltralightEngine.unregisterView(this);
        destroyMcTexture();
        textureReady = false;
        pageReady.set(false);
        try { view.loadURL("about:blank"); } catch (Throwable ignored) {}
    }

    // =========================================================================
    //  Input — render thread. Coordonnées attendues : pixels locaux à la vue
    //  (le placement à l'écran est géré par l'appelant, p.ex. le mod consommateur).
    //  Codes touches/boutons/modifiers : conventions GLFW (= celles de MC).
    // =========================================================================

    public void mouseMoved(int x, int y) {
        fireMouse(UltralightMouseEventType.MOVED, x, y, null);
    }

    public void mousePressed(int x, int y, int glfwButton) {
        fireMouse(UltralightMouseEventType.DOWN, x, y, mapButton(glfwButton));
    }

    public void mouseReleased(int x, int y, int glfwButton) {
        fireMouse(UltralightMouseEventType.UP, x, y, mapButton(glfwButton));
    }

    /** Défilement en pixels (l'appelant convertit les « lignes » MC en pixels et choisit le signe). */
    public void scroll(int deltaXpixels, int deltaYpixels) {
        try {
            view.fireScrollEvent(new UltralightScrollEvent()
                    .type(UltralightScrollEventType.BY_PIXEL)
                    .deltaX(deltaXpixels)
                    .deltaY(deltaYpixels));
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] scroll error: {}", viewId, t.getMessage());
        }
    }

    /** Saisie de texte : un événement CHAR (à appeler depuis le callback char de GLFW/MC). */
    public void charTyped(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            view.fireKeyEvent(new UltralightKeyEvent()
                    .type(UltralightKeyEventType.CHAR)
                    .text(text)
                    .unmodifiedText(text));
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] charTyped error: {}", viewId, t.getMessage());
        }
    }

    /** Touche enfoncée (touches d'édition/navigation/raccourcis). Le texte passe par {@link #charTyped}. */
    public void keyPressed(int glfwKey, int glfwModifiers) {
        fireKey(UltralightKeyEventType.RAW_DOWN, glfwKey, glfwModifiers);
    }

    public void keyReleased(int glfwKey, int glfwModifiers) {
        fireKey(UltralightKeyEventType.UP, glfwKey, glfwModifiers);
    }

    public void focus()   { try { view.focus();   } catch (Throwable ignored) {} }
    public void unfocus() { try { view.unfocus(); } catch (Throwable ignored) {} }
    /** {@code true} si un élément éditable a le focus (utile pour capturer le clavier). */
    public boolean hasInputFocus() { try { return view.hasInputFocus(); } catch (Throwable t) { return false; } }

    private void fireMouse(UltralightMouseEventType type, int x, int y, UltralightMouseEventButton button) {
        try {
            UltralightMouseEvent e = new UltralightMouseEvent().type(type).x(x).y(y);
            if (button != null) e.button(button);
            view.fireMouseEvent(e);
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] mouse error: {}", viewId, t.getMessage());
        }
    }

    private void fireKey(UltralightKeyEventType type, int glfwKey, int glfwModifiers) {
        UltralightKey vk = KEY_MAP.get(glfwKey);
        if (vk == null) return; // touches produisant du texte : voir charTyped()
        try {
            view.fireKeyEvent(new UltralightKeyEvent()
                    .type(type)
                    .virtualKeyCode(vk)
                    .nativeKeyCode(0)
                    .modifiers(mapModifiers(glfwModifiers)));
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] key error: {}", viewId, t.getMessage());
        }
    }

    private static UltralightMouseEventButton mapButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT  -> UltralightMouseEventButton.RIGHT;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> UltralightMouseEventButton.MIDDLE;
            default                            -> UltralightMouseEventButton.LEFT;
        };
    }

    private static int glfwCursorShape(UltralightCursor c) {
        return switch (c) {
            case HAND                 -> GLFW.GLFW_HAND_CURSOR;
            case I_BEAM               -> GLFW.GLFW_IBEAM_CURSOR;
            case CROSS                -> GLFW.GLFW_CROSSHAIR_CURSOR;
            case EAST_WEST_RESIZE, EAST_RESIZE, WEST_RESIZE, COLUMN_RESIZE
                                      -> GLFW.GLFW_HRESIZE_CURSOR;
            case NORTH_SOUTH_RESIZE, NORTH_RESIZE, SOUTH_RESIZE, ROW_RESIZE
                                      -> GLFW.GLFW_VRESIZE_CURSOR;
            default                   -> GLFW.GLFW_ARROW_CURSOR; // POINTER inclus
        };
    }

    private static int mapModifiers(int glfwModifiers) {
        int m = 0;
        if ((glfwModifiers & GLFW.GLFW_MOD_SHIFT)   != 0) m |= UltralightInputModifier.SHIFT_KEY;
        if ((glfwModifiers & GLFW.GLFW_MOD_CONTROL) != 0) m |= UltralightInputModifier.CTRL_KEY;
        if ((glfwModifiers & GLFW.GLFW_MOD_ALT)     != 0) m |= UltralightInputModifier.ALT_KEY;
        if ((glfwModifiers & GLFW.GLFW_MOD_SUPER)   != 0) m |= UltralightInputModifier.META_KEY;
        return m;
    }

    // =========================================================================
    //  Appelé par UltralightEngine.tick() — render thread, chaque frame
    // =========================================================================

    void onRendererTick() {
        paintSurface();
    }

    // =========================================================================
    //  Pipeline surface → NativeImageBackedTexture (mémoire native directe)
    // =========================================================================

    private void paintSurface() {
        UltralightSurface surface = view.surface();
        if (surface == null) return;

        IntRect dirty = surface.dirtyBounds();
        if (!dirty.isValid()) return;

        int w = (int) surface.width();
        int h = (int) surface.height();
        if (w <= 0 || h <= 0) return;

        ensureMcTexture(w, h);
        if (pixelInts == null) return;

        ByteBuffer pixels = surface.lockPixels();
        try {
            // Ultralight : BGRA prémultiplié, little-endian → int LE = 0xAARRGGBB.
            IntBuffer src = pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            int srcStride = (int) (surface.rowBytes() / 4); // stride réel (≠ w possible)

            if (needsFullUpload) {
                blit(src, srcStride, w, 0, 0, w, h);
                needsFullUpload = false;
            } else {
                int dx = dirty.x(), dy = dirty.y(), dw = dirty.width(), dh = dirty.height();
                // borne dans la surface par sécurité
                if (dx < 0) dx = 0; if (dy < 0) dy = 0;
                if (dx + dw > w) dw = w - dx;
                if (dy + dh > h) dh = h - dy;
                if (dw > 0 && dh > 0) blit(src, srcStride, w, dx, dy, dw, dh);
            }
            mcTexture.upload();
            textureReady = true;
        } finally {
            surface.unlockPixels();
            surface.clearDirtyBounds();
        }
    }

    /**
     * Copie une région de la surface (BGRA prémultiplié) vers le buffer natif de la
     * texture (RGBA straight-alpha), en respectant le stride source.
     */
    private void blit(IntBuffer src, int srcStride, int dstStride,
                      int x0, int y0, int rw, int rh) {
        for (int y = y0; y < y0 + rh; y++) {
            int srcRow = y * srcStride;
            int dstRow = y * dstStride;
            for (int x = x0; x < x0 + rw; x++) {
                int v = src.get(srcRow + x);          // 0xAARRGGBB prémultiplié
                int a = (v >>> 24) & 0xFF;
                int r = (v >>> 16) & 0xFF;
                int g = (v >>> 8)  & 0xFF;
                int b =  v         & 0xFF;
                if (a != 0 && a != 255) {              // dé-prémultiplication
                    int rcp = UNPREMULT_RECIP[a];
                    r = Math.min(255, (r * rcp + 32768) >> 16);
                    g = Math.min(255, (g * rcp + 32768) >> 16);
                    b = Math.min(255, (b * rcp + 32768) >> 16);
                }
                // NativeImage RGBA : octets R,G,B,A → int natif (LE) = 0xAABBGGRR.
                pixelInts.put(dstRow + x, (a << 24) | (b << 16) | (g << 8) | r);
            }
        }
    }

    private void ensureMcTexture(int w, int h) {
        if (mcTexture != null) return;
        long bytes = (long) w * h * 4L;
        pixelBuffer = MemoryUtil.memAlloc((int) bytes);
        pixelInts   = pixelBuffer.order(ByteOrder.nativeOrder()).asIntBuffer();
        // NativeImage prend la propriété du pointeur : son close() fera nmemFree().
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false,
                MemoryUtil.memAddress(pixelBuffer));
        mcTexture     = new NativeImageBackedTexture(() -> "ul_view_" + viewId, img);
        texIdentifier = Identifier.of("ultralight", "ul_view_" + viewId);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.getTextureManager().registerTexture(texIdentifier, mcTexture);
        needsFullUpload = true;
    }

    private void destroyMcTexture() {
        // Le pointeur natif est libéré par NativeImage.close() (déclenché par
        // destroyTexture/close de la texture). Ne pas appeler memFree nous-mêmes.
        if (texIdentifier != null) {
            Identifier id = texIdentifier;
            texIdentifier = null;
            mcTexture     = null;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) mc.getTextureManager().destroyTexture(id);
        } else if (mcTexture != null) {
            mcTexture.close();
            mcTexture = null;
        }
        pixelInts   = null;
        pixelBuffer = null;
    }

    // =========================================================================
    //  Pont JS↔Java — fonction native (plus de polling)
    // =========================================================================

    /**
     * Installe {@code window.ulQuery} comme fonction native, à chaque création
     * d'objet window (= chaque navigation, le contexte JS est recréé).
     */
    private void installBridge() {
        try (JavascriptContextLock lock = view.lockJavascriptContext()) {
            JavascriptContext ctx = lock.getContext();
            if (bridgeClass == null) {
                bridgeClass = new JavascriptClassDefinition()
                        .name("AbysseBridge")
                        .onCallAsFunction((context, function, thisObject, args) -> {
                            dispatchQuery(args);
                            return context.makeUndefined();
                        })
                        .bake();
            }
            JavascriptObject fn = ctx.makeObject(bridgeClass);
            ctx.getGlobalObject().setProperty("ulQuery", fn,
                    JavascriptPropertyAttributes.READ_ONLY | JavascriptPropertyAttributes.DONT_DELETE);
        } catch (Throwable t) {
            LOG.warn("[ul-view:{}] Bridge install failed: {}", viewId, t.getMessage());
        }
    }

    /** Appelé sur le render thread depuis le callback JS natif. */
    private void dispatchQuery(JavascriptValue[] args) {
        if (args == null || args.length == 0) return;
        JavascriptValue v = args[0];
        if (v == null || v.isUndefined() || v.isNull()) return;
        Consumer<String> handler = queryHandler;
        if (handler == null) return;
        try {
            handler.accept(v.isString() ? v.toStringCopy() : v.toString());
        } catch (Throwable t) {
            LOG.warn("[ul-view:{}] Bridge handler error: {}", viewId, t.getMessage());
        }
    }

    // =========================================================================
    //  Load listener
    // =========================================================================

    private final class LoadAdapter implements UltralightLoadListener {
        @Override
        public void onWindowObjectReady(long frameId, boolean isMainFrame, String url) {
            if (!isMainFrame) return;
            installBridge();
        }

        @Override
        public void onDOMReady(long frameId, boolean isMainFrame, String url) {
            if (!isMainFrame) return;
            pageReady.set(true);
            Consumer<Void> cb = onPageReadyCallback;
            if (cb != null) MinecraftClient.getInstance().execute(() -> cb.accept(null));
        }

        @Override public void onBeginLoading(long f, boolean m, String u)  { if (m) pageReady.set(false); }
        @Override public void onFinishLoading(long f, boolean m, String u) {}
        @Override public void onFailLoading(long f, boolean m, String u, String desc, String domain, int code) {
            LOG.warn("[ul-view:{}] Load failed ({}): {}", viewId, code, u);
        }
        @Override public void onUpdateHistory() {}
    }

    // =========================================================================
    //  View listener — remonte les messages console JS vers le log MC.
    //  Permet de diagnostiquer le rendu (erreurs CSS/JS, warnings) sans
    //  inspecteur graphique. Non-cassant : aucune logique de rendu ne dépend
    //  de ces callbacks.
    // =========================================================================

    private final class BridgeViewListener implements UltralightViewListener {
        @Override
        public void onAddConsoleMessage(MessageSource source, MessageLevel level,
                                        String message, long line, long column, String sourceId) {
            String tag = "[ul-console:" + viewId + "/" + source + "] " + message;
            switch (level) {
                case ERROR   -> LOG.error(tag);
                case WARNING -> LOG.warn(tag);
                default      -> LOG.info(tag);
            }
        }

        @Override public void onChangeTitle(String title)     {}
        @Override public void onChangeURL(String url)         {}
        @Override public void onChangeTooltip(String tooltip) {}
        @Override public void onChangeCursor(UltralightCursor cursor) {
            IntConsumer h = cursorHandler;
            if (h != null) h.accept(glfwCursorShape(cursor));
        }
        @Override public UltralightView onCreateChildView(String openerUrl, String targetUrl,
                                                          boolean isPopup, IntRect popupRect) {
            return null; // pas de popups / child views
        }
    }
}
