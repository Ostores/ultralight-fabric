package net.ostore.ultralight;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.util.Window;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * DÉMO INTERACTIVE TEMPORAIRE (Phase 2/3) — ouverte par la touche U (cf. {@link UltralightDemo}).
 * À supprimer après vérification (cette classe, {@link UltralightDemo},
 * {@code assets/ultralight/demo-ui.html}, et l'appel dans {@code UltralightApiClient}).
 *
 * <p><b>Overlay plein-fenêtre, net, indépendant du « GUI Scale » de MC.</b> Recette de
 * référence pour un overlay web réactif :
 * <ul>
 *   <li>Vue dimensionnée aux <b>pixels physiques</b> du framebuffer (pas aux px logiques
 *       « GUI Scale »), donc la taille suit la fenêtre, pas le réglage du joueur.</li>
 *   <li>{@code deviceScale = framebufferHeight / REF_CSS_HEIGHT} → le CSS voit toujours une
 *       hauteur logique constante ({@value #REF_CSS_HEIGHT} px) et s'adapte à la fenêtre.</li>
 *   <li>Dessin avec la surcharge région de {@code drawTexture} : texture physique
 *       échantillonnée en entier, dessinée sur la taille logique de l'écran → 1 texel = 1 px
 *       physique (net).</li>
 *   <li>Souris mappée logique→physique exactement ({@code mouseX * fbW / scaledW}).</li>
 * </ul>
 */
public final class UltralightDemoScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("abysse/ul-demoscreen");

    /** Hauteur CSS logique cible : le contenu est conçu pour ~cette hauteur, quelle que soit la fenêtre. */
    private static final double REF_CSS_HEIGHT = 600.0;

    private UltralightBrowserView view;
    private int fbW, fbH;            // pixels physiques (taille de la vue/texture)
    private double deviceScale = 1.0;
    private final Map<Integer, Long> cursorCache = new HashMap<>();

    public UltralightDemoScreen() { super(Text.literal("Ultralight Demo")); }

    @Override
    protected void init() {
        super.init();
        Window w = client.getWindow();
        fbW = w.getFramebufferWidth();
        fbH = w.getFramebufferHeight();
        deviceScale = Math.max(1.0, fbH / REF_CSS_HEIGHT);

        if (view == null) {
            view = new UltralightBrowserView(fbW, fbH, deviceScale);
            view.setQueryHandler(msg -> LOG.info("[ul-demoscreen] pont JS→Java : {}", msg));
            view.setCursorHandler(this::applyCursor);
            String html = read("/assets/ultralight/demo-ui.html");
            if (html != null) view.loadHTML(html);
            view.focus();
        } else {
            // Redimensionnement fenêtre / changement de GUI scale : on suit le framebuffer.
            view.resize(fbW, fbH);
            view.setDeviceScale(deviceScale);
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        UltralightEngine.renderFrame(); // pompe update/render/paint pendant que l'écran est ouvert
        super.render(ctx, mouseX, mouseY, delta); // fond (flou appliqué une seule fois)
        if (view == null) return;
        Identifier id = view.getTextureIdentifier();
        if (id == null) return;
        // texture physique (fbW×fbH) échantillonnée en entier, dessinée sur tout l'écran logique
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id,
                0, 0, 0f, 0f, width, height, fbW, fbH, fbW, fbH);
    }

    // ── Input réel MC → API Phase 2 (logique MC → pixels physiques de la vue) ──

    // MC logique → pixels CSS de la vue (= device px / deviceScale, ce qu'attend Ultralight).
    private int vx(double screenX) { return (int) Math.round(screenX / width  * (fbW / deviceScale)); }
    private int vy(double screenY) { return (int) Math.round(screenY / height * (fbH / deviceScale)); }

    @Override
    public void mouseMoved(double mx, double my) {
        if (view != null) view.mouseMoved(vx(mx), vy(my));
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (view != null) view.mousePressed(vx(click.x()), vy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (view != null) view.mouseReleased(vx(click.x()), vy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (view != null) view.scroll((int) (horizontal * 60), (int) (vertical * 60));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput key) {
        if (key.getKeycode() == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (view != null) view.keyPressed(key.getKeycode(), key.modifiers());
        return true;
    }

    @Override
    public boolean keyReleased(KeyInput key) {
        if (view != null) view.keyReleased(key.getKeycode(), key.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (view != null) view.charTyped(input.asString());
        return true;
    }

    @Override
    public void removed() {
        if (view != null) { view.close(); view = null; }
        if (client != null) GLFW.glfwSetCursor(client.getWindow().getHandle(), 0L); // curseur par défaut
    }

    @Override public boolean shouldPause() { return false; }

    private void applyCursor(int glfwShape) {
        if (client == null) return;
        long cur = cursorCache.computeIfAbsent(glfwShape, s -> GLFW.glfwCreateStandardCursor(s));
        GLFW.glfwSetCursor(client.getWindow().getHandle(), cur);
    }

    private static String read(String resource) {
        try (InputStream in = UltralightDemoScreen.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[ul-demoscreen] lecture {} échouée", resource, e);
            return null;
        }
    }
}
