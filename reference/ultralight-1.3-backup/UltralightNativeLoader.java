package net.ostore.ultralight;

import com.labymedia.ultralight.UltralightJava;
import com.labymedia.ultralight.UltralightLoadException;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Extraction du bridge JNI ultralight-java depuis le JAR Maven,
 * et du SDK Ultralight depuis les ressources embarquées dans ce mod.
 *
 * <p>Le SDK (AppCore, Ultralight, UltralightCore, WebCore) est pré-packagé
 * dans le JAR du mod sous {@code /ultralight-natives/{os}-x64/} pour les trois
 * plateformes (windows, macos, linux). Il est extrait dans {@code <gameDir>/ultralight/sdk/}
 * au premier lancement uniquement (vérification par hash de commit).
 *
 * <p>Aucun téléchargement n'est effectué côté client.
 */
final class UltralightNativeLoader {

    private static final Logger LOG = LoggerFactory.getLogger("abysse/ul-loader");

    static final String SDK_DIR_NAME = "ultralight/sdk";

    // Commit hash du SDK Ultralight embarqué — doit correspondre à UL_COMMIT dans build.gradle.
    private static final String BUNDLED_COMMIT  = "b8daecd";
    private static final String NATIVES_BASE    = "/ultralight-natives/";
    private static final String VERSION_FILE    = ".ultralight-version";

    private static volatile boolean loaded    = false;
    private static Path             loadedSdkDir = null;

    private UltralightNativeLoader() {}

    static Path    getSdkDir() { return loadedSdkDir; }
    static boolean isLoaded()  { return loaded; }

    /**
     * Extrait le bridge JNI + SDK depuis le JAR, puis charge les natives.
     * Idempotent.
     */
    static boolean load() {
        if (loaded) return true;

        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path sdkDir  = gameDir.resolve(SDK_DIR_NAME);

        try {
            Files.createDirectories(sdkDir);
        } catch (IOException e) {
            LOG.error("[ul] Impossible de créer le répertoire natif", e);
            return false;
        }

        // 1. Bridge JNI depuis ultralight-java-base.jar
        try {
            UltralightJava.extractNativeLibrary(sdkDir);
        } catch (UltralightLoadException e) {
            LOG.error("[ul] Extraction bridge JNI échouée", e);
            return false;
        }

        // 1b. Copier ultralight-java.dll → ultralight-java-64.dll si besoin
        try {
            Path without = sdkDir.resolve(System.mapLibraryName("ultralight-java"));
            Path with64  = sdkDir.resolve(System.mapLibraryName("ultralight-java-64"));
            if (Files.isRegularFile(without) && !Files.isRegularFile(with64)) {
                Files.copy(without, with64);
            }
        } catch (IOException e) {
            LOG.warn("[ul] Copie bridge JNI échouée — tentative directe", e);
        }

        // 2. SDK depuis les ressources embarquées
        if (!extractNativesFromJar(sdkDir)) {
            LOG.error("[ul] Extraction SDK depuis le JAR échouée — rendu HTML désactivé.");
            return false;
        }

        // 3. Pré-charger toutes les DLLs du SDK dans l'ordre de dépendance.
        //    Sur Windows, le JVM ne cherche pas le répertoire du SDK lors du chargement
        //    des dépendances natives → on force l'ordre complet ici.
        if (!preloadSupportingLibs(sdkDir)) return false;

        // 4. Charger uniquement le bridge JNI (les SDK DLLs sont déjà en mémoire).
        try {
            UltralightJava.load(sdkDir, false);
        } catch (UltralightLoadException e) {
            LOG.error("[ul] Chargement bridge JNI échoué", e);
            return false;
        }

        loadedSdkDir = sdkDir;
        loaded = true;
        LOG.info("[ul] Natives Ultralight chargées depuis {}.", sdkDir.toAbsolutePath());
        return true;
    }

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Extrait les fichiers SDK depuis les ressources du JAR vers {@code sdkDir}.
     * Utilise {@code VERSION_FILE} comme cache : si la version extraite correspond
     * au commit embarqué, l'extraction est ignorée.
     */
    private static boolean extractNativesFromJar(Path sdkDir) {
        // Vérification version déjà extraite
        Path versionFile = sdkDir.resolve(VERSION_FILE);
        try {
            if (Files.isRegularFile(versionFile)
                    && BUNDLED_COMMIT.equals(Files.readString(versionFile, StandardCharsets.UTF_8).trim())) {
                return true;
            }
        } catch (IOException ignore) {}

        String platform = detectPlatform();
        if (platform == null) {
            LOG.error("[ul] Plateforme non supportée : {}", System.getProperty("os.name"));
            return false;
        }

        // Lire le manifest (liste des fichiers pour cette plateforme)
        String manifestRes = NATIVES_BASE + platform + "/manifest.txt";
        List<String> fileNames;
        try (InputStream in = UltralightNativeLoader.class.getResourceAsStream(manifestRes)) {
            if (in == null) {
                LOG.error("[ul] Manifest introuvable dans le JAR : {} — le mod a-t-il été buildé avec './gradlew build' ?", manifestRes);
                return false;
            }
            fileNames = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().filter(s -> !s.isBlank()).collect(Collectors.toList());
        } catch (IOException e) {
            LOG.error("[ul] Lecture manifest échouée", e);
            return false;
        }

        LOG.info("[ul] Extraction SDK {} ({} fichiers)...", platform, fileNames.size());
        for (String fileName : fileNames) {
            String res  = NATIVES_BASE + platform + "/" + fileName;
            Path   dest = sdkDir.resolve(fileName);
            try (InputStream in = UltralightNativeLoader.class.getResourceAsStream(res)) {
                if (in == null) {
                    LOG.error("[ul] Ressource manquante dans le JAR : {}", res);
                    return false;
                }
                Files.createDirectories(dest.getParent());
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.error("[ul] Extraction échouée : {}", fileName, e);
                return false;
            }
        }

        // Écrire le fichier de version pour les prochains lancements
        try { Files.writeString(versionFile, BUNDLED_COMMIT, StandardCharsets.UTF_8); }
        catch (IOException ignore) {}

        LOG.info("[ul] SDK {} extrait avec succès.", platform);
        return true;
    }

