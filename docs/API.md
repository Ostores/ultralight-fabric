# Ultralight API — guide d'intégration

Mod-API Fabric (client, MC 26.3) qui rend du HTML/CSS/JS dans une texture Minecraft
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
| `static void init()` | À appeler dans `onInitializeClient`. Enregistre le pilote de frame (`LevelExtractionEvents.END_EXTRACTION`, par frame, hors passe de rendu et **avant** la GUI) ; l'init native (plateforme + renderer) est **différée au 1er frame** (fenêtre/GL prêtes). |
| `static boolean isReady()` | Le moteur est-il prêt. |
| `static void renderFrame()` | **Pompe un cycle** update/render/paint des vues actives. Normalement **inutile** : le moteur se pompe seul à chaque frame. Ne jamais l'appeler depuis une phase d'extraction de la GUI (voir l'encadré ci-dessous). |

En jeu, les vues se mettent à jour seules, écran ouvert ou non.

> ### ⚠️ Ne rien pomper pendant la phase d'extraction de la GUI
>
> MC 26.x construit l'interface en **deux temps** : extraction de l'état de rendu, puis soumission
> GPU. Écrire dans une texture GPU au milieu de l'extraction corrompt le lot de dessins de
> Minecraft. Constaté en jeu, de deux façons spectaculaires :
>
> - le **fond de menu** de Minecraft (un quad plein écran tuilé sur une texture 32×32) se met à
>   échantillonner notre texture : la page apparaît répétée en damier derrière toute la scène ;
> - une vue dont la largeur égale celle du framebuffer s'affiche en **noir plein**.
>
> Les textures produites sont pourtant correctes (vérifié en les vidant sur disque) : le défaut est
> uniquement dans le *moment* de l'écriture. Dans `extractRenderState` et dans un `HudElement`, on
> ne fait donc que **dessiner**.
>
> **MC 26.3 ajoute une seconde contrainte, opposée.** L'abstraction GPU est passée à
> `com.mojang.renderpearl`, avec des passes de rendu explicites : écrire une texture alors qu'une
> passe est ouverte lève `Close the existing render pass before performing additional commands`.
> Or `LevelRenderEvents.START_MAIN`, qui convenait en 26.2, est branché à l'intérieur du frame
> graph. Le moteur pompe donc depuis `LevelExtractionEvents.END_EXTRACTION` : l'extraction du
> monde précède le frame graph (aucune passe ouverte) **et** la construction de la GUI.

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
Côté page : `window.ulQuery(data)` (fonction native, injectée à chaque chargement).

Le nom est réglable **par vue** via `view.setBridgeName("…")`, ou à la construction du panneau
via `.bridgeName("…")`. Il existe aussi `UltralightBrowserView.setDefaultBridgeName("…")`, mais
c'est un réglage **global au jeu** : si deux mods l'appellent, le dernier casse le pont de l'autre.
Le nom doit être un identifiant JavaScript, il est injecté dans `window['<nom>']`.
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

### Input (codes `InputConstants`, ceux de MC)
| Méthode | Notes |
|---|---|
| `mouseMoved(int x, int y)` | Coords en **pixels CSS** de la vue (voir le piège §4). |
| `mousePressed(int x, int y, int mcButton)` | `InputConstants.MOUSE_BUTTON_LEFT/RIGHT/MIDDLE`. |
| `mouseReleased(int x, int y, int glfwButton)` | |
| `scroll(int deltaXpx, int deltaYpx)` | Défilement en pixels. |
| `charTyped(String text)` | Saisie de texte (événement CHAR). |
| `keyPressed(int mcKey, int mcMods)` | Codes `InputConstants` (scancodes SDL depuis 26.3 ; mapping interne vers les VK attendus par Ultralight). |
| `keyReleased(int mcKey, int mcMods)` | |
| `focus()` / `unfocus()` | Donne/retire le focus clavier à la vue. |
| `boolean hasInputFocus()` | `true` si un élément éditable (`<input>`…) a le focus. |

### Curseurs
```java
view.setCursorHandler(type -> { /* optionnel : filtrer, sinon la vue applique seule */ });
```
Depuis MC 26.3, **il n'y a plus rien à câbler** : la vue applique elle-même le curseur demandé par
la page, via le jeu de curseurs vanilla (`CursorTypes.POINTING_HAND`, `IBEAM`, `CROSSHAIR`,
`RESIZE_NS`/`EW`/`ALL`, `NOT_ALLOWED`, `ARROW`). Poser un handler ne sert plus qu'à filtrer ou
ignorer ces demandes ; il reçoit un `CursorType`.

---

## 3. Panneau — `UltralightPanel` (**entrée recommandée**)

`UltralightBrowserView` est la couche basse : c'est toi qui calcules la taille, le `deviceScale`,
le rectangle de dessin et la conversion des coordonnées souris. `UltralightPanel` fait tout ça, et
c'est ce qui empêche une interface de **casser selon le ratio d'écran**.

```java
panel = UltralightPanel.builder()
        .design(1280, 720)              // le viewport CSS pour lequel la page est écrite
        .fit(UltralightPanel.Fit.FILL_CLAMPED)   // défaut
        .build();
panel.loadHTML(html);

// Screen.extractRenderState(...)
panel.render(graphics);   // géométrie + dessin

// Screen.mouseClicked(...)
panel.mouseClicked(click.x(), click.y(), click.button());   // false = clic hors du panneau
```

### Les trois politiques (`Fit`)

| Mode | Ce que la page reçoit | Quand le choisir |
|---|---|---|
| `FILL` | hauteur CSS = design, **largeur CSS libre** (≈750 px en 5:4, ≈1420 px en 21:9) | seulement si le CSS est réellement responsive et testé sur toute la plage |
| `CONTAIN` | **exactement** la taille de design, toujours | interface au ratio fixe ; marges transparentes sur les écrans larges, zéro risque |
| `FILL_CLAMPED` *(défaut)* | hauteur CSS = design, largeur CSS **bornée** (défaut 0,72× à 1,25× la largeur de design) | le compromis : responsive dans une plage saine, jamais hors plage |

Hors bornes, `FILL_CLAMPED` centre et laisse des marges (trop large) ou réduit l'échelle (trop
étroit) : l'interface devient plus petite, elle ne casse pas. Bornes ajustables via
`.cssWidthRange(min, max)`.

