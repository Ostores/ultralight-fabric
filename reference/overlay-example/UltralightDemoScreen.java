package net.ostore.ultralight;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * DÉMO INTERACTIVE DE RÉFÉRENCE (non compilée) — overlay web plein écran, net, indépendant du
 * réglage « GUI Scale » de Minecraft. Recette à reprendre côté mod consommateur.
 *
 * <ul>
 *   <li>Vue dimensionnée aux <b>pixels physiques</b> de la fenêtre ({@code Window.getWidth/getHeight}),
 *       donc la taille suit la fenêtre, pas le réglage du joueur.</li>
 *   <li>{@code deviceScale = hauteurPhysique / REF_CSS_HEIGHT} → le CSS voit toujours une hauteur
 *       logique constante ({@value #REF_CSS_HEIGHT} px) et s'adapte à la fenêtre.</li>
 *   <li>Dessin via la surcharge « région » de {@code blit} : texture physique échantillonnée en
 *       entier, dessinée sur la taille logique de l'écran → 1 texel = 1 px physique (net).</li>
 *   <li>Souris mappée logique → <b>pixels CSS</b> (device ÷ deviceScale), le piège classique.</li>
 * </ul>
 *
 * <p><b>MC 26.x :</b> les écrans ne dessinent plus dans {@code render(DrawContext…)} mais
 * remplissent un état de rendu dans {@code extractRenderState(GuiGraphicsExtractor…)}, soumis au
 * GPU ensuite. On y soumet le blit de la texture, et <b>rien d'autre</b> : le cycle du moteur,
 * lui, tourne avant la GUI (voir {@code UltralightEngine}).
 */
public final class UltralightDemoScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/demoscreen");

    /** Hauteur CSS logique cible : le contenu est conçu pour ~cette hauteur, quelle que soit la fenêtre. */
    private static final double REF_CSS_HEIGHT = 600.0;

    private UltralightBrowserView view;
    private int fbW, fbH;            // pixels physiques (taille de la vue/texture)
    private double deviceScale = 1.0;
    private final Map<Integer, Long> cursorCache = new HashMap<>();

    public UltralightDemoScreen() { super(Component.literal("Ultralight Demo")); }

    @Override
    protected void init() {
        super.init();
        Window w = minecraft.getWindow();
        fbW = w.getWidth();      // pixels physiques du framebuffer (pas l'espace GUI Scale)
        fbH = w.getHeight();
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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Ne PAS pomper le moteur ici : il tourne déjà par frame avant la GUI. Écrire dans une
        // texture GPU pendant la phase d'extraction corrompt le lot de dessins de Minecraft.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (view == null) return;
        Identifier id = view.getTextureIdentifier();
        if (id == null) return;
        // texture physique (fbW×fbH) échantillonnée en entier, dessinée sur tout l'écran logique
        graphics.blit(RenderPipelines.GUI_TEXTURED, id,
                0, 0, 0f, 0f, width, height, fbW, fbH, fbW, fbH);
    }

    // ── Input MC → API de la vue (logique MC → pixels CSS de la vue) ──

    // Ultralight attend des pixels CSS = device ÷ deviceScale, PAS des pixels device.
    private int vx(double screenX) { return (int) Math.round(screenX / width  * (fbW / deviceScale)); }
    private int vy(double screenY) { return (int) Math.round(screenY / height * (fbH / deviceScale)); }

    @Override
    public void mouseMoved(double mx, double my) {
        if (view != null) view.mouseMoved(vx(mx), vy(my));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (view != null) view.mousePressed(vx(click.x()), vy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (view != null) view.mouseReleased(vx(click.x()), vy(click.y()), click.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (view != null) view.scroll((int) (horizontal * 60), (int) (vertical * 60));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        if (key.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        if (view != null) view.keyPressed(key.key(), key.modifiers());
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent key) {
        if (view != null) view.keyReleased(key.key(), key.modifiers());
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        if (view != null) view.charTyped(input.codepointAsString());
        return true;
    }

    @Override
    public void removed() {
        if (view != null) { view.close(); view = null; }
        if (minecraft != null) GLFW.glfwSetCursor(minecraft.getWindow().handle(), 0L); // curseur par défaut
    }

    @Override public boolean isPauseScreen() { return false; }

    private void applyCursor(int glfwShape) {
        if (minecraft == null) return;
        long cur = cursorCache.computeIfAbsent(glfwShape, GLFW::glfwCreateStandardCursor);
        GLFW.glfwSetCursor(minecraft.getWindow().handle(), cur);
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