    /** Détecte la plateforme courante et retourne le nom du dossier de ressources. */
    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win"))                          return "windows-x64";
        if (os.contains("mac"))                          return "macos-x64";
        if (os.contains("linux") || os.contains("nix")) return "linux-x64";
        return null;
    }

    /**
     * Charge toutes les DLLs du SDK dans l'ordre de dépendance.
     *
     * <p>Sur Windows, le JVM ne recherche pas automatiquement le répertoire du SDK
     * lors de la résolution des dépendances natives. On doit donc charger chaque DLL
     * explicitement, dans l'ordre de dépendance, AVANT d'appeler UltralightJava.load().
     *
     * <p>Ordre Windows (chaque DLL dépend des précédentes) :
     * glib → gmodule → gobject → gthread → gio → gstreamer
     * → UltralightCore → WebCore → Ultralight → AppCore
     *
     * <p>Les DLLs système (VCRUNTIME140, MSVCP140, …) sont toujours dans System32 et
     * n'ont pas besoin d'être préchargées.
     *
     * @return {@code true} si toutes les DLLs critiques ont été chargées.
     */
    private static boolean preloadSupportingLibs(Path sdkDir) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // DLLs optionnelles (absentes selon plateforme/SDK) — échec ignoré silencieusement.
        String[] optional;
        // DLLs obligatoires — échec = abort.
        String[] required;

        if (os.contains("win")) {
            optional = new String[]{
                "glib-2.0-0.dll", "gmodule-2.0-0.dll", "gobject-2.0-0.dll",
                "gthread-2.0-0.dll", "gio-2.0-0.dll", "gstreamer-full-1.0.dll"
            };
            required = new String[]{
                "UltralightCore.dll", "WebCore.dll", "Ultralight.dll", "AppCore.dll"
            };
        } else {
            optional = new String[]{
                "libglib-2.0.so.0", "libgobject-2.0.so.0", "libgmodule-2.0.so.0",
                "libgthread-2.0.so.0", "libgio-2.0.so.0", "libgstreamer-full-1.0.so"
            };
            required = new String[0];
        }

        for (String name : optional) {
            Path p = sdkDir.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    System.load(p.toAbsolutePath().toString());
                    LOG.debug("[ul] Préchargé : {}", name);
                } catch (UnsatisfiedLinkError e) {
                    LOG.warn("[ul] Préchargement optionnel échoué pour {} : {}", name, e.getMessage());
                }
            }
        }

        for (String name : required) {
            Path p = sdkDir.resolve(name);
            if (!Files.isRegularFile(p)) {
                LOG.error("[ul] DLL requise introuvable : {}", p.toAbsolutePath());
                return false;
            }
            try {
                System.load(p.toAbsolutePath().toString());
                LOG.debug("[ul] Préchargé : {}", name);
            } catch (UnsatisfiedLinkError e) {
                LOG.error("[ul] Chargement DLL requise échoué pour {} : {}", name, e.getMessage());
                return false;
            }
        }
        return true;
    }
}
