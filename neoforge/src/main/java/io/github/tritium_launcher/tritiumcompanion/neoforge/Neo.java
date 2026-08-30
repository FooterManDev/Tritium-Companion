package io.github.tritium_launcher.tritiumcompanion.neoforge;

import io.github.tritium_launcher.tritiumcompanion.Command;
import io.github.tritium_launcher.tritiumcompanion.FluidTintResolver;
import io.github.tritium_launcher.tritiumcompanion.TCompanion;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import static io.github.tritium_launcher.tritiumcompanion.TCompanion.MOD_ID;

@Mod(MOD_ID)
public final class Neo
{
    public Neo() {
        TCompanion.KUBE_JS = KubeJSDumper::dump;
        TCompanion.FLUID_TINT_RESOLVER = (fluid, state) -> {
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
            var still = ext.getStillTexture();
            int tint;
            try {
                tint = ext.getTintColor(state, null, null);
            } catch (Exception e) {
                tint = ext.getTintColor();
            }
            return new FluidTintResolver.Result(still, tint);
        };
        NeoForge.EVENT_BUS.addListener(Neo::onCommandRegister);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStarted);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStopping);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStopped);
        TCompanion.init();
    }

    public static void onCommandRegister(RegisterCommandsEvent e) {
        TCompanion.LOGGER.info("Registering command on NeoForge");
        Command.register(e.getDispatcher());
    }

    public static void onServerStarted(ServerStartedEvent e) {
        TCompanion.onServerStarted(e.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        TCompanion.onServerStopping(e.getServer());
    }

    public static void onServerStopped(ServerStoppedEvent e) {
        TCompanion.onServerStopping(e.getServer());
    }
}
