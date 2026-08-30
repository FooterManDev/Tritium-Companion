package io.github.tritium_launcher.tritiumcompanion.mixin;

import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OptionInstance.class)
public interface OptionInstanceAccessor {
    @Accessor("tooltip")
    OptionInstance.TooltipSupplier<?> tooltip();
}
