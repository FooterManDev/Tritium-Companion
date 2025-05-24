package io.github.footermandev.tritiumcompanion;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;

public class Warning
{
    public static boolean shouldWarn() {
        Path home = Path.of(System.getProperty("user.home"));
        return !Files.isDirectory(home.resolve("tritium"));
    }

    public static Component getWarningMsg() {
        return Component.literal(
                """
                Tritium Companion is a dev tool for Modpack Authors using Tritium Launcher.
                
                If this warning appears, you likely do not have Tritium installed. This mod is safe to remove.
                
                If this is not intentional, please ensure the "tritium" directory exists in your HOME directory.
                """
        ).withStyle(ChatFormatting.GOLD);
    }
}
