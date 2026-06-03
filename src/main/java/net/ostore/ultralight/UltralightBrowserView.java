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
 * <p>Pont JS : {@code window.abysseQuery(data)} (fonction native via {@link JSFunction}).
 */
public final class UltralightBrowserView {

    private static final Logger LOG = LoggerFactory.getLogger("abysse/ul-view");

    /** recip[a] ≈ (255/a) << 16 — dé-prémultiplication sans division par pixel. */
    private static final int[] UNPREMULT_RECIP = new int[256];
    static {
        UNPREMULT_RECIP[0] = 0;
        for (int a = 1; a < 256; a++) UNPREMULT_RECIP[a] = (int) ((255L * 65536L) / a);
    }

    private final ULView view;
    private final int viewId;

    // MC texture adossée à un buffer natif que nous possédons.
    private NativeImageBackedTexture mcTexture;
    private Identifier texIdentifier;
    private ByteBuffer pixelBuffer;   // mémoire native libérée par NativeImage.close()
    private IntBuffer  pixelInts;
    private boolean textureReady   = false;
    private boolean needsFullUpload = true;

    // JS bridge + curseur
    private volatile Consumer<String> queryHandler;
    private volatile IntConsumer cursorHandler;

    // Page lifecycle
    private final AtomicBoolean pageReady = new AtomicBoolean(false);
    private Consumer<Void> onPageReadyCallback;

    public UltralightBrowserView(int width, int height, double deviceScale) {
        this.viewId = UltralightEngine.VIEW_COUNTER.getAndIncrement();
        this.view   = UltralightEngine.createView(width, height, true, deviceScale);
        this.view.setListener(new BridgeListener());
        UltralightEngine.registerView(this);
    }

    // =========================================================================
    //  API publique — render thread
    // =========================================================================

    public void loadHTML(String html) { pageReady.set(false); view.loadHTML(html); }
    public void loadURL(String url)   { pageReady.set(false); view.loadURL(url); }

    public void resize(int physicalWidth, int physicalHeight) {
        view.resize(physicalWidth, physicalHeight);
        destroyMcTexture();
        needsFullUpload = true;
        textureReady    = false;
    }

    public void setDeviceScale(double deviceScale) {
        try { view.setDeviceScale(deviceScale); } catch (Throwable ignored) {}
    }

    public void executeJavaScript(String script) {
        try { view.evaluateScript(script, new String[1]); }
        catch (Throwable e) { LOG.debug("[ul-view:{}] JS error: {}", viewId, e.getMessage()); }
    }

    public void setQueryHandler(Consumer<String> handler)       { this.queryHandler = handler; }
    public void updateQueryHandler(Consumer<String> handler)    { this.queryHandler = handler; }
    public void setOnPageReadyCallback(Consumer<Void> callback) { this.onPageReadyCallback = callback; }
    /** Le handler reçoit une forme de curseur GLFW (p.ex. {@code GLFW_HAND_CURSOR}). */
    public void setCursorHandler(IntConsumer handler)          { this.cursorHandler = handler; }

    public Identifier getTextureIdentifier() { return textureReady ? texIdentifier : null; }
    public boolean isTextureReady()          { return textureReady; }
    public boolean isPageReady()             { return pageReady.get(); }
    public void    setPageReady(boolean v)   { pageReady.set(v); }

    public ULView getView() { return view; }

    public void close() {
        UltralightEngine.unregisterView(this);
        destroyMcTexture();
        textureReady = false;
        pageReady.set(false);
        try { view.destroy(); } catch (Throwable ignored) {}
    }

    // =========================================================================
    //  Input — render thread. Coords en pixels CSS de la vue (= device ÷ deviceScale).
    //  Codes touches/boutons/modifiers : conventions GLFW (= celles de MC).
    // =========================================================================

