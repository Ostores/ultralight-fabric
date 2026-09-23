package net.ostore.ultralight;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;


/**
 * Panneau web : une {@link UltralightBrowserView} dont le wrapper possède la <b>géométrie</b>.
 *
 * <p>C'est la réponse au problème « mon interface casse selon le ratio d'écran ». Le panneau
 * décide, à partir d'une <b>taille de design</b> en pixels CSS et d'une {@link Fit politique de
 * mise en page}, quelle taille physique donner à la vue et quel {@code deviceScale} appliquer,
 * puis il suit la fenêtre tout seul. Il fait aussi le <b>mapping des coordonnées souris</b>
 * (logique Minecraft → pixels CSS), qui est le piège que chaque mod consommateur réimplémentait.
 *
 * <p>Utilisation type dans un {@code Screen} :
 * <pre>{@code
 * panel = UltralightPanel.builder()
 *         .design(1280, 720)          // la page est pensée pour ce viewport CSS
 *         .fit(Fit.FILL_CLAMPED)      // défaut : responsive, mais borné
 *         .build();
 * panel.loadHTML(html);
 *
 * // extractRenderState(...)
 * panel.render(graphics);
 *
 * // mouseClicked(...)
 * panel.mouseClicked(click.x(), click.y(), click.button());
 * }</pre>
 *
 * <p>Côté page, le panneau publie à chaque changement de géométrie :
 * {@code --ul-vw}, {@code --ul-vh}, {@code --ul-aspect} sur {@code :root}, l'attribut
 * {@code data-ul-ratio} ({@code ultrawide} / {@code wide} / {@code standard} / {@code tall}) sur
 * {@code <html>}, et un événement {@code ul:resize}.
 *
 * <p>Tout doit s'exécuter sur le render thread, comme le reste de l'API.
 */
