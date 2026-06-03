# Exemple de référence — overlay web interactif (NON compilé)

Ces fichiers **ne font pas partie du mod** (hors de `src/`). Ils servent d'exemple
fonctionnel et validé d'un overlay Ultralight réactif, à reprendre côté **mod consommateur**.

## Fichiers
- `UltralightDemoScreen.java` — un `Screen` MC qui crée une vue, route le vrai input
  (clic/scroll/clavier/curseur) vers l'API publique de `UltralightBrowserView`, et dessine
  la vue plein-écran, net, indépendamment du « GUI Scale ».
- `UltralightDemo.java` — enregistre une touche (U) pour ouvrir l'écran (exemple d'amorçage).
- `assets/demo-ui.html` — page de test responsive.

## Points clés (validés en jeu)
1. **Taille indexée sur la fenêtre, pas le GUI Scale** : dimensionner la vue aux pixels
   **physiques** du framebuffer (`window.getFramebufferWidth/Height`).
2. **`deviceScale = framebufferHeight / REF_CSS_HEIGHT`** (p.ex. 600) → le CSS voit une
   hauteur logique constante ; la page se met en page de façon responsive.
3. **Coordonnées souris en pixels CSS** (= device ÷ deviceScale), PAS en pixels device :
   `viewX = screenX / scaledWidth * (framebufferWidth / deviceScale)`. C'est LE piège.
4. **Dessin net** via la surcharge région de `drawTexture(pipeline, id, x, y, u, v, drawW,
   drawH, regionW, regionH, texW, texH)` : `drawW/drawH` = taille logique écran,
   `regionW/regionH = texW/texH` = taille physique de la texture.
5. **Pomper le moteur** depuis `Screen.render()` via `UltralightEngine.renderFrame()`
   (le `HudRenderCallback` ne tourne pas quand un écran est ouvert).
6. **Curseurs** : `view.setCursorHandler(shape -> glfwSetCursor(window, standardCursor(shape)))`,
   remis à `0L` à la fermeture.

> Note : ces fichiers étaient dans le package `net.ostore.ultralight` ; pour les utiliser
> depuis un autre mod (autre package), tout passe par l'API publique de `UltralightBrowserView`
> et `UltralightEngine.renderFrame()`.
