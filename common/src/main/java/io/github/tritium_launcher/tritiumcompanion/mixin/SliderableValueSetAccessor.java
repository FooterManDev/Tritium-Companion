package io.github.tritium_launcher.tritiumcompanion.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.client.OptionInstance$SliderableValueSet")
public interface SliderableValueSetAccessor
{
    @Invoker("fromSliderValue")
    Object invokeFromSliderValue(double sliderValue);
}