    public void mouseMoved(int x, int y) {
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_MOVED, x, y, MouseButton.NONE)) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] mouse: {}", viewId, t.getMessage()); }
    }

    public void mousePressed(int x, int y, int glfwButton) {
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_DOWN, x, y, mapButton(glfwButton))) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] mouse: {}", viewId, t.getMessage()); }
    }

    public void mouseReleased(int x, int y, int glfwButton) {
        try (ULMouseEvent e = new ULMouseEvent(MouseEventType.MOUSE_UP, x, y, mapButton(glfwButton))) {
            view.fireMouseEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] mouse: {}", viewId, t.getMessage()); }
    }

    public void scroll(int deltaXpixels, int deltaYpixels) {
        try (ULScrollEvent e = new ULScrollEvent(ScrollEventType.SCROLL_BY_PIXEL, deltaXpixels, deltaYpixels)) {
            view.fireScrollEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] scroll: {}", viewId, t.getMessage()); }
    }

    public void charTyped(String text) {
        if (text == null || text.isEmpty()) return;
        try (ULKeyEvent e = new ULKeyEvent(KeyEventType.CHAR, 0, 0, 0, text, text, false, false, false)) {
            view.fireKeyEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] char: {}", viewId, t.getMessage()); }
    }

    public void keyPressed(int glfwKey, int glfwModifiers) {
        fireKey(KeyEventType.RAW_KEY_DOWN, glfwKey, glfwModifiers);
    }

    public void keyReleased(int glfwKey, int glfwModifiers) {
        fireKey(KeyEventType.KEY_UP, glfwKey, glfwModifiers);
    }

    public void focus()   { try { view.focus();   } catch (Throwable ignored) {} }
    public void unfocus() { try { view.unfocus(); } catch (Throwable ignored) {} }
    public boolean hasInputFocus() { try { return view.hasInputFocus(); } catch (Throwable t) { return false; } }

    private void fireKey(KeyEventType type, int glfwKey, int glfwModifiers) {
        try (ULKeyEvent e = new ULKeyEvent(type, mapModifiers(glfwModifiers),
                glfwKeyToWindowsVK(glfwKey), 0, "", "", false, false, false)) {
            view.fireKeyEvent(e);
        } catch (Throwable t) { LOG.debug("[ul-view:{}] key: {}", viewId, t.getMessage()); }
    }

    private static MouseButton mapButton(int glfwButton) {
        return switch (glfwButton) {
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT  -> MouseButton.RIGHT;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> MouseButton.MIDDLE;
            default                            -> MouseButton.LEFT;
        };
    }

    /** Modifiers Ultralight : ALT=1, CTRL=1<<1, META=1<<2, SHIFT=1<<3. */
    private static int mapModifiers(int glfwModifiers) {
        int m = 0;
        if ((glfwModifiers & GLFW.GLFW_MOD_ALT)     != 0) m |= 1;
        if ((glfwModifiers & GLFW.GLFW_MOD_CONTROL) != 0) m |= 1 << 1;
        if ((glfwModifiers & GLFW.GLFW_MOD_SUPER)   != 0) m |= 1 << 2;
        if ((glfwModifiers & GLFW.GLFW_MOD_SHIFT)   != 0) m |= 1 << 3;
        return m;
    }

    /** GLFW → virtual key code Windows (A-Z/0-9 coïncident ; touches d'édition remappées). */
    private static int glfwKeyToWindowsVK(int glfwKey) {
        return switch (glfwKey) {
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_TAB       -> 0x09;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_ESCAPE    -> 0x1B;
            case GLFW.GLFW_KEY_SPACE     -> 0x20;
            case GLFW.GLFW_KEY_PAGE_UP   -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_END       -> 0x23;
            case GLFW.GLFW_KEY_HOME      -> 0x24;
            case GLFW.GLFW_KEY_LEFT      -> 0x25;
            case GLFW.GLFW_KEY_UP        -> 0x26;
            case GLFW.GLFW_KEY_RIGHT     -> 0x27;
            case GLFW.GLFW_KEY_DOWN      -> 0x28;
            case GLFW.GLFW_KEY_INSERT    -> 0x2D;
            case GLFW.GLFW_KEY_DELETE    -> 0x2E;
            default                      -> glfwKey; // A-Z, 0-9 alignés sur les VK
        };
    }

    // =========================================================================
    //  Tick (render thread, chaque frame)
    // =========================================================================

    void onRendererTick() { paintSurface(); }

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

        boolean full = needsFullUpload;
        int dx = 0, dy = 0, dw = w, dh = h;
        if (!full) {
            if (dirty == null || dirty.right <= dirty.left || dirty.bottom <= dirty.top) return; // rien de sale
            dx = Math.max(0, dirty.left);
            dy = Math.max(0, dirty.top);
            dw = Math.min(w, dirty.right) - dx;
            dh = Math.min(h, dirty.bottom) - dy;
            if (dw <= 0 || dh <= 0) return;
        }

        ensureMcTexture(w, h);
        if (pixelInts == null) return;

        ULBitmapSurface bitmap = ULBitmapSurface.fromSurface(surface);
        try (ULSurface.LockedPixels locked = bitmap.acquirePixelLock()) {
            ByteBuffer pixels = locked.pixels();
            IntBuffer src = pixels.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer(); // BGRA prémult → int LE 0xAARRGGBB
            int srcStride = surface.getRowBytes() / 4;
            blit(src, srcStride, w, dx, dy, dw, dh);
            needsFullUpload = false;
            mcTexture.upload();
            textureReady = true;
        } catch (Throwable t) {
            LOG.debug("[ul-view:{}] paint: {}", viewId, t.getMessage());
        } finally {
            surface.clearDirtyBounds();
        }
    }

    /** Région surface (BGRA prémultiplié) → buffer texture (RGBA straight-alpha), stride respecté. */
    private void blit(IntBuffer src, int srcStride, int dstStride, int x0, int y0, int rw, int rh) {
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
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false,
                MemoryUtil.memAddress(pixelBuffer));
        mcTexture     = new NativeImageBackedTexture(() -> "ul_view_" + viewId, img);
        texIdentifier = Identifier.of("ultralight", "ul_view_" + viewId);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) mc.getTextureManager().registerTexture(texIdentifier, mcTexture);
        needsFullUpload = true;
    }

    private void destroyMcTexture() {
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
    //  Pont JS↔Java — fonction native
    // =========================================================================

    private void installBridge() {
        try (JSContext ctx = view.acquireJSContextLock()) {
            ctx.globalObject().setProperty("abysseQuery",
                    JSFunction.create(ctx, "abysseQuery", (c, self, args) -> {
                        dispatchQuery(args);
                        return c.makeUndefined();
                    }));
        } catch (Throwable t) {
            LOG.warn("[ul-view:{}] Bridge install failed: {}", viewId, t.getMessage());
        }
    }

    private void dispatchQuery(JSValue[] args) {
        if (args == null || args.length == 0) return;
        JSValue v = args[0];
        if (v == null || v.isUndefined() || v.isNull()) return;
        Consumer<String> handler = queryHandler;
        if (handler == null) return;
        try { handler.accept(v.toString()); }
        catch (Throwable t) { LOG.warn("[ul-view:{}] Bridge handler error: {}", viewId, t.getMessage()); }
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
            Consumer<Void> cb = onPageReadyCallback;
            if (cb != null) MinecraftClient.getInstance().execute(() -> cb.accept(null));
        }

        @Override
        public void onLoadingFail(long frameID, boolean isMainFrame, String url,
                                  String description, String errorDomain, int errorCode) {
            LOG.warn("[ul-view:{}] Load failed ({}): {} — {}", viewId, errorCode, url, description);
        }

        @Override
        public void onCursorChange(ULCursor cursor) {
            IntConsumer h = cursorHandler;
            if (h != null) h.accept(glfwCursorShape(cursor));
        }

        @Override
        public void onConsoleMessageAdded(ULMessageSource source, ULMessageLevel level,
                                          String message, int line, int column, String sourceID) {
            String tag = "[ul-console:" + viewId + "/" + source + "] " + message;
            switch (level) {
                case ERROR   -> LOG.error(tag);
                case WARNING -> LOG.warn(tag);
                default      -> LOG.info(tag);
            }
        }
    }

    private static int glfwCursorShape(ULCursor c) {
        return switch (c) {
            case HAND                 -> GLFW.GLFW_HAND_CURSOR;
            case IBEAM                -> GLFW.GLFW_IBEAM_CURSOR;
            case CROSS                -> GLFW.GLFW_CROSSHAIR_CURSOR;
            case EAST_WEST_RESIZE, EAST_RESIZE, WEST_RESIZE, COLUMN_RESIZE -> GLFW.GLFW_HRESIZE_CURSOR;
            case NORTH_SOUTH_RESIZE, NORTH_RESIZE, SOUTH_RESIZE, ROW_RESIZE -> GLFW.GLFW_VRESIZE_CURSOR;
            default                   -> GLFW.GLFW_ARROW_CURSOR; // POINTER inclus
        };
    }
}
