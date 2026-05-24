package io.github.tritium_launcher.tritiumcompanion.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.tritium_launcher.tritiumcompanion.Common;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class RegistryIconRenderer {
    private static final int ICON_SIZE = 512;
    private static final float GUI_FAR_PLANE = 21000.0f;
    private static RenderTarget target;

    private static void ensureTarget() {
        if (target == null) {
            target = new TextureTarget(ICON_SIZE, ICON_SIZE, true, Minecraft.ON_OSX);
        }
    }

    public static void renderIcons(Map<ItemStack, Path> tasks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Set<Path> outputDirectories = new HashSet<>();
        for (Path outputPath : tasks.values()) {
            outputDirectories.add(outputPath.getParent());
        }

        for (Path outputDirectory : outputDirectories) {
            try {
                Files.createDirectories(outputDirectory);
            } catch (IOException e) {
                Common.LOGGER.error("Failed to create icon output directory {}", outputDirectory, e);
            }
        }

        CountDownLatch latch = new CountDownLatch(1);

        RenderSystem.recordRenderCall(() -> {
            try {
                ensureTarget();
                ItemRenderer itemRenderer = mc.getItemRenderer();

                for (Map.Entry<ItemStack, Path> entry : tasks.entrySet()) {
                    ItemStack stack = entry.getKey();
                    Path outputPath = entry.getValue();

                    renderSingleIcon(mc, itemRenderer, stack, outputPath);
                }
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void renderSingleIcon(Minecraft mc, ItemRenderer itemRenderer, ItemStack stack, Path outputPath) {
        target.bindWrite(true);
        target.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);

        RenderSystem.viewport(0, 0, ICON_SIZE, ICON_SIZE);
        RenderSystem.setProjectionMatrix(
                new Matrix4f().setOrtho(0.0f, ICON_SIZE, ICON_SIZE, 0.0f, 1000.0f, GUI_FAR_PLANE),
                VertexSorting.ORTHOGRAPHIC_Z
        );

        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().translation(0.0f, 0.0f, 10000.0f - GUI_FAR_PLANE);
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Lighting.setupFor3DItems();

        PoseStack poseStack = new PoseStack();
        float scale = ICON_SIZE;
        
        poseStack.pushPose();
        poseStack.translate(ICON_SIZE / 2.0f, ICON_SIZE / 2.0f, 150.0f);
        poseStack.scale(scale, -scale, scale);

        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        BakedModel model = itemRenderer.getModel(stack, mc.level, mc.player, 0);

        itemRenderer.render(
                stack,
                ItemDisplayContext.GUI,
                false,
                poseStack,
                bufferSource,
                15728880,
                OverlayTexture.NO_OVERLAY,
                model
        );

        bufferSource.endBatch();
        poseStack.popPose();

        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        target.unbindWrite();

        try (NativeImage image = takeScreenshot(target)) {
            image.flipY();
            image.writeToFile(outputPath);
        } catch (IOException e) {
            Common.LOGGER.error("Failed to save icon to {}", outputPath, e);
        }
    }

    private static NativeImage takeScreenshot(RenderTarget target) {
        NativeImage nativeImage = new NativeImage(target.width, target.height, false);
        RenderSystem.bindTexture(target.getColorTextureId());
        nativeImage.downloadTexture(0, false);
        return nativeImage;
    }
}