public final class UltralightPanel implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/panel");

    /**
     * Politique de mise en page : comment la taille de design se traduit dans la fenêtre réelle.
     *
     * <p>Le choix se résume à « qui absorbe la variation de ratio » : la page (FILL), le wrapper
     * par des marges (CONTAIN), ou les deux dans une plage bornée (FILL_CLAMPED).
     */
    public enum Fit {
        /**
         * La hauteur CSS reste celle du design, la <b>largeur CSS suit la fenêtre</b> sans limite.
         * En 5:4 la page reçoit ~750 px de large, en 21:9 ~1420 : c'est le mode le plus souple et
         * celui qui casse les mises en page non réellement responsive. À ne choisir que si le CSS
         * a été écrit et testé pour toute la plage.
         */
        FILL,
        /**
         * La page voit <b>toujours exactement</b> la taille de design. Le panneau prend le plus
         * grand rectangle de ce ratio disponible et centre le reste (marges transparentes). Les
         * bugs de ratio deviennent impossibles ; le rendu reste net puisque la vue est rendue à la
         * résolution physique, pas agrandie comme une texture.
         */
        CONTAIN,
        /**
         * Défaut. Comme {@link #FILL} mais la largeur CSS est bornée : au-delà du maximum on
         * centre et on laisse des marges, en dessous du minimum on réduit l'échelle (l'interface
         * devient plus petite, avec un peu de hauteur CSS en plus, plutôt que de casser).
         */
        FILL_CLAMPED
    }

    // ── configuration (immuable) ──
    private final Fit fit;
    private final int designW, designH;
    private final int minCssW, maxCssW;
    private final long maxViewPixels;
    private final float boundsX, boundsY, boundsW, boundsH;
    private final String bridgeName;   // null = défaut de la bibliothèque
    private boolean animated;          // repeindre à chaque frame (page animée)

    // ── état géométrique courant ──
    private UltralightBrowserView view;
    private int drawX, drawY, drawW, drawH;      // rectangle de dessin, en px logiques MC
    private int viewPW, viewPH;                  // taille de la vue, en px physiques
    private double deviceScale = 1.0;
    private int cssW, cssH;
    private int areaW, areaH;                    // zone allouée au panneau, en px logiques
    private double previewAspect = 0.0;          // > 0 : simulation de ratio (mode test)

    // ── resize temporisé (un drag de fenêtre ne doit pas réallouer par frame) ──
    // Horloge monotone et pas horloge murale : on mesure une duree, et un ajustement NTP
    // retarderait ou declencherait un redimensionnement au hasard.
    private static final long RESIZE_SETTLE_NANOS = 120L * 1_000_000L;
    private int pendingPW, pendingPH;
    private double pendingScale;
    private long pendingSince;

    // ── handlers différés (posés avant que la vue existe) ──
    private Consumer<String> queryHandler;
    private Consumer<CursorType> cursorHandler;
    private Consumer<Void> pageReadyCallback;
    private String pendingHtml, pendingUrl;

    private boolean warnedUnboundedFill;

    private UltralightPanel(Builder b) {
        this.fit           = b.fit;
        this.designW       = b.designW;
        this.designH       = b.designH;
        this.minCssW       = b.minCssW  > 0 ? b.minCssW  : Math.round(b.designW * 0.72f);
        this.maxCssW       = b.maxCssW  > 0 ? b.maxCssW  : Math.round(b.designW * 1.25f);
        this.maxViewPixels = b.maxViewPixels;
        this.boundsX       = b.boundsX;
        this.boundsY       = b.boundsY;
        this.boundsW       = b.boundsW;
        this.boundsH       = b.boundsH;
        this.bridgeName    = b.bridgeName;
        this.animated      = b.animated;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Fit fit = Fit.FILL_CLAMPED;
        private int designW = 1280, designH = 720;
        private int minCssW = 0, maxCssW = 0;
        private long maxViewPixels = 3840L * 2160L;
        private float boundsX = 0f, boundsY = 0f, boundsW = 1f, boundsH = 1f;
        private String bridgeName = null;
        private boolean animated = false;

        /** Viewport CSS pour lequel la page est écrite. Défaut 1280×720. */
        public Builder design(int cssWidth, int cssHeight) {
            if (cssWidth <= 0 || cssHeight <= 0) throw new IllegalArgumentException("design <= 0");
            this.designW = cssWidth; this.designH = cssHeight; return this;
        }

        /** Politique de mise en page. Défaut {@link Fit#FILL_CLAMPED}. */
        public Builder fit(Fit fit) {
            if (fit != null) this.fit = fit; return this;
        }

        /**
         * Bornes de largeur CSS pour {@link Fit#FILL_CLAMPED}. Défaut : 0,72× à 1,25× la largeur
         * de design. Sans effet dans les autres modes.
         */
        public Builder cssWidthRange(int minCssWidth, int maxCssWidth) {
            if (minCssWidth > 0 && maxCssWidth > 0 && minCssWidth > maxCssWidth)
                throw new IllegalArgumentException("cssWidthRange: min > max");
            this.minCssW = minCssWidth; this.maxCssW = maxCssWidth; return this;
        }

        /**
         * Plafond de résolution de la vue, en pixels. Au-delà, la vue est rendue plus petite (et
         * donc très légèrement moins nette) : en 4K une surface CPU coûte 33 Mo et un blit complet
         * par frame. Défaut : 3840×2160. {@code 0} = pas de plafond.
         */
        public Builder maxViewPixels(long pixels) { this.maxViewPixels = Math.max(0, pixels); return this; }

        /**
         * Sous-rectangle de l'écran occupé par le panneau, en fractions (0..1). Défaut : plein
         * écran. Utile pour un panneau/dialogue qui ne prend qu'une partie de la fenêtre.
         */
        public Builder bounds(float x, float y, float width, float height) {
            // design() et cssWidthRange() valident deja : des fractions aberrantes ici donnaient un
            // rectangle absurde sans un mot.
            float eps = 1e-4f;
            if (!(x >= -eps && y >= -eps && width > 0f && height > 0f
                    && x + width <= 1f + eps && y + height <= 1f + eps)) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "bounds hors de [0,1] : x=%.3f y=%.3f w=%.3f h=%.3f", x, y, width, height));
            }
            this.boundsX = x; this.boundsY = y; this.boundsW = width; this.boundsH = height; return this;
        }

        /**
         * Nom de la fonction JS du pont pour ce panneau ({@code window.<nom>(data)}). Par défaut,
         * celui de la bibliothèque. À définir ici plutôt que globalement : deux mods qui règlent le
         * nom global se cassent mutuellement le pont.
         */
        public Builder bridgeName(String name) { this.bridgeName = name; return this; }

        /**
         * Page animée (CSS, {@code requestAnimationFrame}, vidéo) : repeinte à chaque frame. Sans
         * ce réglage, une animation sans interaction se fige à l'écran. Inutile, et coûteux, pour
         * une page statique.
         */
        public Builder animated(boolean animated) { this.animated = animated; return this; }

        public UltralightPanel build() { return new UltralightPanel(this); }
    }

    // =========================================================================
    //  Contenu et callbacks
    // =========================================================================

    public void loadHTML(String html) {
        this.pendingHtml = html; this.pendingUrl = null;
        if (view != null) { view.loadHTML(html); this.pendingHtml = null; }
    }

    public void loadURL(String url) {
        this.pendingUrl = url; this.pendingHtml = null;
        if (view != null) { view.loadURL(url); this.pendingUrl = null; }
    }

    public void setQueryHandler(Consumer<String> handler) {
        this.queryHandler = handler;
        if (view != null) view.setQueryHandler(handler);
    }

    /**
     * Remplace le comportement par defaut, qui est d'appliquer directement le curseur demande par
     * la page. Depuis MC 26.3 le jeu expose ses propres curseurs, il n'y a donc plus rien a cabler
     * cote mod : ne poser un handler que pour ignorer ou filtrer ces demandes.
     */
    public void setCursorHandler(Consumer<CursorType> handler) {
        this.cursorHandler = handler;
        if (view != null) view.setCursorHandler(handler);
    }

    public void setOnPageReadyCallback(Consumer<Void> callback) {
        this.pageReadyCallback = callback;
        if (view != null) view.setOnPageReadyCallback(wrapPageReady(callback));
    }

    public void executeJavaScript(String script) { if (view != null) view.executeJavaScript(script); }

    /** La vue sous-jacente, pour tout ce que le panneau n'expose pas. Peut être {@code null}. */
    public UltralightBrowserView view() { return view; }

    /** Viewport CSS courant, en pixels logiques vus par la page. */
    public int cssWidth()  { return cssW; }
    public int cssHeight() { return cssH; }
    /** Rectangle de dessin dans l'espace logique de l'écran (coordonnées de {@code Screen}). */
    public int drawX() { return drawX; }
    public int drawY() { return drawY; }
    public int drawWidth()  { return drawW; }
    public int drawHeight() { return drawH; }

    /**
     * Mode test des ratios : force le panneau à occuper le plus grand rectangle du ratio donné
     * (p.ex. {@code 21.0/9.0}) à l'intérieur de la fenêtre, sans toucher à la vraie fenêtre. C'est
     * le seul moyen pratique de vérifier une interface en 21:9 ou en 4:3 sans changer de
     * résolution. {@code 0} rend la main au comportement normal.
     */
    public void setPreviewAspect(double aspect) {
        this.previewAspect = aspect > 0 ? aspect : 0.0;
    }

    // =========================================================================
    //  Rendu
    // =========================================================================

    /**
     * Met la géométrie à jour et dessine le panneau. À appeler depuis
     * {@code Screen.extractRenderState(...)} ou depuis un {@code HudElement} : c'est le même appel.
     *
     * <p>Ne pompe <b>pas</b> le moteur : celui-ci tourne par frame avant la GUI. Pomper ici
     * écrirait dans une texture GPU au milieu de l'extraction, ce qui corrompt le lot de dessins
     * de Minecraft (voir {@link UltralightEngine}).
     */
    public void render(GuiGraphicsExtractor graphics) {
        syncGeometry();
        blit(graphics);
    }

    private void blit(GuiGraphicsExtractor graphics) {
        if (view == null) return;
        Identifier id = view.getTextureIdentifier();
        if (id == null) return;
        int tw = view.getTextureWidth(), th = view.getTextureHeight();
        if (tw <= 0 || th <= 0 || drawW <= 0 || drawH <= 0) return;
        // Texture physique échantillonnée en entier, dessinée à la taille logique du rectangle :
        // 1 texel = 1 pixel physique, donc net quel que soit le GUI Scale.
        graphics.blit(RenderPipelines.GUI_TEXTURED, id,
                drawX, drawY, 0f, 0f, drawW, drawH, tw, th, tw, th);
    }

    // =========================================================================
    //  Géométrie
    // =========================================================================

    private void syncGeometry() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        Window win = mc.getWindow();
        int fbW  = win.getWidth(),          fbH  = win.getHeight();
        int guiW = win.getGuiScaledWidth(), guiH = win.getGuiScaledHeight();
        if (fbW <= 0 || fbH <= 0 || guiW <= 0 || guiH <= 0) return;   // fenêtre minimisée

        // Zone du panneau, en pixels physiques.
        double areaPX = boundsX * fbW, areaPY = boundsY * fbH;
        double areaPW = boundsW * fbW, areaPH = boundsH * fbH;
        if (previewAspect > 0) {   // mode test : on simule une fenêtre d'un autre ratio
            double avail = areaPW / areaPH;
            double w = avail > previewAspect ? areaPH * previewAspect : areaPW;
            double h = avail > previewAspect ? areaPH : areaPW / previewAspect;
            areaPX += (areaPW - w) / 2.0;
            areaPY += (areaPH - h) / 2.0;
            areaPW = w; areaPH = h;
        }
        if (areaPW < 1 || areaPH < 1) return;

        double scale, vpw, vph;
        switch (fit) {
            case CONTAIN -> {
                double target = (double) designW / designH;
                boolean wider = (areaPW / areaPH) > target;
                vph   = wider ? areaPH : areaPW / target;
                vpw   = wider ? areaPH * target : areaPW;
                scale = vph / designH;
            }
            case FILL_CLAMPED -> {
                scale = areaPH / designH;
                double css = areaPW / scale;
                if (css > maxCssW) {              // trop large : on borne et on centre
                    vpw = maxCssW * scale; vph = areaPH;
                } else if (css < minCssW) {       // trop étroit : on réduit l'échelle
                    scale = areaPW / minCssW; vpw = areaPW; vph = areaPH;
                } else {
                    vpw = areaPW; vph = areaPH;
                }
            }
            default -> {                           // FILL
                scale = areaPH / designH;
                vpw = areaPW; vph = areaPH;
            }
        }

        // Plafond de résolution : on baisse vue ET deviceScale du même facteur, le viewport CSS
        // reste identique — seule la finesse du rendu diminue.
        if (maxViewPixels > 0) {
            double px = vpw * vph;
            if (px > maxViewPixels) {
                double f = Math.sqrt(maxViewPixels / px);
                vpw *= f; vph *= f; scale *= f;
            }
        }

        int newPW = Math.max(1, (int) Math.round(vpw));
        int newPH = Math.max(1, (int) Math.round(vph));

        // Rectangle de dessin : la vue est centrée dans sa zone, converti en px logiques.
        double pxPerGuiX = (double) fbW / guiW, pxPerGuiY = (double) fbH / guiH;
        drawX = (int) Math.round((areaPX + (areaPW - newPW) / 2.0) / pxPerGuiX);
        drawY = (int) Math.round((areaPY + (areaPH - newPH) / 2.0) / pxPerGuiY);
        drawW = Math.max(1, (int) Math.round(newPW / pxPerGuiX));
        drawH = Math.max(1, (int) Math.round(newPH / pxPerGuiY));
        areaW = Math.max(1, (int) Math.round(areaPW / pxPerGuiX));
        areaH = Math.max(1, (int) Math.round(areaPH / pxPerGuiY));

        applySize(newPW, newPH, scale);
    }

    /** Applique la taille calculée, en la laissant se stabiliser (drag de fenêtre). */
    private void applySize(int newPW, int newPH, double newScale) {
        if (view == null) {
            createView(newPW, newPH, newScale);
            return;
        }
        boolean same = newPW == viewPW && newPH == viewPH
                && Math.abs(newScale - deviceScale) < 1e-4;
        if (same) { pendingSince = 0L; return; }

        long now = System.nanoTime();
        if (pendingSince == 0L || newPW != pendingPW || newPH != pendingPH
                || Math.abs(newScale - pendingScale) > 1e-4) {
            pendingPW = newPW; pendingPH = newPH; pendingScale = newScale; pendingSince = now;
            return;                                   // on attend que ça se stabilise
        }
        if (now - pendingSince < RESIZE_SETTLE_NANOS) return;

        pendingSince = 0L;
        viewPW = newPW; viewPH = newPH; deviceScale = newScale;
        view.resize(newPW, newPH);
        view.setDeviceScale(newScale);
        onGeometryChanged();
    }

    private void createView(int pw, int ph, double scale) {
        if (!UltralightEngine.isReady()) return;
        viewPW = pw; viewPH = ph; deviceScale = scale;
        view = new UltralightBrowserView(pw, ph, scale);
        if (bridgeName    != null) view.setBridgeName(bridgeName);
        view.setAnimated(animated);
        if (queryHandler  != null) view.setQueryHandler(queryHandler);
        if (cursorHandler != null) view.setCursorHandler(cursorHandler);
        view.setOnPageReadyCallback(wrapPageReady(pageReadyCallback));
        if (pendingHtml != null) { view.loadHTML(pendingHtml); pendingHtml = null; }
        else if (pendingUrl != null) { view.loadURL(pendingUrl); pendingUrl = null; }
        onGeometryChanged();
    }

    /** Republie les métriques dans la page ET (re)dit au dev où il en est. */
    private void onGeometryChanged() {
        cssW = (int) Math.round(viewPW / deviceScale);
        cssH = (int) Math.round(viewPH / deviceScale);
        pushCssMetrics();
        logGeometry();
    }

    private Consumer<Void> wrapPageReady(Consumer<Void> user) {
        return v -> {
            pushCssMetrics();                 // la page vient de (re)charger : ses variables sont perdues
            if (user != null) user.accept(v);
        };
    }

    private void pushCssMetrics() {
        if (view == null || cssW <= 0 || cssH <= 0) return;
        double aspect = (double) cssW / cssH;
        String bucket = aspect >= 2.1 ? "ultrawide"
                      : aspect >= 1.55 ? "wide"
                      : aspect >= 1.2  ? "standard"
                      : "tall";
        String js = "(function(){var d=document.documentElement;if(!d)return;"
                + "d.style.setProperty('--ul-vw','" + cssW + "px');"
                + "d.style.setProperty('--ul-vh','" + cssH + "px');"
                + "d.style.setProperty('--ul-aspect','" + String.format(java.util.Locale.ROOT, "%.4f", aspect) + "');"
                + "d.setAttribute('data-ul-ratio','" + bucket + "');"
                + "try{window.dispatchEvent(new CustomEvent('ul:resize',{detail:{width:" + cssW
                + ",height:" + cssH + ",aspect:" + String.format(java.util.Locale.ROOT, "%.4f", aspect)
                + ",ratio:'" + bucket + "'}}));}catch(e){}"
                + "})()";
        view.executeJavaScript(js);
    }

    /**
     * Une ligne INFO à chaque changement de géométrie : le moddeur doit pouvoir lire, sans
     * instrumenter quoi que ce soit, quelle politique s'applique et quel viewport sa page reçoit.
     */
    private void logGeometry() {
        int marginX = Math.max(0, (areaW - drawW) / 2);
        int marginY = Math.max(0, (areaH - drawH) / 2);
        StringBuilder note = new StringBuilder();
        if (marginX > 0 || marginY > 0) {
            note.append(" · marges ").append(marginX).append('/').append(marginY).append(" px");
            if (fit == Fit.CONTAIN)           note.append(" (ratio de design conservé)");
            else if (fit == Fit.FILL_CLAMPED) note.append(" (largeur CSS bornée à ").append(maxCssW).append(')');
        } else if (fit == Fit.FILL_CLAMPED && cssW <= minCssW) {
            note.append(" · échelle réduite (largeur CSS minimale ").append(minCssW).append(" atteinte)");
        }
        LOG.info("[ul-panel] fit={} · vue {}×{} px · viewport CSS {}×{} · deviceScale {} (design {}×{}){}",
                fit, viewPW, viewPH, cssW, cssH,
                String.format(java.util.Locale.ROOT, "%.2f", deviceScale), designW, designH, note);

        if (fit == Fit.FILL && !warnedUnboundedFill) {
            double drift = (double) cssW / designW;
            if (drift < 0.8 || drift > 1.3) {
                warnedUnboundedFill = true;
                LOG.warn("[ul-panel] fit=FILL : la page reçoit {} px de large pour un design de {} px "
                        + "({} %). Une mise en page non réellement responsive va casser à ce ratio ; "
                        + "Fit.FILL_CLAMPED (borné) ou Fit.CONTAIN (taille de design garantie) "
                        + "évitent le problème.",
                        cssW, designW, Math.round(drift * 100));
            }
        }
    }

    // =========================================================================
    //  Input — coordonnées logiques MC (celles de Screen), converties en pixels CSS
    // =========================================================================

    /** Le point (logique MC) est-il dans le panneau ? */
    public boolean contains(double mouseX, double mouseY) {
        return mouseX >= drawX && mouseX < drawX + drawW
            && mouseY >= drawY && mouseY < drawY + drawH;
    }

    private int vx(double mouseX) { return (int) Math.round((mouseX - drawX) / drawW * cssW); }
    private int vy(double mouseY) { return (int) Math.round((mouseY - drawY) / drawH * cssH); }

    /** @return {@code true} si l'événement a été transmis à la page (curseur dans le panneau). */
    public boolean mouseMoved(double mouseX, double mouseY) {
        if (view == null || !contains(mouseX, mouseY)) return false;
        view.mouseMoved(vx(mouseX), vy(mouseY));
        return true;
    }

    /**
     * @param mcButton le bouton tel que Minecraft le fournit ({@code MouseButtonEvent.button()}),
     *        à comparer uniquement à {@code InputConstants.MOUSE_BUTTON_*} : depuis MC 26.3 ce sont
     *        des codes SDL (gauche/milieu/droite = 1/2/3), plus les 0/1/2 de GLFW.
     */
    public boolean mouseClicked(double mouseX, double mouseY, int mcButton) {
        if (view == null || !contains(mouseX, mouseY)) return false;
        view.mousePressed(vx(mouseX), vy(mouseY), mcButton);
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int mcButton) {
        if (view == null || !contains(mouseX, mouseY)) return false;
        view.mouseReleased(vx(mouseX), vy(mouseY), mcButton);
        return true;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (view == null || !contains(mouseX, mouseY)) return false;
        view.scroll((int) Math.round(horizontal * 60), (int) Math.round(vertical * 60));
        return true;
    }

    /**
     * @param mcKey la touche telle que Minecraft la fournit ({@code KeyEvent.key()}), à transmettre
     *        telle quelle : depuis MC 26.3 ce sont des scancodes SDL (A = 4, Entrée = 40), sans
     *        rapport avec les codes GLFW. Comparer uniquement à {@code InputConstants.KEY_*}.
     */
    public boolean keyPressed(int mcKey, int mcModifiers) {
        if (view == null) return false;
        view.keyPressed(mcKey, mcModifiers);
        return true;
    }

    public boolean keyReleased(int mcKey, int mcModifiers) {
        if (view == null) return false;
        view.keyReleased(mcKey, mcModifiers);
        return true;
    }

    /**
     * Forme recommandée depuis {@code Screen.keyPressed(KeyEvent)} : elle tient compte de la
     * disposition du clavier (en AZERTY, la forme à codes entiers envoie Ctrl+Q pour Ctrl+A).
     */
    public boolean keyPressed(KeyEvent event) {
        if (view == null) return false;
        view.keyPressed(event);
        return true;
    }

    public boolean keyReleased(KeyEvent event) {
        if (view == null) return false;
        view.keyReleased(event);
        return true;
    }

    public boolean charTyped(String text) {
        if (view == null) return false;
        view.charTyped(text);
        return true;
    }

    public void focus()   { if (view != null) view.focus(); }
    public void unfocus() { if (view != null) view.unfocus(); }
    public boolean hasInputFocus() { return view != null && view.hasInputFocus(); }

    /** Force la re-rastérisation pendant quelques frames. Pour une page animée, préférer
     *  {@link #setAnimated(boolean)} plutôt que de l'appeler à chaque frame. */
    public void requestRepaint() { if (view != null) view.requestRepaint(); }

    /** Voir {@link Builder#animated(boolean)}. Modifiable à chaud (animation qui démarre, s'arrête). */
    public void setAnimated(boolean animated) {
        this.animated = animated;
        if (view != null) view.setAnimated(animated);
    }

    public boolean isAnimated() { return animated; }

    @Override
    public void close() {
        if (view != null) { view.close(); view = null; }
    }
}
