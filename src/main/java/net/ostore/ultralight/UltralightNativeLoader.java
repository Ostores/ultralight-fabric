package net.ostore.ultralight;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Chargement des natifs Ultralight 1.4 (SDK + pont JNI Luminescence) — <b>téléchargés au
 * premier lancement</b> (façon MCEF), pas embarqués dans le jar.
 *
 * <p>Au 1er run : détection de la plateforme → téléchargement de
 * {@code ultralight-natives-<platform>.zip} (bin/ {UL libs + LuminescenceJNI} + resources/)
 * depuis la release GitHub → dézippé dans {@code <gameDir>/ultralight-1.4/} → chargé.
 * Idempotent (marqueur de version). Repli : si les natifs sont déjà présents (placés à la
 * main), aucun téléchargement (pratique pour le dev hors-ligne).
 *
 * <p>Avantages : jar léger, multi-plateforme, et pas de redistribution des binaires Ultralight
 * (le joueur les récupère et accepte la licence).
 */
final class UltralightNativeLoader {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/loader");

    static final String SDK_DIR_NAME = "ultralight-1.4";
    private static final String SDK_VERSION = "1.4.0";
    /** Révision du contenu des packs de natifs. À incrémenter quand on republie un pack (ex.
     *  ajout de libs d'appoint bundlées) → force le re-téléchargement chez les clients. */
    private static final String PACK_REV = "2";

    /** Base de téléchargement des packs de natifs (release GitHub). Surchargeable via -D. */
    private static final String RELEASE_BASE = System.getProperty(
            "ultralight.natives.url",
            "https://github.com/Ostores/ultralight-fabric/releases/download/natives-1.4.0/");

    private static final String VERSION_FILE = ".ul-natives-version";

    private static volatile boolean loaded = false;
    private static Path loadedSdkDir = null;

    private UltralightNativeLoader() {}

    static Path getSdkDir() { return loadedSdkDir; }
    static boolean isLoaded() { return loaded; }

    /**
     * Garantit la présence des natifs (téléchargement si besoin) puis les charge.
     * @return racine du SDK (contenant {@code resources/}) ou {@code null} en cas d'échec.
     */
    static Path load() {
        if (loaded) return loadedSdkDir;

        String platform = detectPlatform();
        if (platform == null) {
            LOG.error("[ul] Plateforme non supportée : {}", System.getProperty("os.name"));
            return null;
        }

        Path sdkDir = FabricLoader.getInstance().getGameDir().resolve(SDK_DIR_NAME);
        Path binDir = sdkDir.resolve("bin");
        if (!ensureNatives(platform, sdkDir, binDir)) return null;

        try {
            // Indique à Luminescence où trouver SON pont JNI (qu'on a téléchargé dans bin/).
            Path jni = binDir.resolve(jniLibName(platform));
            if (Files.isRegularFile(jni)) {
                System.setProperty("ultralight.native.path", jni.toAbsolutePath().toString());
            }
            // Précharge les deps système bundlées (ex. libbz2 absente d'un runtime minimal/Flatpak)
            // AVANT que Luminescence ne charge libWebCore → le linker les résout depuis la mémoire.
            preloadBundledDeps(platform, binDir);
            me.ayydxn.luminescence.internal.UltralightNativeLoader.load(binDir.toAbsolutePath());
        } catch (Throwable t) {
            LOG.error("[ul] Chargement des natifs Ultralight 1.4 / Luminescence échoué", t);
            return null;
        }

        loadedSdkDir = sdkDir;
        loaded = true;
        LOG.info("[ul] Natifs Ultralight 1.4 (WebKit 615) chargés [{}].", platform);
        return sdkDir;
    }

    /** Télécharge + dézippe le pack si absent/obsolète. Repli : natifs déjà présents → OK. */
    private static boolean ensureNatives(String platform, Path sdkDir, Path binDir) {
        Path marker = sdkDir.resolve(VERSION_FILE);
        String tag = SDK_VERSION + "-" + platform + "-r" + PACK_REV;
        try {
            if (Files.isRegularFile(marker)
                    && tag.equals(Files.readString(marker, StandardCharsets.UTF_8).trim())
                    && nativesComplete(platform, binDir)) {
                return true; // déjà installé (SDK + JNI présents)
            }
        } catch (Exception ignore) {}

        // Repli dev : natifs placés à la main (SDK UL + JNI présents) → pas de téléchargement.
        if (nativesComplete(platform, binDir)) {
            LOG.info("[ul] Natifs déjà présents dans {} — téléchargement ignoré.", binDir);
            writeMarker(marker, tag);
            return true;
        }

        String url = RELEASE_BASE + "ultralight-natives-" + platform + ".zip";
        LOG.info("[ul] Téléchargement des natifs Ultralight 1.4 [{}] depuis {} …", platform, url);
        try {
            Files.createDirectories(sdkDir);
            Path zip = Files.createTempFile("ul-natives-", ".zip");
            try {
                download(url, zip);
                unzip(zip, sdkDir);
            } finally {
                Files.deleteIfExists(zip);
            }
            if (!nativesComplete(platform, binDir)) {
                LOG.error("[ul] Pack téléchargé invalide (SDK Ultralight ou JNI absent) pour {}.", platform);
                return false;
            }
            writeMarker(marker, tag);
            LOG.info("[ul] Natifs Ultralight 1.4 [{}] installés.", platform);
            return true;
        } catch (Throwable t) {
            LOG.error("[ul] Téléchargement/extraction des natifs échoué ({}). "
                    + "Vérifie ta connexion ou place les natifs dans {} manuellement.", t.getMessage(), binDir);
            return false;
        }
    }

