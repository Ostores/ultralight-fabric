# Changelog

## 3.1.0 : Minecraft 26.3

Compatible avec la 3.0.0 : rien à changer pour continuer à compiler. Deux nouveautés méritent
quand même une ligne de code : `.animated(true)` pour une page animée, et `keyPressed(KeyEvent)`.

### Nouveau

- **Copier-coller** : le presse-papiers d'Ultralight n'était branché nulle part, Ctrl+C / Ctrl+V
  dans une page ne faisaient rien. Il passe désormais par celui de Minecraft (donc du système).
- **Pages animées : `UltralightPanel.Builder.animated(true)`** (et `setAnimated` à chaud, aussi sur
  la vue). Vérifié en jeu : sans ce réglage, une page qui s'anime sans interaction ne tourne
  **pas du tout** (0 frame `requestAnimationFrame` et 0 peinture sur 120 frames de jeu, environ une seconde). Avec : 79 frames
  JS et 73 peintures sur la même durée. `requestRepaint()` appelé à chaque frame faisait la même
  chose, mais n'était documenté nulle part hors de la javadoc.
- **`keyPressed(KeyEvent)` / `keyReleased(KeyEvent)`**, sur le panneau et la vue. Les lettres y
  suivent la disposition du clavier : avec les codes entiers (positions physiques), la touche A
  d'un AZERTY arrive comme un Q, donc Ctrl+A devient Ctrl+Q et Ctrl+Z devient Ctrl+W.
- **Table de touches complète** : F1–F24, pavé numérique, ponctuation, modificateurs seuls, Verr.
  Maj, etc. Ces touches arrivaient à la page avec `keyCode` 0.
- **Logs du moteur** : les messages internes d'Ultralight arrivent dans le log de Minecraft
  (`ultralight/native` ; erreurs et avertissements visibles, le reste en debug).

### Fiabilité

- **Garde de thread** : tout appel à l'API depuis un autre thread que le render thread lève une
  `IllegalStateException` qui nomme le thread, au lieu de corrompre la mémoire native et de
  crasher plus tard, ailleurs, sans explication.

### Nettoyage

- `UltralightJsBridge` supprimée : inutilisée, et impossible à instancier hors de la bibliothèque.

### Sonde

23 vérifications automatiques (16 en 3.0.0) : copier, coller, touches F2 et virgule, lettre en
disposition AZERTY, page animée, garde de thread. Le test du presse-papiers sauvegarde puis
restaure le contenu du joueur.

---

## 3.0.0 : Minecraft 26.3

Portage sur **Minecraft 26.3**, corrections pour l'usage par plusieurs mods, et une série de
garde-fous qui rendent les erreurs d'usage visibles au lieu de casser le rendu.

Version majeure : l'entrée passe de GLFW à SDL, ce qui change le **sens** des codes de touche et
de bouton sans changer les signatures. Un mod écrit pour la 2.0.0 compile, et se comporte mal s'il
comparait des codes en dur. Voir « Migration » plus bas.

### Portage 26.3

- **GPU** : l'abstraction de rendu de Minecraft est passée à `com.mojang.renderpearl` (backend
  Vulkan à côté d'OpenGL, passes de rendu explicites).
- **Point de pompage du moteur** : `LevelExtractionEvents.END_EXTRACTION`. En 26.3, écrire une
  texture pendant qu'une passe de rendu est ouverte lève une exception, et `START_MAIN` (utilisé en
  26.2) est justement dans le frame graph. L'extraction du monde précède à la fois le frame graph
  et la construction de la GUI, les deux contraintes sont respectées.
- **Entrée SDL** : table de conversion scancodes SDL → codes attendus par Ultralight, boutons de
  souris renumérotés.
- **Curseurs** : jeu de curseurs vanilla (`CursorTypes`), appliqué par la vue elle-même. Plus rien
  à câbler côté mod.
- **Saisie de texte** : la vue signale à `TextInputManager` quand un champ de la page prend ou perd
  le focus. Sans ça, le backend SDL se désynchronise et la saisie s'arrête.

### Usage par plusieurs mods

- `UltralightEngine.init()` est **idempotent**. Chaque mod consommateur l'appelle, et deux appels
  pompaient le moteur deux fois par frame.
