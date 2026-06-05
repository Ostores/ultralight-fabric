package net.ostore.ultralight;

import me.ayydxn.luminescence.font.ULFontFile;
import me.ayydxn.luminescence.platform.ULFontLoader;
import me.ayydxn.luminescence.platform.impl.StandardULFontLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Chargeur de polices ajoutant des <b>polices de secours embarquées</b> pour les glyphes
 * que WebKit 615 (Ultralight 1.4) ne sait pas dessiner (carrés / « tofu ») :
 * <ul>
 *   <li><b>Noto Emoji</b> (monochrome) — emojis ;</li>
 *   <li><b>Noto Sans Symbols 2</b> — symboles non-emoji (✓ ★ ◈ ➤ …).</li>
 * </ul>
 *
 * <p><b>Routage conscient du système.</b> Le secours système d'Ultralight est Arial
 * ({@link StandardULFontLoader#getFallbackFont()}). On ne détourne un caractère vers nos
 * polices que si <b>Arial ne sait pas</b> le rendre (sinon on délègue → WebKit utilise
 * Arial, ce qui couvre l'ASCII, les lettres accentuées, les flèches simples, etc.). Parmi
 * nos polices, on choisit celle qui possède réellement le glyphe via {@link Font#canDisplay}.
 * Aucune devinette de plage Unicode → impossible de « voler » du texte normal.
 *
 * <p>Dégrade proprement : si AWT/une ressource manque, on délègue (jamais de détournement
 * hasardeux).
 */
public final class EmojiFallbackFontLoader implements ULFontLoader {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/fonts");

    public static final String EMOJI_FAMILY   = "AbysseEmoji";
    public static final String SYMBOLS_FAMILY = "AbysseSymbols";

    private static final String RES_EMOJI   = "/fonts/NotoEmoji-Regular.ttf";
    private static final String RES_SYMBOLS = "/fonts/NotoSansSymbols2-Regular.ttf";

    private final StandardULFontLoader delegate;
    private final String emojiPath, symbolsPath;   // .ttf extraits (rendu), ou null
    private final Font systemAwt;                   // Arial — pour savoir ce que le système couvre déjà
    private final Font emojiAwt, symbolsAwt;        // pour choisir la bonne police embarquée

    private final Set<Integer> loggedCps = new HashSet<>();
    private boolean loggedLoadE = false, loggedLoadS = false;

    public EmojiFallbackFontLoader() {
        this.delegate = new StandardULFontLoader();
        this.emojiPath   = extract(RES_EMOJI,   "emoji");
        this.symbolsPath = extract(RES_SYMBOLS, "symbols");
        this.emojiAwt   = awtFromPath(emojiPath);
        this.symbolsAwt = awtFromPath(symbolsPath);
        this.systemAwt  = loadSystemFont();
        LOG.info("[ul-fonts] secours prêt — emoji:{} symboles:{} systeme(canDisplay):{}",
                emojiPath != null, symbolsPath != null, systemAwt != null);
    }

    @Override
    public ULFontFile load(String family, int weight, boolean italic) {
        if (emojiPath != null && EMOJI_FAMILY.equalsIgnoreCase(family)) {
            if (!loggedLoadE) { LOG.info("[ul-fonts] load() police emoji"); loggedLoadE = true; }
            return ULFontFile.createFromFilePath(emojiPath);
        }
        if (symbolsPath != null && SYMBOLS_FAMILY.equalsIgnoreCase(family)) {
            if (!loggedLoadS) { LOG.info("[ul-fonts] load() police symboles"); loggedLoadS = true; }
            return ULFontFile.createFromFilePath(symbolsPath);
        }
        return delegate.load(family, weight, italic);
    }

    @Override
    public String getFallbackFontForCharacters(String chars, int weight, boolean italic) {
        if (chars != null) {
            for (int i = 0, n = chars.length(); i < n; ) {
                int cp = chars.codePointAt(i);
                String fam = pick(cp);
                if (fam != null) {
                    if (loggedCps.size() < 600 && loggedCps.add(cp)) {
                        LOG.info("[ul-fonts] U+{} → {}", hex(cp), fam);
                    }
                    return fam;
                }
                i += Character.charCount(cp);
            }
        }
        return delegate.getFallbackFontForCharacters(chars, weight, italic);
    }

    @Override
    public String getFallbackFont() {
        return delegate.getFallbackFont();
    }

    /**
     * Renvoie la famille embarquée à utiliser pour {@code cp}, ou {@code null} pour déléguer.
     * Règle : on ne détourne JAMAIS ce qu'Arial sait déjà rendre (ASCII, latin accentué,
     * flèches simples…). Sinon, on prend la police embarquée qui possède le glyphe.
     */
    private String pick(int cp) {
        if (cp < 0x00A1) return null;                                  // ASCII / contrôles → Arial
        // Espaces (NBSP, espace fine insécable U+202F, etc.) et caractères de format :
        // jamais détournés — sinon une police symbole leur donne une largeur d'em → « espace en trop »
        // (notamment entre les groupes de chiffres formatés à la française).
        if (Character.isSpaceChar(cp) || Character.getType(cp) == Character.FORMAT) return null;
        if (systemAwt != null) {
            if (systemAwt.canDisplay(cp)) return null;                 // Arial sait le faire → déléguer
        } else {
            // Sans info système, ne détourner que l'astral (emoji sûrs) pour éviter de voler du texte.
            if (cp < 0x1F000) return null;
        }
        if (emojiPath   != null && emojiAwt   != null && emojiAwt.canDisplay(cp))   return EMOJI_FAMILY;
        if (symbolsPath != null && symbolsAwt != null && symbolsAwt.canDisplay(cp)) return SYMBOLS_FAMILY;
        return null;
    }

    private static String hex(int cp) { return Integer.toHexString(cp).toUpperCase(); }

    private static Font awtFromPath(String path) {
        if (path == null) return null;
        try { return Font.createFont(Font.TRUETYPE_FONT, new File(path)); }
        catch (Throwable t) { LOG.warn("[ul-fonts] AWT police KO ({}): {}", path, t.getMessage()); return null; }
    }

    /** Charge la police de secours système (Arial) pour interroger sa couverture. */
    private Font loadSystemFont() {
        // 1) chemin exact résolu par le loader système
        try {
            String fam = delegate.getFallbackFont();
            ULFontFile ff = delegate.load(fam, 400, false);
            if (ff != null && ff.getFilePath() != null) {
                Font f = awtFromPath(ff.getFilePath());
                if (f != null) return f;
            }
        } catch (Throwable ignored) {}
        // 2) Arial Windows
        try {
            File arial = new File(System.getenv("SystemRoot") + "/Fonts/arial.ttf");
            if (arial.isFile()) return Font.createFont(Font.TRUETYPE_FONT, arial);
        } catch (Throwable ignored) {}
        // 3) police logique
        try { return new Font("Arial", Font.PLAIN, 12); } catch (Throwable ignored) {}
        return null;
    }

    /** Extrait une police embarquée vers un fichier temporaire ; renvoie son chemin absolu (ou null). */
    private static String extract(String res, String tag) {
        try (InputStream in = EmojiFallbackFontLoader.class.getResourceAsStream(res)) {
            if (in == null) { LOG.warn("[ul-fonts] Ressource introuvable : {}", res); return null; }
            Path tmp = Files.createTempFile("abysse-" + tag + "-", ".ttf");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            return tmp.toAbsolutePath().toString();
        } catch (Exception e) {
            LOG.warn("[ul-fonts] Extraction police {} échouée : {}", tag, e.getMessage());
            return null;
        }
    }
}