Le panneau **écrit une ligne INFO à chaque changement de géométrie**, pour que tu saches sans
instrumenter quoi que ce soit ce que ta page reçoit :

```
[ul-panel] fit=FILL_CLAMPED · vue 2250×1080 px · viewport CSS 1500×720 · deviceScale 1.50 (design 1280×720) · marges 155/0 px (largeur CSS bornée à 1600)
```

En `FILL`, si la largeur CSS s'écarte de plus de 20/30 % du design, un WARN te le signale une fois
et te renvoie vers `FILL_CLAMPED` / `CONTAIN`.

### Ce que la page reçoit en plus

À chaque changement de géométrie (et à chaque chargement de page), le panneau publie :

- `--ul-vw`, `--ul-vh`, `--ul-aspect` sur `:root` ;
- `data-ul-ratio` sur `<html>` : `ultrawide` (≥ 2,1), `wide` (≥ 1,55), `standard` (≥ 1,2), `tall` ;
- un événement `ul:resize` (`detail = {width, height, aspect, ratio}`).

```css
:root[data-ul-ratio="ultrawide"] .grid { grid-template-columns: repeat(6, 1fr); }
:root[data-ul-ratio="tall"]      .grid { grid-template-columns: repeat(2, 1fr); }
```

### Tester les ratios sans changer de résolution

```java
panel.setPreviewAspect(21.0 / 9.0);   // simule un 21:9 dans la fenêtre actuelle ; 0 = normal
```

### Le reste de l'API

| Méthode | Rôle |
|---|---|
| `render(graphics)` | depuis `Screen.extractRenderState` **ou** un `HudElement` : géométrie + dessin. Ne pompe pas le moteur. |
| `mouseMoved/mouseClicked/mouseReleased/mouseScrolled(...)` | coordonnées **logiques MC** ; renvoie `false` hors du panneau |
| `keyPressed/keyReleased(glfwKey, mods)`, `charTyped(text)` | identiques à la vue |
| `focus()` / `unfocus()` / `hasInputFocus()` | focus clavier |
| `contains(x, y)`, `drawX/drawY/drawWidth/drawHeight()` | rectangle occupé, en px logiques |
| `cssWidth()` / `cssHeight()` | viewport CSS courant |
| `bounds(x, y, w, h)` *(builder)* | n'occuper qu'une fraction de l'écran |
| `maxViewPixels(n)` *(builder)* | plafond de résolution (défaut 3840×2160) |
| `bridgeName(nom)` *(builder)* | nom de la fonction de pont, propre à ce panneau |
| `view()` | la `UltralightBrowserView` sous-jacente |
| `close()` | libère tout |

---

## 4. Diagnostic CSS (opt-in)

Une sonde charge une page de test et logge les capacités CSS réelles + `userAgent`.
Activable par `-Dultralight.cssprobe=true` ou `ULTRALIGHT_CSSPROBE=true`. Inactive sinon.

### Sonde de géométrie (opt-in)

Parcourt les politiques de `Fit` et des ratios simulés en capturant une copie d'écran par étape
dans `run/screenshots`. C'est le test de non-régression de la géométrie, du redimensionnement et de
la règle « rien sur le GPU pendant l'extraction ».