- Créer une vue avant que le moteur soit prêt lève une `IllegalStateException` claire, au lieu de
  passer un renderer nul au code natif (crash JVM impossible à rattraper).
- Le nom du pont JS est **propre à chaque vue** (`setBridgeName`, `Builder.bridgeName`). Le
  réglage global devient `setDefaultBridgeName` et le dit. Le nom est validé : il est injecté dans
  du JS.

### Garde-fous

- **`renderFrame()` est ignoré pendant l'extraction de la GUI**, avec un avertissement et la pile
  de l'appelant. C'est l'appel qui produisait la page en damier et la vue noire. Il est aussi
  ignoré en jeu, où le moteur est déjà pompé.
- **Alerte au-delà de 8 vues ouvertes** en même temps (≈16 Mo chacune en 1080p), puis à 16, 32… :
  c'est presque toujours une vue jamais fermée.
- **Texture libérée entre deux frames** : fermer une vue pendant le rendu (depuis un
  `HudElement`, par exemple) libérait sa texture alors que le dessin de la frame en cours la
  référençait encore (`GL_INVALID_OPERATION` en OpenGL ; en Vulkan, une image détruite encore
  utilisée). La libération est reportée au tick client suivant.
- Échecs de peinture et d'entrée signalés une fois au niveau par défaut, au lieu d'être avalés en
  debug. `bounds()` du panneau validé. Allocation de la texture protégée contre le débordement et
  la fuite du tampon natif.

### Performance : le pont JS ne tourne plus à vide

Le pont vidait sa file de messages à chaque frame et pour chaque vue, même sans aucun message :
un verrou de contexte JS et une évaluation à chaque fois. Il ne la vide plus que lorsque la page
signale un message, avec un vidage de secours toutes les 30 frames.

Mesuré en jeu (1920×1080, tour 2 de la sonde), coût du pont par frame et par vue :

| Configuration | Avant | Après |
|---|---|---|
| Page statique, quart d'écran | 82 µs | 4 µs |
| Page animée, quart d'écran | 86 µs | 3,5 µs |
| Page animée, plein écran | 92 µs | 4,5 µs |

Sur une page statique, c'était environ un tiers du cycle du moteur. Le gain est systématique et
se multiplie par le nombre de vues ouvertes. Le cycle complet, lui, varie de ±0,15 ms d'un run à
l'autre : on ne le compare pas entre deux lancements.

### Sonde de diagnostic

16 vérifications automatiques dans la phase input (7 en 2.0.0) : les nouveaux garde-fous, le
pompage unique, le pont sur signal, la libération différée. Une phase `typing` manuelle vérifie la
saisie clavier réelle : SDL ignore les frappes injectées par l'OS, un script produirait un faux
négatif.

### Compatibilité

- Minecraft **26.3**, Fabric Loader **0.19.5+**, Fabric API **0.160.5+26.3**, **Java 25**
- Natifs Ultralight 1.4 / WebKit 615 téléchargés au premier lancement
- Windows x64, Linux x64, macOS arm64
- CPU **AVX2** requis (sinon le rendu HTML se désactive au lieu de crasher)

### Migration depuis 2.0.0

1. **Codes d'entrée** : transmettre tels quels ceux que reçoit le `Screen` (`click.button()`,
   `key.key()`, `key.modifiers()`) et ne les comparer qu'aux constantes de `InputConstants`.
   Tout `0`/`1`/`2` de bouton ou code de touche GLFW écrit en dur est faux en 26.3.
2. `setCursorHandler(IntConsumer)` devient `setCursorHandler(Consumer<CursorType>)`, et n'est plus
   nécessaire : la vue applique seule les curseurs. Supprimer le code GLFW de curseur.
3. `UltralightBrowserView.setBridgeName(String)` statique devient une méthode d'instance ; le
   réglage global s'appelle `setDefaultBridgeName`.
4. Ne pas créer de vue avant `UltralightEngine.isReady()` (ou utiliser `UltralightPanel`, qui
   attend tout seul).
5. Supprimer tout appel à `UltralightEngine.renderFrame()` depuis un écran ou un élément de HUD.

Dépendance et pièges détaillés : section « Consommer depuis un autre mod » de `docs/API.md`.

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
