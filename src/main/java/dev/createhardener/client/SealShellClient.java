package dev.createhardener.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import dev.createhardener.CreateHardener;

/** 客户端注册：外壳方块实体的渲染器。 */
@EventBusSubscriber(modid = CreateHardener.MODID, value = Dist.CLIENT)
public final class SealShellClient {

    private SealShellClient() {
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CreateHardener.SEAL_SHELL_BE.get(), SealShellRenderer::new);
    }
}
