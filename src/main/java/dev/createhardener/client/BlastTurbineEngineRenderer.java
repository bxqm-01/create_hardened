package dev.createhardener.client;

import com.mojang.blaze3d.vertex.PoseStack;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import dev.createhardener.BlastTurbineEngineBlockEntity;

/**
 * 冲爆引擎**输出面那半根传动轴**的渲染器。
 *
 * <h2>抄的就是原版创造马达</h2>
 * 原版 {@code CreativeMotorRenderer} 只覆写了一个 {@code getRotatedModel(...)}：
 * {@code CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state)} ——
 * 其余旋转、转速、超应力表现全部由 {@code KineticBlockEntityRenderer} 负责。
 * 我们照抄这一句（{@code SHAFT_HALF} 是"朝南的半根轴"，{@code partialFacing} 会把它转到方块
 * {@code FACING} 那一侧；而我们的 {@code FACING} 就是输出面，所以位置天然正确）。
 *
 * <h2>为什么不继承 {@code KineticBlockEntityRenderer}</h2>
 * 因为装了 Flywheel 的实例里 {@code KineticBlockEntityRenderer.renderSafe} 的**第一句**
 * 就是 {@code if (VisualizationManager.supportsVisualization(level)) return;} ——
 * 那是给 Flywheel visual 让路；我们没给方块实体注册 visual，继承它只会**什么都不画**
 * （beta79 的齿轮就是这么消失的）。所以改用**普通** {@code BlockEntityRenderer}，
 * 直接调原版**公开静态**的 {@link KineticBlockEntityRenderer#renderRotatingBuffer} ——
 * 转动逻辑 100% 复用原版，我们只负责"用哪个模型"。
 */
public class BlastTurbineEngineRenderer implements BlockEntityRenderer<BlastTurbineEngineBlockEntity> {

    public BlastTurbineEngineRenderer(BlockEntityRendererProvider.Context context) {
        // 不需要 context：模型走 CachedBuffers，旋转走原版静态方法
    }

    @Override
    public void render(BlastTurbineEngineBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state);
        KineticBlockEntityRenderer.renderRotatingBuffer(be, shaft, poseStack,
                buffers.getBuffer(RenderType.solid()), light);
    }
}
