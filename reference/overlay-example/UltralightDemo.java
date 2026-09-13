package net.ostore.ultralight;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EXEMPLE (non compilé) — enregistre la touche U pour ouvrir {@link UltralightDemoScreen}.
 */
final class UltralightDemo {

    private static final Logger LOG = LoggerFactory.getLogger("ultralight/demo");

    private UltralightDemo() {}

    static void init() {
        KeyMapping key = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ultralight.demo", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_U,
                KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.consumeClick()) client.setScreenAndShow(new UltralightDemoScreen());
        });
        LOG.info("[ul-demo] Touche U → ouvre la démo Ultralight interactive.");
    }
}
