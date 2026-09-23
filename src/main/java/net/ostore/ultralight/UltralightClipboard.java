package net.ostore.ultralight;

import com.mojang.blaze3d.systems.RenderSystem;
import me.ayydxn.luminescence.platform.ULClipboard;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Presse-papiers d'Ultralight branché sur celui de Minecraft (donc celui du système).
 *
 * <p>Sans lui, Ultralight n'a nulle part où lire ni écrire : Ctrl+C / Ctrl+V dans un champ d'une
 * page ne font rien.
 *
 * <p>Ultralight l'appelle en traitant un événement clavier, donc sur le render thread, où l'on
 * transmet les entrées. Le backend SDL de Minecraft n'est pas fait pour être appelé d'ailleurs :
 * un appel venu d'un autre thread est ignoré plutôt que de risquer l'état de la fenêtre. Aucune
 * exception ne doit remonter vers le code natif, qui ne la purgerait pas.
 */
final class UltralightClipboard implements ULClipboard {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/clipboard");

    @Override
    public void clear() { writePlainText(""); }

    @Override
    public String readPlainText() {
        if (!RenderSystem.isOnRenderThread()) return "";
        try {
            String text = Minecraft.getInstance().keyboardHandler.getClipboard();
            return text == null ? "" : text;
        } catch (Throwable t) {
            LOG.debug("[ul] lecture du presse-papiers impossible : {}", t.toString());
            return "";
        }
    }

    @Override
    public void writePlainText(String text) {
        if (!RenderSystem.isOnRenderThread()) return;
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text == null ? "" : text);
        } catch (Throwable t) {
            LOG.debug("[ul] écriture du presse-papiers impossible : {}", t.toString());
        }
    }
}