| Drapeau | Effet |
|---|---|
| `-Dultralight.panelprobe=true` / `ULTRALIGHT_PANELPROBE=true` | arme la sonde ; elle attend que tu sois en jeu |
| `ULTRALIGHT_PANELPROBE_WORLD=<dossier>` | charge cette sauvegarde au lieu d'attendre |
| `ULTRALIGHT_PANELPROBE_QUIT=true` | quitte le jeu à la fin (run automatisé) |
| `ULTRALIGHT_PANELPROBE_MODE=geometry\|input\|perf\|all` | phases à jouer (défaut `all`) |

La phase **input** injecte de vrais événements à des positions CSS connues et vérifie ce que la
page a reçu : mapping des coordonnées, cible du clic, hit-test hors panneau, saisie texte, focus,
curseurs, molette. La phase **perf** compare le temps de frame avec et sans overlay ; couper la
synchro verticale et relever `maxFps` avant de la lancer, sinon la mesure est plafonnée.

Sans le premier drapeau, **aucun écouteur n'est enregistré** : coût nul dans un jar publié.
Diagnostic complémentaire : `-Dultralight.dumptexture=true` écrit dans `run/ul-dump/` la texture
telle qu'on la compose à chaque repaint complet, ce qui distingue un défaut de notre pipeline
pixel d'un défaut de dessin côté Minecraft.

Capacités WebKit **615** (Safari 16.4) : grid, flexbox `gap`, `aspect-ratio`, `clip-path`,
`-webkit-backdrop-filter`, `var()`, `inset`, `overflow:clip`, transforms, transitions, filter…
**Non rendus par ce build** : `conic-gradient`, `:has()` (utiliser `radial-gradient` / restructurer).
Perf : en CPU mode, éviter les animations plein écran continues + `backdrop-filter` (repaint coûteux).

---

## 5. Activation / compatibilité

- **Désactivation manuelle** : `-Dultralight.disable=true` (ou `ULTRALIGHT_DISABLE=true`) coupe
  totalement le rendu HTML — aucun natif n'est téléchargé ni chargé. `UltralightEngine.isReady()`
  reste `false` ; les mods consommateurs doivent le vérifier avant d'ouvrir une vue.
- **Garde-fou AVX** : les natifs WebKit 615 sont compilés avec AVX. Sur un CPU sans AVX (Intel
  pré-2011 / AMD pré-Bulldozer), la 1re instruction AVX lèverait un `SIGILL` natif = crash JVM dur.
  Le moteur détecte l'absence d'AVX (flag HotSpot `UseAVX`) et **désactive le rendu HTML** au lieu
  de crasher. Forçage (tests) : `-Dultralight.skipCpuCheck=true`.

---

## 6. Recette manuelle (sans `UltralightPanel`)

> `UltralightPanel` (§3) fait tout ce qui suit à ta place. Cette section documente la mécanique
> pour qui veut piloter la géométrie lui-même. Exemple complet : **`reference/overlay-example/`**.

Points qui font qu'un overlay s'adapte à la **fenêtre** (et pas au réglage « GUI Scale ») :

1. **Taille = pixels physiques de la fenêtre**, pas l'espace logique GUI :
   ```java
   int fbW = window.getWidth(), fbH = window.getHeight();   // pixels physiques (MC 26.x)
   double deviceScale = Math.max(1.0, fbH / 600.0);   // CSS voit ~600px de haut, constant
   view = new UltralightBrowserView(fbW, fbH, deviceScale);
   ```
   (`window.getGuiScaledWidth/Height` est l'espace logique « GUI Scale » — ce n'est PAS ce qu'on veut ici.)
2. **Ne pompe pas le moteur** depuis `extractRenderState()` : il tourne déjà avant la GUI, et y
   écrire dans une texture GPU casse le rendu (voir §1).
3. **Dessin net** (1 texel = 1 px physique) via la surcharge région de `blit` :
   ```java
   graphics.blit(RenderPipelines.GUI_TEXTURED, id,
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

> **MC 26.x — ce qui a changé côté écran.** Les `Screen` ne dessinent plus dans
> `render(DrawContext…)` : ils remplissent un état de rendu dans
> `extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick)`,
> soumis au GPU ensuite. L'input passe par des records :
> `mouseClicked(MouseButtonEvent, boolean)` / `mouseReleased(MouseButtonEvent)` (`e.x()`, `e.y()`,
> `e.button()`), `keyPressed/keyReleased(KeyEvent)` (`e.key()`, `e.modifiers()`),
> `charTyped(CharacterEvent)` (`e.codepointAsString()`). Voir
> [`reference/overlay-example/`](../reference/overlay-example/) pour la version compilable.

---

## 7. Contenu dynamique

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
