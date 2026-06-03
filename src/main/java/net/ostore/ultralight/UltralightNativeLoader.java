package net.ostore.ultralight;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Chargement des natifs Ultralight 1.4 via Luminescence.
 *
 * <p>Le SDK Ultralight 1.4 (bin/ + resources/) est <b>embarqué dans le jar</b> sous
 * {@code /ultralight-sdk/<platform>/} (avec un {@code manifest.txt}). Au premier lancement,
 * la plateforme courante est extraite dans {@code <gameDir>/ultralight-1.4/}, puis le pont
 * JNI {@code LuminescenceJNI} (embarqué dans le jar de natifs Luminescence) est chargé.
 *
 * <p>Note licence : embarquer les binaires Ultralight = redistribution → prévoir le
 * {@code NOTICES} d'attribution (licence Ultralight Free).
 */
final class UltralightNativeLoader {

    private static final Logger LOG = LoggerFactory.getLogger("abysse/ul-loader");

    static final String SDK_DIR_NAME   = "ultralight-1.4";
    private static final String SDK_VERSION = "1.4.0";
    private static final String BUNDLE_BASE = "/ultralight-sdk/";
    private static final String VERSION_FILE = ".ul-sdk-version";

    private static volatile boolean loaded = false;
    private static Path loadedSdkDir = null;

    private UltralightNativeLoader() {}

    static Path getSdkDir() { return loadedSdkDir; }
    static boolean isLoaded() { return loaded; }

    /**
     * Extrait le SDK 1.4 de la plateforme courante depuis le jar puis charge les natifs.
     * @return la racine du SDK (contenant {@code resources/}) ou {@code null} en cas d'échec.
     */
    static Path load() {
        if (loaded) return loadedSdkDir;

        String platform = detectPlatform();
        if (platform == null) {
            LOG.error("[ul] Plateforme non supportée : {}", System.getProperty("os.name"));
            return null;
        }

        Path sdkDir = FabricLoader.getInstance().getGameDir().resolve(SDK_DIR_NAME);
        if (!extractSdk(platform, sdkDir)) return null;

        try {
            me.ayydxn.luminescence.internal.UltralightNativeLoader.load(sdkDir.resolve("bin").toAbsolutePath());
        } catch (Throwable t) {
            LOG.error("[ul] Chargement des natifs Ultralight 1.4 / Luminescence échoué", t);
            return null;
        }

        loadedSdkDir = sdkDir;
        loaded = true;
        LOG.info("[ul] Natifs Ultralight 1.4 (WebKit 615) chargés [{}].", platform);
        return sdkDir;
    }

    /** Extrait les fichiers listés dans le manifeste de la plateforme. Idempotent (marqueur de version). */
    private static boolean extractSdk(String platform, Path sdkDir) {
        Path marker = sdkDir.resolve(VERSION_FILE);
        String tag = SDK_VERSION + "-" + platform;
        try {
            if (Files.isRegularFile(marker) && tag.equals(Files.readString(marker, StandardCharsets.UTF_8).trim()))
                return true; // déjà extrait
        } catch (Exception ignore) {}

        String base = BUNDLE_BASE + platform + "/";
        List<String> files;
        try (InputStream in = UltralightNativeLoader.class.getResourceAsStream(base + "manifest.txt")) {
            if (in == null) {
                LOG.error("[ul] SDK 1.4 non embarqué pour {} (manifeste {}manifest.txt absent du jar).", platform, base);
                return false;
            }
            files = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        } catch (Exception e) {
            LOG.error("[ul] Lecture manifeste SDK échouée", e);
            return false;
        }

        try {
            Files.createDirectories(sdkDir);
            for (String rel : files) {
                Path dest = sdkDir.resolve(rel);
                Files.createDirectories(dest.getParent());
                try (InputStream in = UltralightNativeLoader.class.getResourceAsStream(base + rel)) {
                    if (in == null) { LOG.error("[ul] Fichier SDK manquant dans le jar : {}{}", base, rel); return false; }
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.writeString(marker, tag, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.error("[ul] Extraction SDK 1.4 échouée", e);
            return false;
        }
        LOG.info("[ul] SDK Ultralight 1.4 [{}] extrait ({} fichiers).", platform, files.size());
        return true;
    }

    /** Doit correspondre aux dossiers /ultralight-sdk/<platform>/ et au loader Luminescence. */
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
