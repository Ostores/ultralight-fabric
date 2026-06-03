# Ultralight API — guide d'intégration

Mod-API Fabric (client, MC 1.21.11) qui rend du HTML/CSS/JS dans une texture Minecraft
via **Ultralight 1.4 (WebKit 615, ≈ Safari 16.4)** (binding Luminescence). Conçu pour être
consommé par un autre mod via `mavenLocal()`.

> **Contrainte absolue : tout doit s'exécuter sur le render thread du client.**
> Le binding JNI met en cache le `JNIEnv` du thread d'init ; un appel depuis un
> autre thread = corruption mémoire native. En pratique : appelle l'API depuis le rendu
> (HUD, `Screen.render`, callbacks MC marshalés via `MinecraftClient.execute`).

---

## 1. Moteur — `UltralightEngine`

| Méthode | Rôle |
|---|---|
| `static void init()` | À appeler dans `onInitializeClient`. Enregistre le pilote de frame ; l'init native (plateforme + renderer) est **différée au 1er frame** (fenêtre/GL prêtes). |
| `static boolean isReady()` | Le moteur est-il prêt. |
| `static void renderFrame()` | **Pompe un cycle** update/render/paint des vues actives. À appeler depuis `Screen.render()` (voir §4) — sinon, en jeu, le rendu est piloté automatiquement par `HudRenderCallback`. Ne pas appeler en plus du tick HUD dans la même frame. |

