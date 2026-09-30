package dev.createhardener.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import dev.createhardener.CreateHardener;

/**
 * 冲爆引擎的客户端接线 —— 只有一件事：注册那半根传动轴的渲染器。
 *
 * <p>不需要"提前初始化部件模型"那一步：用的是 Create 自己的 {@code AllPartialModels.SHAFT_HALF}，
 * 它在模型烘焙期就由 Create 建好了（硬化泵当初要手动 {@code init()} 是因为那是**我们自己的** PartialModel）。
 */
@EventBusSubscriber(modid = CreateHardener.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class BlastTurbineEngineClient {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                CreateHardener.BLAST_TURBINE_ENGINE_BE.get(), BlastTurbineEngineRenderer::new);
    }
}
