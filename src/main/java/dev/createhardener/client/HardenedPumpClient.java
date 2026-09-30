package dev.createhardener.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import dev.createhardener.CreateHardener;

/**
 * 硬化流体泵的客户端接线 —— **只这一件事**。
 *
 * <ol>
 *   <li><b>泵的齿轮渲染器</b>：注册我们自己的 {@link HardenedPumpRenderer}（只换模型，转动/转速/超应力
 *       的逻辑全部复用原版公开静态方法）。</li>
 *   <li><b>提前初始化</b>：它的部件模型（Flywheel {@code PartialModel}）**必须在模型烘焙之前**创建，
 *       否则解析不到 → 齿轮渲染成黑紫的缺失模型方块（beta80 实测）。</li>
 * </ol>
 *
 * <p>历史：本类原名 {@code HardenedPipeClient}，当时还管着硬化管道的 {@code cutout} 渲染层与
 * 管口贴图包装器（{@code HardenedPipeAttachmentModel}）—— 2026-09-27 用户决定**整套删除硬化管道**
 * （理由：原版管道不产生量、也不设上限，做专用管道没有必要），这两块随之一起删掉。
 * 现在文件里已经没有任何"管道"相关代码，故改名为 {@code HardenedPumpClient}。
 */
@EventBusSubscriber(modid = CreateHardener.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class HardenedPumpClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 触发 HardenedPumpRenderer 的类初始化，让 HARDENED_COG 早于模型烘焙建立（见上面的说明）。
        HardenedPumpRenderer.init();
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                CreateHardener.HARDENED_FLUID_PUMP_BE.get(), HardenedPumpRenderer::new);
    }
}
