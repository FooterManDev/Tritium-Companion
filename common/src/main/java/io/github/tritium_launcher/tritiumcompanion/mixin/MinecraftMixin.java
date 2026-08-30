package io.github.tritium_launcher.tritiumcompanion.mixin;

import io.github.tritium_launcher.tritiumcompanion.client.options.OptionsExporter;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Unique
    private static boolean tritium$optionsExported = false;

    @SuppressWarnings("unused")
    @Inject(method = "run", at = @At("HEAD"))
    private void tritium$onGameStart(CallbackInfo ci) {
        if (tritium$optionsExported) return;
        tritium$optionsExported = true;
        OptionsExporter.writeExport();
    }
}
