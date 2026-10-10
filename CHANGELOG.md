# Changelog

Branche `26.2`. Les versions pour Minecraft 26.3 (3.x) vivent sur la branche `26.3`.

## 2.1.0 : Minecraft 26.2 (en cours, publiée en `2.1.0-SNAPSHOT`)

Rétroportage sur 26.2 de tout ce que les 3.0.0 et 3.1.0 ont apporté sans dépendre de 26.3.
Compatible avec la 2.0.0 à la compilation : un mod écrit pour elle compile sans changement. Deux
nouveautés méritent quand même une ligne de code : `.animated(true)` pour une page animée, et
`keyPressed(KeyEvent)` pour le clavier.

### Rendu

- **Plancher d'échelle à 1.** Sous un `deviceScale` de 1 (panneau de design 1920×1080 dans une
  fenêtre plus petite), le rendu CPU d'Ultralight 1.4 perdait des couches entières : fond et images
  peints, ni texte ni blocs, jusqu'au changement suivant. Vu à 0,44 dans une fenêtre 854×480, sur
  la moitié des écrans d'une page. La vue est désormais peinte à sa taille CSS et la carte graphique
  la réduit ; le rectangle affiché et la souris ne changent pas. Le plafond `maxViewPixels` passe
  après ce plancher. La ligne `[ul-panel]` donne aussi la taille affichée.

### Usage par plusieurs mods

- `UltralightEngine.init()` est **idempotent**. Chaque mod consommateur l'appelle, et deux appels
  pompaient le moteur deux fois par frame.
- Créer une vue avant que le moteur soit prêt lève une `IllegalStateException` claire, au lieu de
  passer un renderer nul au code natif (crash JVM impossible à rattraper).
- Le nom du pont JS est **propre à chaque vue** (`setBridgeName`, `Builder.bridgeName`). Le
  réglage global devient `setDefaultBridgeName` et le dit. Le nom est validé : il est injecté dans
  du JS.

### Clavier, copier-coller

