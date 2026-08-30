package io.github.tritium_launcher.tritiumcompanion;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

public interface FluidTintResolver {
    record Result(ResourceLocation stillTexture, int tintColor) {}

    Result resolve(Fluid fluid, FluidState state);

    FluidTintResolver NOOP = (fluid, state) -> null;
}
