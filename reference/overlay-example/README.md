# Exemple de référence — overlay web interactif (NON compilé)

Ces fichiers **ne font pas partie du mod** (hors de `src/`). Ils servent d'exemple
fonctionnel et validé d'un overlay Ultralight réactif, à reprendre côté **mod consommateur**.

## Fichiers
- **`UltralightPanelScreen.java`** — la version **recommandée** : le même overlay écrit avec
  `UltralightPanel`. Le mod ne calcule plus ni taille de vue, ni `deviceScale`, ni rectangle de
  dessin, ni conversion des coordonnées souris ; il choisit juste une politique de mise en page.
- `UltralightDemoScreen.java` — la version **manuelle** (`UltralightBrowserView` directement) : un
  `Screen` MC (26.x : `extractRenderState`) qui crée une vue, route le vrai input
  (clic/scroll/clavier/curseur), et dessine la vue plein-écran, net, indépendamment du
  « GUI Scale ». Utile pour comprendre la mécanique, ou si tu veux piloter la géométrie toi-même.
- `UltralightDemo.java` — enregistre une touche (U) pour ouvrir l'écran (exemple d'amorçage).
- `assets/demo-ui.html` — page de test responsive.

## Points clés de la version manuelle (validés en jeu sur 1.21.11, portés sur 26.2)

> `UltralightPanel` fait tout ce qui suit à ta place. Ça reste utile à connaître pour déboguer.

1. **Taille indexée sur la fenêtre, pas le GUI Scale** : dimensionner la vue aux pixels
   **physiques** de la fenêtre (`Window.getWidth/getHeight` en MC 26.x ; `getGuiScaledWidth/Height`
   est l'espace logique, à ne pas utiliser ici).
2. **`deviceScale = framebufferHeight / REF_CSS_HEIGHT`** (p.ex. 600) → le CSS voit une
   hauteur logique constante ; la page se met en page de façon responsive.
3. **Coordonnées souris en pixels CSS** (= device ÷ deviceScale), PAS en pixels device :
   `viewX = screenX / this.width * (fbW / deviceScale)`. C'est LE piège.
4. **Dessin net** via la surcharge région de `blit(pipeline, id, x, y, u, v, drawW,
   drawH, regionW, regionH, texW, texH)` : `drawW/drawH` = taille logique écran,
   `regionW/regionH = texW/texH` = taille physique de la texture.
5. **Pomper le moteur** depuis `Screen.extractRenderState()` via `UltralightEngine.renderFrame()`
   (l'élément de HUD ne tourne pas quand un écran est ouvert).
6. **Curseurs** : `view.setCursorHandler(shape -> glfwSetCursor(window, standardCursor(shape)))`,
   remis à `0L` à la fermeture.

> Note : ces fichiers étaient dans le package `net.ostore.ultralight` ; pour les utiliser
> depuis un autre mod (autre package), tout passe par l'API publique de `UltralightBrowserView`
> et `UltralightEngine.renderFrame()`.
>
> Ils sont écrits pour **MC 26.2** et ont été vérifiés à la compilation (copiés temporairement
> dans `src/`, compilés, puis ressortis) — mais pas encore exécutés en jeu sur 26.2.