- **`keyPressed(KeyEvent)` / `keyReleased(KeyEvent)`**, sur le panneau et la vue. Les lettres y
  suivent la disposition du clavier : avec les codes entiers (positions physiques GLFW, nommées
  d'après le clavier US), la touche A d'un AZERTY arrive comme un Q, donc Ctrl+A devient Ctrl+Q et
  Ctrl+Z devient Ctrl+W. En 26.2 le `KeyEvent` ne porte que la position et le scancode : la lettre
  est lue par `glfwGetKeyName(key, scancode)`.
- **Table de touches complète** : F1 à F24, pavé numérique (avec l'indicateur « keypad »),
  ponctuation, touche `<>` des claviers ISO, modificateurs seuls, Verr. Maj, Verr. Num, Pause,
  Impr. écran. En 2.0.0 ces touches arrivaient à la page avec le code GLFW brut (F1 = 290) ; une
  touche inconnue arrive désormais avec `keyCode` 0.
- **Copier-coller** : le presse-papiers d'Ultralight n'était branché nulle part, Ctrl+C / Ctrl+V
  dans une page ne faisaient rien. Il passe désormais par celui de Minecraft (donc du système).

### Pages animées

- **`UltralightPanel.Builder.animated(true)`** (et `setAnimated` à chaud, aussi sur la vue).
  Vérifié en jeu sur 26.2 : sans ce réglage, une page qui s'anime sans interaction ne tourne
  **pas du tout** (0 frame `requestAnimationFrame`, 0 peinture sur 120 frames de jeu). Avec : 47
  à 49 frames JS et 38 à 49 peintures sur la même durée, selon le run.

### Performance : le pont JS ne tourne plus à vide

Le pont vidait sa file de messages à chaque frame et pour chaque vue, même sans aucun message
(~90 µs par frame et par vue). Il ne la vide plus que lorsque la page signale un message, avec un
vidage de secours toutes les 30 frames. Mesuré sur 26.2 (sonde perf, tour 2, deux runs) : 3,4 à
3,7 µs par frame pour une page statique en quart d'écran, 4,5 à 5,8 µs pour une page animée, 4,3 à
5,0 µs en plein écran.

### Garde-fous

- **Garde de thread** : tout appel à l'API depuis un autre thread que le render thread lève une
  `IllegalStateException` qui nomme le thread, au lieu de corrompre la mémoire native et de
  crasher plus tard, ailleurs, sans explication.
- **`renderFrame()` est ignoré pendant l'extraction de la GUI**, avec un avertissement et la pile
  de l'appelant. C'est l'appel qui produisait la page en damier et la vue noire. Il est aussi
  ignoré en jeu, où le moteur est déjà pompé.
- **Alerte au-delà de 8 vues ouvertes** en même temps (≈16 Mo chacune en 1080p), puis à 16, 32… :
  c'est presque toujours une vue jamais fermée.
- **Texture libérée entre deux frames** : fermer une vue pendant le rendu (depuis un `HudElement`,
  par exemple) libérait sa texture alors que le dessin de la frame en cours la référençait encore
  (`GL_INVALID_OPERATION`). La libération est reportée au tick client suivant.
- Échecs de peinture et d'entrée signalés une fois au niveau par défaut, au lieu d'être avalés en
  debug. `bounds()` du panneau validé. Allocation de la texture protégée contre le débordement et
  la fuite du tampon natif. Temporisation de redimensionnement sur horloge monotone.
- **Logs du moteur** : les messages internes d'Ultralight arrivent dans le log de Minecraft
  (`ultralight/native`).

### Nettoyage

- `UltralightJsBridge` supprimée : inutilisée, et impossible à instancier hors de la bibliothèque.

### Sonde

23 vérifications automatiques dans la phase input (7 en 2.0.0), les mêmes qu'en 3.1.0. Celle de
la lettre AZERTY a deux volets en 26.2, pour ne pas dépendre du clavier du poste de test : la table
doit traduire « position Q, nom a » en VK_A, et une vraie frappe GLFW doit suivre la disposition
active. Phase `typing` manuelle : elle trace pour chaque touche le code GLFW, le scancode, le nom
selon la disposition et le VK envoyé.

### Non rétroporté, et pourquoi

Tout ce qui suit appartient au portage 26.3 lui-même (commit `db270af`) et n'a pas de sens, ou
pas d'objet, en 26.2 :

- **Entrée SDL** (boutons 1/2/3, scancodes SDL, table SDL vers VK) : MC 26.2 est en GLFW. La
  table de touches a été réécrite pour GLFW, avec la même couverture.
- **Curseurs appliqués par la vue** (`setCursorHandler(Consumer<CursorType>)`) : changerait la
  signature publique de la 2.0.0. En 26.2, `setCursorHandler(IntConsumer)` reçoit toujours une
  forme GLFW et c'est au mod d'appliquer le curseur.
- **Notification de `TextInputManager`** : ce service n'existe qu'en 26.3, où le backend SDL en a
  besoin. GLFW n'en a pas besoin.
- **Pompage sur `LevelExtractionEvents.END_EXTRACTION`** : imposé en 26.3 par les passes de rendu
  de `renderpearl`. En 26.2, `LevelRenderEvents.START_MAIN` reste le bon point (avant la GUI, et
  sans passe de rendu qui interdise l'écriture).
- **`com.mojang.renderpearl`** : la texture reste `com.mojang.blaze3d.textures.GpuTexture`.

### Compatibilité

- Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API **0.160.0+26.2**, **Java 25**
- Natifs Ultralight 1.4 / WebKit 615 téléchargés au premier lancement
- Windows x64, Linux x64, macOS arm64
- CPU **AVX2** requis (sinon le rendu HTML se désactive au lieu de crasher)

### Migration depuis 2.0.0

1. **Clavier** : remplacer `panel.keyPressed(key.key(), key.modifiers())` par
   `panel.keyPressed(key)` (idem pour `keyReleased`). Indispensable en AZERTY.
2. **Page animée** : `.animated(true)` sur le panneau, au lieu d'appeler `requestRepaint()` à
   chaque frame.
3. `UltralightBrowserView.setBridgeName(String)` statique devient une méthode d'instance ; le
   réglage global s'appelle `setDefaultBridgeName`.
4. Ne pas créer de vue avant `UltralightEngine.isReady()` (ou utiliser `UltralightPanel`, qui
   attend tout seul).
5. Supprimer tout appel à `UltralightEngine.renderFrame()` depuis un écran ou un élément de HUD.
6. Vérifier qu'aucun appel à l'API ne part d'un autre thread : il lève désormais une exception.

Pour préparer le passage à 26.3 : ne comparer les codes de touche et de bouton qu'aux constantes
d'`InputConstants`, jamais à des valeurs en dur (voir `docs/API.md`, « Différences avec la
3.1.0 »).

---

## 2.0.0 : Minecraft 26.2

Portage sur **Minecraft 26.2**, plus une couche de mise en page qui règle le problème des
interfaces qui cassaient selon le ratio d'écran.

### Nouveau : `UltralightPanel`

L'entrée recommandée. Le panneau possède la géométrie (taille de vue, `deviceScale`, rectangle de
dessin, suivi de la fenêtre) et la conversion « coordonnées logiques Minecraft → pixels CSS », que
chaque mod consommateur réimplémentait et ratait. Trois politiques de mise en page :

| Mode | Ce que la page reçoit | Quand le choisir |
|---|---|---|
| `FILL` | hauteur CSS = design, largeur libre | seulement si le CSS est réellement responsive |
| `CONTAIN` | **exactement** la taille de design, toujours | ratio fixe ; marges transparentes, zéro risque |
| `FILL_CLAMPED` *(défaut)* | largeur CSS bornée | le compromis : responsive dans une plage saine |

Le panneau logge à chaque changement le viewport CSS réellement fourni à la page, et publie
`--ul-vw`, `--ul-vh`, `--ul-aspect`, `data-ul-ratio` et un événement `ul:resize` côté CSS.
`setPreviewAspect()` simule un ratio sans toucher à la fenêtre.

### Corrections de rendu

- **Ne rien écrire sur le GPU pendant la phase d'extraction de la GUI.** MC 26.x construit
  l'interface en deux temps ; écrire une texture au milieu de l'extraction faisait apparaître la
  page en damier derrière toute la scène, et une vue de la largeur du framebuffer en noir plein.
- **Garde-fou CPU** : les natifs WebKit 615 utilisent des instructions **AVX2**, pas seulement AVX.
- **Upload par bande** : l'upload de la zone sale est refait en bande pleine largeur, sans copie
  intermédiaire (`writeToTexture` n'accepte plus de sous-rectangle source en 26.x).

### Compatibilité

Minecraft **26.2**, Fabric Loader **0.19.5+**, Fabric API, **Java 25**.