    private static void download(String url, Path dest) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " pour " + url);
        }
    }

    /** Dézippe en se protégeant du zip-slip. */
    private static void unzip(Path zip, Path destDir) throws Exception {
        Path root = destDir.toAbsolutePath().normalize();
        try (InputStream fin = Files.newInputStream(zip);
             ZipInputStream zin = new ZipInputStream(fin)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IllegalStateException("Zip slip: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
                }
                zin.closeEntry();
            }
        }
    }

    private static void writeMarker(Path marker, String tag) {
        try { Files.writeString(marker, tag, StandardCharsets.UTF_8); } catch (Exception ignore) {}
    }

    /**
     * Précharge les bibliothèques d'appoint bundlées dans {@code bin/} (toute lib partagée qui
     * n'est pas un binaire principal du SDK) AVANT que Luminescence ne charge le SDK. Utile quand
     * une dépendance système de {@code libWebCore} manque (ex. {@code libbz2.so.1.0} dans un
     * runtime minimal / sandbox Flatpak) : une fois la lib chargée en mémoire, le linker la résout
     * pour satisfaire les entrées NEEDED. Plusieurs passes → gère les inter-dépendances.
     * Windows : rien (les DLLs embarquent/chargent leurs deps autrement).
     */
    private static void preloadBundledDeps(String platform, Path binDir) {
        if (platform.startsWith("windows")) return;
        String ext = platform.startsWith("macos") ? ".dylib" : ".so";
        List<String> mainLibs = List.of(
                "libWebCore", "libUltralight", "libUltralightCore", "libAppCore", "libLuminescenceJNI");
        List<Path> helpers = new ArrayList<>();
        try (var s = Files.list(binDir)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                int idx = n.indexOf(ext);
                if (idx < 0) return;                        // pas une lib partagée
                String base = n.substring(0, idx);          // "libbz2" pour libbz2.so.1.0
                if (!mainLibs.contains(base)) helpers.add(p); // lib d'appoint bundlée
            });
        } catch (Exception e) {
            return;
        }
        if (helpers.isEmpty()) return;
        List<Path> pending = new ArrayList<>(helpers);
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            List<Path> still = new ArrayList<>();
            for (Path p : pending) {
                try { System.load(p.toAbsolutePath().toString()); progress = true; }
                catch (Throwable t) { still.add(p); }       // deps pas encore satisfaites → passe suivante
            }
            pending = still;
        }
        int ok = helpers.size() - pending.size();
        if (ok > 0)  LOG.info("[ul] {} lib(s) d'appoint préchargée(s) depuis bin/.", ok);
        if (!pending.isEmpty()) LOG.warn("[ul] Libs d'appoint non préchargées : {}", pending);
    }

    private static String jniLibName(String platform) {
        if (platform.startsWith("windows")) return "LuminescenceJNI.dll";
        if (platform.startsWith("macos"))   return "libLuminescenceJNI.dylib";
        return "libLuminescenceJNI.so";
    }

    /** Bibliothèque cœur du SDK Ultralight (WebCore) — sa présence = SDK réellement installé. */
    private static String sdkCoreLibName(String platform) {
        if (platform.startsWith("windows")) return "WebCore.dll";
        if (platform.startsWith("macos"))   return "libWebCore.dylib";
        return "libWebCore.so";
    }

    /**
     * Vrai si le pont JNI <b>et</b> le cœur du SDK Ultralight sont présents dans {@code bin/}.
     * On ne se fie pas au seul JNI : un pack partiel (JNI sans les dylibs du SDK, ou une install
     * interrompue) laissait sinon un {@code bin/} « valide » que Luminescence rejetait ensuite
     * ({@code WebCore introuvable}). Vérifier le cœur du SDK rend l'install auto-réparable.
     */
    private static boolean nativesComplete(String platform, Path binDir) {
        return Files.isRegularFile(binDir.resolve(jniLibName(platform)))
            && Files.isRegularFile(binDir.resolve(sdkCoreLibName(platform)));
    }

    /** Doit correspondre aux noms de packs et au loader Luminescence. */
    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm");
        if (os.contains("win"))  return "windows-x64";
        if (os.contains("mac"))  return arm ? "macos-arm64" : "macos-x64";
        if (os.contains("nux") || os.contains("nix")) return arm ? "linux-arm64" : "linux-x64";
        return null;
    }
}
