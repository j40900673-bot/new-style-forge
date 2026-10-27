package dev.styleforge;

import dev.styleforge.style.PackSync;
import dev.styleforge.style.StyleManager;
import dev.styleforge.ui.StylesScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class StyleForgeClient implements ClientModInitializer {
    public static final String MOD_ID = "styleforge";

    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        StyleManager.init();
        PackSync.init();

        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.styleforge.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KeyBinding.Category.create(Identifier.of(MOD_ID, "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new StylesScreen(null));
                }
            }
        });
    }
}
