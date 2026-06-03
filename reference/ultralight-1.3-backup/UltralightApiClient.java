package net.ostore.ultralight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;

@Environment(EnvType.CLIENT)
public final class UltralightApiClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        UltralightEngine.init();
    }
}
