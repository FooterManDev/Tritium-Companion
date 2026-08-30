package io.github.tritium_launcher.tritiumcompanion.patch;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.jspecify.annotations.NonNull;
import recipe.TDumpPatch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class FluidTintPatch implements TDumpPatch
{
    private volatile int waterColor = -1;
    private static final int DEFAULT_WATER_COLOR = 0x3F76E4;

    private static final String TARGET_FILE = "water_still.png";

    @Override
    public void apply(@NonNull Path snapshotDir, Object gameContext)
    {
        if (waterColor == -1)
        {
            waterColor = resolveWaterColor();
        }

        try (Stream<Path> walk = Files.walk(snapshotDir))
        {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(TARGET_FILE))
                    .forEach(path -> tintPng(path, waterColor));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to walk snapshot directory for fluid tint patch", e);
        }
    }

    private static int resolveWaterColor()
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null)
        {
            return mc.level.registryAccess()
                    .registry(Registries.BIOME)
                    .flatMap(reg -> Optional.ofNullable(reg.get(Biomes.OCEAN)))
                    .map(Biome::getWaterColor)
                    .orElse(DEFAULT_WATER_COLOR);
        }
        return DEFAULT_WATER_COLOR;
    }

    private static void tintPng(Path path, int tintRgb)
    {
        try
        {
            NativeImage image;
            try (InputStream in = Files.newInputStream(path))
            {
                image = NativeImage.read(in);
            }

            if (image.getWidth() <= 0 || image.getHeight() <= 0)
            {
                return;
            }

            int tr = (tintRgb >> 16) & 0xFF;
            int tg = (tintRgb >> 8)  & 0xFF;
            int tb =  tintRgb        & 0xFF;

            for (int y = 0; y < image.getHeight(); y++)
            {
                for (int x = 0; x < image.getWidth(); x++)
                {
                    int pixel = image.getPixelRGBA(x, y);
                    int a = (pixel >> 24) & 0xFF;
                    int b = (pixel >> 16) & 0xFF;
                    int g = (pixel >> 8)  & 0xFF;
                    int r =  pixel        & 0xFF;

                    r = r * tr / 255;
                    g = g * tg / 255;
                    b = b * tb / 255;

                    image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }

            image.writeToFile(path);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to tint " + path, e);
        }
    }
}
