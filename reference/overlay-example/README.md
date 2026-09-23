# Exemple de référence : overlay web interactif (NON compilé avec le mod)

Ces fichiers **ne font pas partie du mod** (ils sont hors de `src/`). Ce sont des exemples à
reprendre côté **mod consommateur**, écrits pour **Minecraft 26.3**.

## Fichiers

- **`UltralightPanelScreen.java`** : la version **recommandée**, écrite avec `UltralightPanel`.
  Le mod ne calcule ni taille de vue, ni `deviceScale`, ni rectangle de dessin, ni conversion des
  coordonnées souris : il choisit seulement une politique de mise en page (`Fit`).
- `UltralightDemoScreen.java` : la version **manuelle**, avec `UltralightBrowserView` directement.
  Utile pour comprendre la mécanique, ou pour piloter la géométrie soi-même.
- `UltralightDemo.java` : enregistre une touche (U) qui ouvre l'écran.
- `assets/demo-ui.html` : page de test responsive.

## Les trois règles à ne pas enfreindre

1. **Ne jamais pomper le moteur soi-même.** Le moteur tourne tout seul à chaque frame, avant la
   GUI. Dans `extractRenderState` ou un `HudElement`, on ne fait que **dessiner**
   (`panel.render(graphics)`). Appeler `UltralightEngine.renderFrame()` depuis là écrit dans une
   texture GPU pendant l'extraction de la GUI : la page apparaît en damier derrière toute la scène,
   ou la vue s'affiche en noir. Depuis la 3.0.0, la bibliothèque refuse cet appel et le signale.
2. **Ne jamais coder en dur des codes de touche ou de bouton.** Depuis MC 26.3 l'entrée passe par
   SDL : les boutons gauche/milieu/droite valent 1/2/3 (0/1/2 en GLFW) et les touches sont des
   scancodes SDL (A vaut 4, Entrée 40, Échap 41). Les signatures n'ont pas changé, donc du code
   écrit pour GLFW compile et se comporte mal sans aucun message. Toujours passer par
   `com.mojang.blaze3d.platform.InputConstants` (`MOUSE_BUTTON_LEFT`, `KEY_ESCAPE`…) et
   transmettre tel quel ce que reçoit le `Screen`. Pour le clavier, passer le `KeyEvent` complet
   (`panel.keyPressed(key)`) : c'est la seule forme qui respecte la disposition AZERTY.
3. **Fermer ce qu'on ouvre.** `panel.close()` (ou `view.close()`) dans `Screen.removed()`. Chaque
   vue coûte environ 16 Mo en 1080p (surface native et texture). Au-delà de 8 vues ouvertes en même
   temps, la bibliothèque écrit un avertissement.

Page animée (CSS, `requestAnimationFrame`) : `.animated(true)` sur le panneau, sinon elle se fige.

## Ce que fait la version manuelle (et que `UltralightPanel` fait pour toi)

1. **Taille indexée sur la fenêtre, pas sur le GUI Scale** : la vue est dimensionnée aux pixels
   **physiques** (`Window.getWidth/getHeight`). `getGuiScaledWidth/Height` est l'espace logique,
   ce n'est pas ce qu'il faut ici.
2. **`deviceScale = hauteurPhysique / REF_CSS_HEIGHT`** (par exemple 600) : le CSS voit une hauteur
   logique constante et la page se met en page de façon responsive.
3. **Coordonnées souris en pixels CSS** (pixels device ÷ `deviceScale`), pas en pixels device :
   `viewX = screenX / this.width * (fbW / deviceScale)`. C'est LE piège de la version manuelle.
4. **Dessin net** via la surcharge « région » de `blit(pipeline, id, x, y, u, v, drawW, drawH,
   regionW, regionH, texW, texH)` : `drawW/drawH` = taille logique à l'écran,
   `regionW/regionH = texW/texH` = taille physique de la texture. Pipeline
   `RenderPipelines.GUI_TEXTURED` (alpha straight), pas la variante prémultipliée.
5. **Curseurs** : rien à câbler. La vue applique elle-même le curseur demandé par la page via les
   curseurs vanilla (`CursorTypes`).

## Réutiliser ces fichiers

Ils sont écrits dans le package `net.ostore.ultralight` pour pouvoir être compilés à côté de la
bibliothèque. Depuis un autre mod, change le package : tout ce qu'ils utilisent fait partie de
l'API publique (`UltralightPanel`, `UltralightBrowserView`, `UltralightEngine.isReady()`).
Pour déclarer la dépendance, voir la section « Consommer depuis un autre mod » de
[docs/API.md](../../docs/API.md).
