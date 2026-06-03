package net.ostore.ultralight;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DÉMO TEMPORAIRE — enregistre la touche U pour ouvrir {@link UltralightDemoScreen}.
 * À supprimer après vérification (cette classe + l'appel dans {@code UltralightApiClient}).
 */
final class UltralightDemo {

    private static final Logger LOG = LoggerFactory.getLogger("abysse/ul-demo");

    private UltralightDemo() {}

    static void init() {
        KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ultralight.demo", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_U, KeyBinding.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.wasPressed()) client.setScreen(new UltralightDemoScreen());
        });
        LOG.info("[ul-demo] Touche U → ouvre la démo Ultralight interactive.");
    }
}
