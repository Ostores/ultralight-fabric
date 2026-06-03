# ultralight-fabric

Mod-API **Fabric** (client, Minecraft 1.21.11) qui rend du **HTML / CSS / JS** dans une texture
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
| Natifs | **téléchargés au 1er lancement** (façon MCEF), pas embarqués → jar léger (~14 Mo) |

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

## Build (développeurs)

```bash
# 1. Place l'API Luminescence dans libs/ :  libs/luminescence-2026.1.0.jar
#    (depuis les releases Luminescence)
# 2. Installe-la dans le Maven local (requise pour le jar-in-jar) :
scripts/install-luminescence.ps1     # Windows   (ou: bash scripts/install-luminescence.sh)
# 3. Build :
./gradlew build                      # → build/libs/ultralight-1.0.jar (~14 Mo, autonome)
```

Le jar embarque l'API Luminescence (LGPL) + icu4j en **jar-in-jar**. Les natifs ne sont **pas**
nécessaires pour compiler (récupérés au runtime).
Pour lancer en dev : `./gradlew runClient` (les natifs se téléchargent, ou place-les à la main).

### Régénérer les packs de natifs
Les packs Linux/macOS sont produits et publiés automatiquement par le workflow
**`.github/workflows/build-luminescence-natives.yml`** (Actions → Run workflow). Le pack
Windows est assemblé localement (VS 2026 indisponible sur les runners GitHub) et uploadé à la main
sur la release `natives-1.4.0`.

## API (mods consommateurs)

Entrée : `UltralightEngine.init()` (dans `onInitializeClient`), puis `UltralightBrowserView`
(loadHTML/URL, pont JS `window.ulQuery`, input souris/clavier, curseurs). Pont JS configurable via
`UltralightBrowserView.setBridgeName(...)`. Détails + recette d'overlay réactif : **[docs/API.md](docs/API.md)**.

## Licences

- **Code de ce mod** : [MIT](LICENSE) — voir [NOTICES.md](NOTICES.md) pour les tiers.
- **Ultralight** : SDK propriétaire, [licence Free](https://ultralig.ht/pricing) (gratuit < 100 k$
  de CA/financement, PC, usage applicatif, **attribution requise**). Binaires non redistribués par
  ce repo — téléchargés depuis la release au runtime.
- **Luminescence** : LGPL-3.0 · **icu4j** : licence Unicode.
