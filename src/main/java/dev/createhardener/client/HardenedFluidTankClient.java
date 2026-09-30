package dev.createhardener.client;

import com.simibubi.create.content.fluids.tank.FluidTankRenderer;
import com.simibubi.create.foundation.model.ModelSwapper;

import dev.createhardener.CreateHardener;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 硬化流体储罐的客户端装配。三件事，缺一不可：
 *
 * <ol>
 *   <li><b>方块实体渲染器</b> —— 复用原版 {@link FluidTankRenderer}（画罐里的液面）；
 *       不注册的话罐子摆出来是空的（原版只给 {@code AllBlockEntityTypes.FLUID_TANK} 注册了）。</li>
 *   <li><b>渲染层</b> —— 必须显式设成 {@code cutout}。⚠️ 2026-09-26 用户实测"开窗后看不到内壁、
 *       也看不到液体"的**真正原因**：原版和我们的模型 JSON **都没有 {@code render_type} 字段**
 *       （顶层键只有 credit/parent/textures/elements/groups），说明这个层是**代码**设的；
 *       我们的方块没设 → 走默认的 {@code solid} → **观察窗贴图里那些全透明像素被写成不透明**，
 *       于是内壁和液面全被那层"变黑的窗"挡住。（模型的透明像素是**二值 alpha**：
 *       68.8% 全透明 + 31.2% 全不透明，所以 {@code cutout} 就是正确的层。）</li>
 *   <li><b>模型包装（连接纹理）</b> —— 在烘焙结果里把储罐模型换成 {@link HardenedFluidTankModel}
 *       （自己写的 {@code CTModel} 子类）。用的是 Create 公开静态工具
 *       {@link ModelSwapper#swapModels}，没有改 Create 任何代码。</li>
 * </ol>
 */
@EventBusSubscriber(modid = CreateHardener.MODID, value = Dist.CLIENT)
public final class HardenedFluidTankClient {

    private HardenedFluidTankClient() {
    }

    /** 储罐的观察窗有透明像素，必须走 cutout 层（见类注释第 2 条）。 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemBlockRenderTypes.setRenderLayer(
                CreateHardener.HARDENED_FLUID_TANK.get(), RenderType.cutout()));
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CreateHardener.HARDENED_FLUID_TANK_BE.get(), FluidTankRenderer::new);
    }

    /** 把储罐的烘焙模型换成带连接纹理的包装版（见类注释第 2 条）。 */
    @SubscribeEvent
    public static void wrapTankModels(ModelEvent.ModifyBakingResult event) {
        ModelSwapper.swapModels(
                event.getModels(),
                ModelSwapper.getAllBlockStateModelLocations(CreateHardener.HARDENED_FLUID_TANK.get()),
                HardenedFluidTankModel::new);
    }
}
