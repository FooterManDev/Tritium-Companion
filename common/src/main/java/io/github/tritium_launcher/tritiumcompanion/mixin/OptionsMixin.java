package io.github.tritium_launcher.tritiumcompanion.mixin;

import io.github.tritium_launcher.tritiumcompanion.client.options.OptionsExporter;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public class OptionsMixin {

    @SuppressWarnings("unused")
    @Inject(method = "save", at = @At("TAIL"))
    private void tritium$onSave(CallbackInfo ci) {
        OptionsExporter.writeExport();
    }
}

