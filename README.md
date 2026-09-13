# ultralight-fabric

Mod-API **Fabric** (client, Minecraft **26.2**) qui rend du **HTML / CSS / JS** dans une texture
Minecraft via **[Ultralight](https://ultralig.ht) 1.4 (WebKit 615 ≈ Safari 16.4)**, exposé pour
être consommé par d'autres mods.

Pensé comme un wrapper **léger** (faible empreinte mémoire) pour des overlays / UIs web in-game —
là où une solution Chromium (MCEF) serait bien plus lourde.

> 📖 Documentation complète : **[Wiki](../../wiki)** · API détaillée : **[docs/API.md](docs/API.md)** · exemple : **[reference/overlay-example/](reference/overlay-example/)**

## État

| | |
|---|---|
| Moteur | Ultralight **1.4.0** / WebKit **615** via le binding [Luminescence](https://github.com/Solomon-Team/Luminescence) |
| Plateformes | ✅ Windows x64 · ✅ Linux x64 · ✅ macOS arm64 *(Intel mac : non encore packagé)* |
| Rendu | CPU mode → texture MC (BGRA prémultiplié → RGBA straight-alpha, copie native) |
| Natifs | **téléchargés au 1er lancement** (façon MCEF), pas embarqués → jar léger (~15 Mo) |
| Toolchain | Java **25**, Gradle 9.7, fabric-loom 1.17 (MC 26.x n'est plus obfusqué : ni yarn ni intermediary) |

CSS moderne (WebKit 615) : grid, flexbox `gap`, `aspect-ratio`, `clip-path`,
`-webkit-backdrop-filter`, `var()`, transitions, `inset`, `overflow:clip`…
*Non rendus par ce build : `conic-gradient`, `:has()`.*

## Comment ça marche (natifs)

Le jar du mod **ne contient pas** le SDK Ultralight ni le pont JNI. Au premier lancement,
`UltralightNativeLoader` détecte la plateforme et **télécharge** le pack correspondant
(`ultralight-natives-<platform>.zip` = SDK Ultralight 1.4 + `LuminescenceJNI`) depuis la
[release `natives-1.4.0`](../../releases/tag/natives-1.4.0), le dézippe dans
`<gameDir>/ultralight-1.4/`, puis le charge. C'est mis en cache (re-téléchargement uniquement
si la version change).

- Base de téléchargement surchargeable : `-Dultralight.natives.url=<url/>`.
- Dev hors-ligne : place les natifs dans `<gameDir>/ultralight-1.4/bin` → le téléchargement est ignoré.
- Désactivation manuelle du rendu HTML : `-Dultralight.disable=true` (ou `ULTRALIGHT_DISABLE=true`).
- **CPU sans AVX** (Intel pré-2011 / AMD pré-Bulldozer) : les natifs WebKit 615 sont compilés avec
  AVX → le rendu HTML se **désactive automatiquement** sur ces machines (au lieu de crasher).
  Bypass de test : `-Dultralight.skipCpuCheck=true` (⚠️ re-crashe sur un vrai CPU sans AVX).

## Build (développeurs)

Prérequis : **JDK 25** (MC 26.x compile en release 25). Le wrapper Gradle (9.7) se charge du reste.

```bash
# 1. Place l'API Luminescence dans libs/ :  libs/luminescence-2026.1.0.jar
#    (depuis les releases Luminescence)
# 2. Installe-la dans le Maven local (requise pour le jar-in-jar) :
scripts/install-luminescence.ps1     # Windows   (ou: bash scripts/install-luminescence.sh)
# 3. Build :
./gradlew build                      # → build/libs/ultralight-1.0.jar (~15 Mo, autonome)
```

Le jar embarque l'API Luminescence (LGPL) + icu4j en **jar-in-jar**. Les natifs ne sont **pas**
nécessaires pour compiler (récupérés au runtime).
Pour lancer en dev : `./gradlew runClient` (les natifs se téléchargent, ou place-les à la main).

> **MC 26.x n'est plus obfusqué** : plus de mappings yarn ni d'intermediary dans `build.gradle`,
> les dépendances mod se déclarent en `implementation` (plus de `modImplementation`), et il n'y a
> plus de tâche `remapJar` — le `jar` produit est directement le jar du mod.

### Valider le jar publié (jar-in-jar)

`runClient` résout Luminescence et icu4j depuis le classpath de dev, donc un défaut
d'empaquetage y reste **invisible**. Pour tester le jar tel que le joueur l'installe, relancer le
jeu avec un classpath d'où le mod et ses deux dépendances ont été retirés, et le jar ajouté via
`-Dfabric.addMods` :

1. `./gradlew build` puis `./gradlew runClient` une fois, pour que loom écrive
   `build/loom-cache/argFiles/runClient` ;
2. recopier cet argfile en retirant du `-classpath` les entrées `build/classes/java/main`,
   `build/resources/main`, `luminescence-*.jar` et `icu4j-*.jar` ;
3. relancer `java` avec cet argfile et `-Dfabric.addMods=build/libs/ultralight-<version>.jar`.

Le log doit annoncer `ultralight <version>` dans la liste des mods chargés. Si l'imbrication est
cassée, le jeu échoue sur un `NoClassDefFoundError` au lieu de démarrer. En armant la sonde
(`ULTRALIGHT_PANELPROBE=true`), on vérifie en plus que le mod **fonctionne**, pas seulement
qu'il se charge.

### Régénérer les packs de natifs
Les packs Linux/macOS sont produits et publiés automatiquement par le workflow
**`.github/workflows/build-luminescence-natives.yml`** (Actions → Run workflow). Le pack
Windows est assemblé localement (VS 2026 indisponible sur les runners GitHub) et uploadé à la main
sur la release `natives-1.4.0`.

## API (mods consommateurs)

`UltralightEngine.init()` dans `onInitializeClient`, puis **`UltralightPanel`** : il possède la
géométrie (taille de vue, `deviceScale`, rectangle de dessin, conversion des coordonnées souris)
et suit la fenêtre tout seul.

```java
panel = UltralightPanel.builder()
        .design(1280, 720)                        // viewport CSS visé par la page
        .fit(UltralightPanel.Fit.FILL_CLAMPED)    // défaut
        .build();
panel.loadHTML(html);
// Screen.extractRenderState : panel.renderInScreen(graphics);
```

Trois politiques de mise en page, pour que les interfaces ne cassent plus selon le **ratio
d'écran** : `FILL` (largeur CSS libre), `CONTAIN` (taille de design garantie, marges), et
`FILL_CLAMPED` (défaut : responsive mais borné). Le panneau logge à chaque changement le viewport
CSS réellement fourni à la page, et publie `--ul-vw` / `--ul-vh` / `--ul-aspect` /
`data-ul-ratio` / l'événement `ul:resize` côté CSS.

En dessous, `UltralightBrowserView` reste accessible (loadHTML/URL, pont JS `window.ulQuery`,
input, curseurs ; nom du pont configurable via `setBridgeName(...)`).
Détails : **[docs/API.md](docs/API.md)**.

## Licences

- **Code de ce mod** : [MIT](LICENSE) — voir [NOTICES.md](NOTICES.md) pour les tiers.
- **Ultralight** : SDK propriétaire, [licence Free](https://ultralig.ht/pricing) (gratuit < 100 k$
  de CA/financement, PC, usage applicatif, **attribution requise**). Binaires non redistribués par
  ce repo — téléchargés depuis la release au runtime.
- **Luminescence** : LGPL-3.0 · **icu4j** : licence Unicode.
