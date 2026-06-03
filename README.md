# ultralight-fabric

Mod-API **Fabric** (client, Minecraft 1.21.11) qui rend du **HTML / CSS / JS** dans une texture
Minecraft via **[Ultralight](https://ultralig.ht) 1.4 (WebKit 615)**, exposé pour être consommé
par d'autres mods.

Pensé comme un wrapper **léger** (faible empreinte mémoire) pour des overlays / UIs web in-game —
là où une solution Chromium (MCEF) serait beaucoup plus lourde.

## État

| | |
|---|---|
| Moteur | Ultralight **1.4.0** / WebKit **615** (≈ Safari 16.4) via le binding [Luminescence](https://github.com/Solomon-Team/Luminescence) |
| Windows x64 | ✅ fonctionnel (rendu, pont JS, input souris/clavier, curseurs) |
| macOS / Linux | 🚧 natifs à builder en CI (`.github/workflows/build-luminescence-natives.yml`) |
| Rendu | CPU mode → texture MC (BGRA prémultiplié → RGBA straight-alpha, copie native) |

CSS moderne disponible (WebKit 615) : grid, flexbox `gap`, `aspect-ratio`, `clip-path`,
`-webkit-backdrop-filter`, `var()`, transitions… (`conic-gradient` et `:has()` non rendus par ce build).

## Build

Le repo ne contient **pas** les binaires (sources seules). Pour builder localement :

1. **Binding Luminescence** — placer dans `libs/` :
   - `luminescence-2026.1.0.jar` (API) + `luminescence-2026.1.0-natives-<platform>.jar` (JNI),
     depuis les [releases Luminescence](https://github.com/Solomon-Team/Luminescence/releases)
     (ou produits par le workflow CI pour macOS/Linux).
2. **SDK Ultralight 1.4 Free** — extraire l'archive de la plateforme
   ([mirror](https://github.com/Ayydxn/Voxellight-Ultralight-SDKs/releases) ou
   [ultralig.ht](https://ultralig.ht)) dans :
   ```
   src/main/resources/ultralight-sdk/<platform>/
     ├── bin/          (UltralightCore, WebCore, Ultralight, AppCore)
     ├── resources/    (icudt67l.dat, cacert.pem)
     └── manifest.txt  (liste des fichiers ci-dessus, chemins relatifs)
   ```
   `<platform>` ∈ `windows-x64`, `linux-x64`, `linux-arm64`, `macos-x64`, `macos-arm64`.
3. `./gradlew build`

Au 1er lancement, le mod extrait le SDK de la plateforme courante dans `<gameDir>/ultralight-1.4/`
puis charge les natifs.

## Utilisation (API)

Voir **[docs/API.md](docs/API.md)** (moteur, vue, pont JS `window.abysseQuery`, input, curseurs,
recette d'overlay réactif) et l'exemple complet dans **[reference/overlay-example/](reference/overlay-example/)**.

## Licences

- **Code de ce mod** : voir `LICENSE` *(à définir)*.
- **Ultralight** : SDK propriétaire, licence [Ultralight Free](https://ultralig.ht/pricing)
  (gratuit < 100 k$ de CA/financement, PC, usage applicatif, **attribution requise**).
  Les binaires ne sont pas redistribués dans ce repo ; le jar de release les embarque.
- **Luminescence** : LGPL-3.0.
- **icu4j** : licence Unicode.
