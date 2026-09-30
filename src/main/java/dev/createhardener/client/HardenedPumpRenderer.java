package dev.createhardener.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.createhardener.CreateHardener;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;

/**
 * 硬化流体泵的**齿轮**渲染器。
 *
 * <h2>为什么不能直接继承原版 {@code PumpRenderer}（beta79 实测齿轮完全不显示的真因）</h2>
 * 原版 {@code KineticBlockEntityRenderer.renderSafe} 的**第一句**就是：
 * <pre>
 *   if (VisualizationManager.supportsVisualization(be.getLevel())) return;   // ← 直接返回
 * </pre>
 * 也就是说：**装了 Flywheel 的实例里，动能方块一律交给 Flywheel 的 visual 渲染，这条 legacy 的
 * BlockEntityRenderer 路径根本不执行**。原版泵之所以有齿轮，是因为 Create 给它的方块实体注册了
 * Flywheel visual；我们的泵没有 visual，于是什么都不画。
 *
 * <h2>这里的做法（照着原版复刻，但绕开 Flywheel 的接管）</h2>
 * 不继承那个基类（就不会吃到提前 return），实现一个**普通**的 {@link BlockEntityRenderer}，
 * 直接调用原版**公开静态**的 {@link KineticBlockEntityRenderer#renderRotatingBuffer} ——
 * 转动角、转速、超应力等旋转逻辑 **100% 复用原版**，我们只负责"用哪个模型"：
 * 用我们自己的 {@code createhardener:block/hardened_mechanical_pump/cog}（贴图已是 {@code hardened_pump}）。
 *
 * <p>⚠️ 若将来我们给这个方块实体注册了 Flywheel visual，这里要相应去掉，避免重复渲染。
 */
public class HardenedPumpRenderer implements BlockEntityRenderer<PumpBlockEntity> {

    /**
     * 我们的齿轮部件模型（惰性解析，烘焙完成后才有实体）。
     *
     * <p>⚠️ **必须在模型烘焙之前创建**（beta80 实测"有东西在转、但是黑紫方块"就是这里踩的坑）：
     * Flywheel 是在**模型烘焙那一刻**统一解析它已知的部件模型的；而这个静态字段如果等到
     * "世界加载、构造渲染器"时才初始化，就**晚于烘焙** → 部件解析不出来 → 退回缺失模型（黑紫方块）。
     * 所以 {@link HardenedPumpClient#onClientSetup} 里会显式调一次 {@link #init()} 把类提前初始化。
     */
    public static final PartialModel HARDENED_COG = PartialModel.of(
            ResourceLocation.fromNamespaceAndPath(CreateHardener.MODID,
                    "block/hardened_mechanical_pump/cog"));

    /** 只为**触发类初始化**（让上面的部件模型早于模型烘焙被创建）——见 {@link #HARDENED_COG} 的说明。 */
    public static void init() {
        // 有意留空：调用即加载本类，静态字段随之创建
    }

    public HardenedPumpRenderer(BlockEntityRendererProvider.Context context) {
        // 不需要 context 里的任何东西：模型走 CachedBuffers，旋转走原版静态方法
    }

    @Override
    public void render(PumpBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        SuperByteBuffer cog = CachedBuffers.partialFacing(HARDENED_COG, state);
        KineticBlockEntityRenderer.renderRotatingBuffer(be, cog, poseStack,
                buffers.getBuffer(RenderType.cutoutMipped()), light);
    }
}