En jeu (pas d'écran ouvert), les vues se mettent à jour seules. Tu n'as besoin de
`renderFrame()` que pour un overlay rendu pendant qu'un `Screen` est ouvert.

---

## 2. Vue — `UltralightBrowserView`

### Cycle de vie
```java
UltralightBrowserView view = new UltralightBrowserView(widthPx, heightPx, deviceScale);
view.loadHTML("<html>…</html>");      // ou view.loadURL("…")
// … usage …
view.close();                          // libère texture + mémoire native, stoppe les timers JS
```
- `widthPx`/`heightPx` : taille de la vue en **pixels (device)**.
- `deviceScale` : ratio DPI. Le CSS voit `widthPx/deviceScale × heightPx/deviceScale` px logiques.

### Contenu
| Méthode | Rôle |
|---|---|
| `loadHTML(String html)` | Charge du HTML inline. |
| `loadURL(String url)` | Charge une URL (`file://`, `http(s)://`, ou schéma custom — voir §5). |
| `resize(int wPx, int hPx)` | Redimensionne (détruit/recrée la texture). |
| `setDeviceScale(double)` | Ajuste le DPI/zoom CSS à chaud. |
| `executeJavaScript(String)` | Exécute du JS dans la page. |

### Rendu vers Minecraft
| Méthode | Rôle |
|---|---|
| `Identifier getTextureIdentifier()` | L'`Identifier` de texture MC à dessiner (`null` tant que pas prêt). |
| `boolean isTextureReady()` | La texture a-t-elle été peinte au moins une fois. |

Dessine avec **`RenderPipelines.GUI_TEXTURED`** (alpha *straight*). La vue produit de
l'alpha straight dé-prémultiplié ; **n'utilise PAS** `GUI_TEXTURED_PREMULTIPLIED_ALPHA`
(zones semi-transparentes délavées).

### Pont JS ↔ Java
Côté page : `window.ulQuery(data)` (fonction native, injectée à chaque chargement ;
nom configurable via `UltralightBrowserView.setBridgeName("…")`).
Côté Java :
```java
view.setQueryHandler(msg -> { /* msg = la string passée à ulQuery, sur le render thread */ });
view.updateQueryHandler(handler);              // remplace le handler sans recréer la vue
```
Pas de polling, pas de latence (callback natif JavaScriptCore).

### Cycle de page
```java
view.setOnPageReadyCallback(v -> { /* DOM prêt (onDOMReady), marshalé sur le thread principal */ });
boolean ready = view.isPageReady();
```

### Input (codes GLFW = ceux de MC)
| Méthode | Notes |
|---|---|
| `mouseMoved(int x, int y)` | Coords en **pixels CSS** de la vue (voir le piège §4). |
| `mousePressed(int x, int y, int glfwButton)` | `glfwButton` : `GLFW_MOUSE_BUTTON_LEFT/RIGHT/MIDDLE`. |
| `mouseReleased(int x, int y, int glfwButton)` | |
| `scroll(int deltaXpx, int deltaYpx)` | Défilement en pixels. |
| `charTyped(String text)` | Saisie de texte (événement CHAR). |
| `keyPressed(int glfwKey, int glfwMods)` | Touches d'édition/navigation/raccourcis (mapping GLFW→VK interne). |
| `keyReleased(int glfwKey, int glfwMods)` | |
| `focus()` / `unfocus()` | Donne/retire le focus clavier à la vue. |
| `boolean hasInputFocus()` | `true` si un élément éditable (`<input>`…) a le focus. |

### Curseurs
```java
view.setCursorHandler(glfwShape -> GLFW.glfwSetCursor(windowHandle, standardCursor(glfwShape)));
```
Le handler reçoit une **forme de curseur GLFW** (`GLFW_HAND_CURSOR`, `GLFW_IBEAM_CURSOR`,
`GLFW_ARROW_CURSOR`, redimensionnement…). Remets `glfwSetCursor(handle, 0L)` à la fermeture.

---

## 3. Diagnostic CSS (opt-in)

Une sonde charge une page de test et logge les capacités CSS réelles + `userAgent`.
Activable par `-Dultralight.cssprobe=true` ou `ULTRALIGHT_CSSPROBE=true`. Inactive sinon.

Capacités WebKit **615** (Safari 16.4) : grid, flexbox `gap`, `aspect-ratio`, `clip-path`,
`-webkit-backdrop-filter`, `var()`, `inset`, `overflow:clip`, transforms, transitions, filter…
**Non rendus par ce build** : `conic-gradient`, `:has()` (utiliser `radial-gradient` / restructurer).
Perf : en CPU mode, éviter les animations plein écran continues + `backdrop-filter` (repaint coûteux).

---

## 4. Recette : overlay web réactif dans un `Screen`

Voir l'exemple complet et validé dans **`reference/overlay-example/`**.

Points qui font qu'un overlay s'adapte à la **fenêtre** (et pas au réglage « GUI Scale ») :

1. **Taille = pixels physiques du framebuffer**, pas l'espace logique GUI :
   ```java
   int fbW = window.getFramebufferWidth(), fbH = window.getFramebufferHeight();
   double deviceScale = Math.max(1.0, fbH / 600.0);   // CSS voit ~600px de haut, constant
   view = new UltralightBrowserView(fbW, fbH, deviceScale);
   ```
2. **Pompe le moteur** dans `render()` : `UltralightEngine.renderFrame();` (le HUD ne tourne pas pendant un écran).
3. **Dessin net** (1 texel = 1 px physique) via la surcharge région :
   ```java
   ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id,
                   0, 0, 0f, 0f, this.width, this.height, fbW, fbH, fbW, fbH);
   // drawW/drawH = taille logique écran ; regionW/regionH = texW/texH = taille physique
   ```
4. ⚠️ **PIÈGE — coordonnées souris en pixels CSS**, pas device. Ultralight attend
   `device ÷ deviceScale` :
   ```java
   int vx = (int)Math.round(screenX / this.width  * (fbW / deviceScale));
   int vy = (int)Math.round(screenY / this.height * (fbH / deviceScale));
   ```
   (Invisible si `deviceScale == 1`, casse l'input sinon.)
5. **Resize** : refais 1. dans `Screen.init()` (rappelé au redimensionnement).

---

## 5. Contenu dynamique

### Ce qui marche (recommandé)
- **`loadHTML(String)`** avec CSS/JS inline + données injectées → couvre la plupart des UIs.
- **Pont JS** pour les données dynamiques : la page appelle `window.ulQuery("getInventory")`,
  Java reçoit via `setQueryHandler`, calcule, et renvoie via
  `view.executeJavaScript("window.onInventory(" + json + ")")`. Aller-retour propre, sans réseau.

### Schéma d'URL custom (`app://…`) — ❌ NE MARCHE PAS (testé)
Vérifié empiriquement : Ultralight **ne route pas** un schéma inconnu vers le
`UltralightFileSystem`. Il le traite comme une requête **réseau** (charge même le CA cert TLS)
puis échoue (`Load failed`), et `fetch("app://…")` est bloqué par les contrôles d'origine
(*« Cross origin requests are only supported for HTTP »*). N'y comptez pas.

### Repli pour des UIs multi-fichiers (si vraiment nécessaire)
Le `FileSystem` **est** consulté pour les URLs `file://` (c'est ainsi que le SDK charge ses
propres ressources). On peut donc, au besoin, faire un **FileSystem virtuel** : `loadURL`/
sous-ressources en `file://` sous un préfixe réservé, dont `fileExists`/`openFile`/`readFromFile`
**synthétisent** le contenu en Java (pas besoin de fichier réel). À ne construire que si le mod consommateur
sert réellement des assets séparés (.css/.js/.png) — sinon `loadHTML` + pont JS suffit.
Note : `fetch()` reste soumis à CORS même en `file://` → privilégier le pont JS pour les données.
