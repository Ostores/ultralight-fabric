package net.ostore.ultralight;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * EXEMPLE RECOMMANDÉ (non compilé) — le même overlay que {@link UltralightDemoScreen}, mais écrit
 * avec {@link UltralightPanel}.
 *
 * <p>Comparé à la version manuelle, tout ce qui suit disparaît du code du mod : le calcul de la
 * taille de vue et du {@code deviceScale}, le suivi du framebuffer au redimensionnement, le
 * rectangle de dessin, les arguments de région du {@code blit}, et surtout la conversion
 * « coordonnées logiques Minecraft → pixels CSS » qui est le piège classique.
 *
 * <p>Le seul choix à faire est la {@link UltralightPanel.Fit politique de mise en page} :
 * {@code FILL_CLAMPED} (défaut) convient à presque tout ; {@code CONTAIN} si l'interface a un
 * ratio fixe et ne doit jamais bouger ; {@code FILL} seulement si le CSS est réellement responsive.
 */
public final class UltralightPanelScreen extends Screen {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/panelscreen");

    private UltralightPanel panel;
    private final Map<Integer, Long> cursorCache = new HashMap<>();

    public UltralightPanelScreen() { super(Component.literal("Ultralight Panel")); }

    @Override
    protected void init() {
        super.init();
        if (panel != null) return;              // init() est rappelé à chaque resize : le panneau suit tout seul
        panel = UltralightPanel.builder()
                .design(1280, 720)              // le viewport CSS pour lequel la page est écrite
                .fit(UltralightPanel.Fit.FILL_CLAMPED)
                .build();
        panel.setQueryHandler(msg -> LOG.info("[ul-panelscreen] pont JS→Java : {}", msg));
        panel.setCursorHandler(this::applyCursor);
        String html = read("/assets/ultralight/demo-ui.html");
        if (html != null) panel.loadHTML(html);
        panel.focus();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (panel != null) panel.render(graphics);   // géométrie + dessin (le moteur tourne avant la GUI)
    }

    // ── Input : coordonnées logiques Minecraft, le panneau fait la conversion ──

    @Override
    public void mouseMoved(double mx, double my) {
        if (panel != null) panel.mouseMoved(mx, my);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        return panel != null && panel.mouseClicked(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        return panel != null && panel.mouseReleased(click.x(), click.y(), click.button());
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        return panel != null && panel.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent key) {
        if (key.key() == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        return panel != null && panel.keyPressed(key.key(), key.modifiers());
    }

    @Override
    public boolean keyReleased(KeyEvent key) {
        return panel != null && panel.keyReleased(key.key(), key.modifiers());
    }

    @Override
    public boolean charTyped(CharacterEvent input) {
        return panel != null && panel.charTyped(input.codepointAsString());
    }

    @Override
    public void removed() {
        if (panel != null) { panel.close(); panel = null; }
        if (minecraft != null) GLFW.glfwSetCursor(minecraft.getWindow().handle(), 0L);
    }

    @Override public boolean isPauseScreen() { return false; }

    private void applyCursor(int glfwShape) {
        if (minecraft == null) return;
        long cur = cursorCache.computeIfAbsent(glfwShape, GLFW::glfwCreateStandardCursor);
        GLFW.glfwSetCursor(minecraft.getWindow().handle(), cur);
    }

    private static String read(String resource) {
        try (InputStream in = UltralightPanelScreen.class.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[ul-panelscreen] lecture {} échouée", resource, e);
            return null;
        }
    }
}
