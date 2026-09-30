package dev.createhardener.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;

import dev.createhardener.CreateHardener;
import dev.createhardener.SealShellBlockEntity;
import dev.createhardener.SealShellBlocks;

/**
 * 塑封外壳的客户端渲染：**把外壳画成它冒充的那个方块**
 * —— 自己实现的完整方块伪装（原理同 Create copycat，但不依赖它）。
 *
 * <p>这里只做**材质层**：{@link BlockRenderDispatcher#renderSingleBlock} 直接画被套方块的原模型，
 * 所以套壳后方块外观完全不变（玻璃还是玻璃、竖半砖还是竖半砖）。
 *
 * <p><b>涂层不在这里画</b>：那层半透明覆盖改由 Catnip Outliner 的 {@code withFaceTexture} 负责
 * （见 {@code SealZoneClient} + {@code SealCoatings}），也就是 Create 强力胶的做法。
 * 原因：① 走描边那条路时**物理结构里也正常显示**，而方块实体渲染器在子世界里会被
 * {@code shouldRenderOffScreen} 之类的判定筛掉；② 只在"手持塑封剂"时显示这个条件与描边天然同源。
 */
public class SealShellRenderer implements BlockEntityRenderer<SealShellBlockEntity> {

    private final BlockRenderDispatcher blockRenderer;

    public SealShellRenderer(BlockEntityRendererProvider.Context context) {
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    /**
     * **必须覆写**，否则在航空学的物理结构（子世界）里整个人都不会被渲染。
     *
     * <p>原因：Sable 的 `VanillaSingleSubLevelRenderData` 会给每个方块实体先算一次
     * {@code BlockEntityRenderer.shouldRenderOffScreen(be)} 并记为 `singleBlockEntityGlobal` ——
     * 返回 false 的渲染器在子世界里被当作"只可能在自己的方块格子里显示"而被跳过。
     * （用户实测：套壳的方块被物理化后，那层半透明涂层完全看不到。）
     *
     * <p>本渲染器本来就要在**方块格之外**画东西（涂层放大到 1.02 凸出方块外），
     * 所以按原版惯例（潜影盒/方尖碑那类）返回 true。
     */
    @Override
    public boolean shouldRenderOffScreen(SealShellBlockEntity be) {
        return true;
    }

    @Override
    public void render(SealShellBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (be.getLevel() == null) {
            return;
        }
        BlockState material = be.material();
        if (material == null) {
            return;
        }

        // 只画"被套方块的原模型"（tint / 光照 / 遮挡全按原方块来）。
        //
        // ⚠️ 叠加涂层**不在这里画**：那层半透明覆盖改由 Catnip Outliner 的
        // `withFaceTexture` 负责（见 `SealZoneClient` + `SealCoatings`），
        // 也就是 Create 强力胶的做法。原因：① 走描边那条路时**物理结构里也正常显示**，
        // 而方块实体渲染器在子世界里会被 `shouldRenderOffScreen` 之类的判定筛掉；
        // ② 只在"手持塑封剂"时显示这个条件，与描边天然同源，不用两处各判一次。
        //
        // 另注：本方块自己的"外观"由 models/block/sealed_shell.json 的 particle 贴图决定
        // （sealed_structure_void）—— 那才是破坏粒子的来源；这里画的是**被冒充方块**的模型。
        blockRenderer.renderSingleBlock(material, poseStack, buffers, packedLight, packedOverlay);
    }

    /** 玩家主手或副手拿着塑封剂时为 true。 */
    public static boolean isHoldingSealant() {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        var sealant = CreateHardener.OBSIDIAN_SEALANT.get();
        return player.getMainHandItem().is(sealant) || player.getOffhandItem().is(sealant);
    }
}
