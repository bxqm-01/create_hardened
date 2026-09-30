package dev.createhardener.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import net.createmod.catnip.data.Iterate;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import com.simibubi.create.AllSpriteShifts;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.fluids.tank.FluidTankCTBehaviour;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.CTSpriteShifter;

import dev.createhardener.CreateHardener;

/**
 * 硬化流体储罐的**运行期模型层**：连接纹理 + 原版那套面筛选（2026-09-26 补齐）。
 *
 * <h2>为什么别用原版的 {@code FluidTankModel}</h2>
 * 它的构造器是 {@code private}，公开工厂 {@code FluidTankModel.standard(baked)} 内部把三个 sprite shift
 * **写死成 Create 自己的**（{@code AllSpriteShifts.FLUID_TANK*}），而
 * {@code FluidTankCTBehaviour.getShift} 是按"贴图对象是否等于 {@code getOriginal()}"来选的 ——
 * 我们的贴图不是那些对象，**套上也不会生效**。所以只继承 {@code CTModel}（构造器 public），
 * 把偏移换成指向我们自己贴图的版本。
 *
 * <h2>为什么要复刻 {@code getQuads}（用户实测：单块/2×2 正常，3×3 只显示一个方块）</h2>
 * 原版的行为是（javap 逐行核对过，**收尾那一趟最容易漏**）：
 * <pre>
 *   dir != null → 返回空表（挡掉渲染器的逐方向遍历：多方块时那趟会被原版的邻居遮挡判定误砍）
 *   dir == null → ① 把 Iterate.directions（= Direction.values()，6 个真实方向、不含 null）逐桶并起来，
 *                    跳过"该方向的邻居是同类相连方块"的桶（多块接缝剔除）
 *                 ② 再补一趟 dir == null 的结果 —— 落到 SimpleBakedModel 的 unculledFaces
 * </pre>
 * <p>② 绝对不能省：凡带观察窗的模型（{@code block_*_window*}）几何**全部没有 cullface**
 * （Create 故意的 —— 窗口必须隔着邻居也画出来），那些面只能从 ② 出来。
 * 漏掉 ② 的实测症状：带窗模型几乎全丢 → "2×2 只剩顶面、各尺寸缺内壁底面"（2026-09-26 用户报回）。
 * 也就是说它**完全绕开原版的邻居遮挡判定**，自己按"是否相连"决定画哪些方向的面。
 * 多方块时相邻的格子全是同类方块，用原版判定就会把该方向的整组面（含观察窗与内壁）全砍掉 ——
 * 这正是"3×3 只显示一个方块"的来源。
 *
 * <p>⚠️ 原版的 {@code CullData} 是**包私有**类，我们用不了，所以这里用**自己的** {@link ModelProperty}
 * 装"被剔除的方向集合"，连通判断仍调 Create 公开的
 * {@link ConnectivityHandler#isConnected}，逻辑与原版一致。
 *
 * <p>⚠️ 前提：那三张 {@code *_connected} 必须被 stitching 进方块图集，故另有
 * {@code assets/minecraft/atlases/blocks.json}（Create 也用这个文件把它的 {@code _connected} 塞进去）。
 */
public class HardenedFluidTankModel extends CTModel {

    /** 替代原版包私有的 {@code FluidTankModel.CULL_PROPERTY}：记录"哪些方向该剔除"。 */
    private static final ModelProperty<Set<Direction>> CULLED = new ModelProperty<>();

    /** 惰性构建：首次烘焙时才会用到，此时图集已 stitching 完成。 */
    private static CTSpriteShiftEntry shiftSide;
    private static CTSpriteShiftEntry shiftTop;
    private static CTSpriteShiftEntry shiftInner;

    public HardenedFluidTankModel(BakedModel original) {
        super(original, new FluidTankCTBehaviour(side(), top(), inner()));
    }

    // ------------------------------------------------------------ 面筛选（照抄原版 FluidTankModel）

    @Override
    protected ModelData.Builder gatherModelData(ModelData.Builder builder, BlockAndTintGetter level, BlockPos pos,
                                                BlockState state, ModelData data) {
        super.gatherModelData(builder, level, pos, state, data);
        Set<Direction> culled = EnumSet.noneOf(Direction.class);
        // ⚠️ 必须用 horizontalDirections（**水平 4 向**），不能用 directions（全 6 向）！
        //    原版这里就是 `getstatic Iterate.horizontalDirections` —— 也就是说 **UP/DOWN 永远不参与接缝剔除**。
        //    我第一版误用了 6 向，于是 DOWN 也去调 isConnected（脚下是地面也会被判成"相连"），
        //    结果**单个 / 4 格 / 9 格储罐的底面全被剔掉**（2026-09-26 用户实测报回）。
        for (Direction direction : Iterate.horizontalDirections) {
            // 该方向的邻居若是"同类相连"的储罐（同一多方块），这一面就是接缝，剔除
            if (ConnectivityHandler.isConnected(level, pos, pos.relative(direction))) {
                culled.add(direction);
            }
        }
        return builder.with(CULLED, culled);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random,
                                    ModelData data, RenderType renderType) {
        // 原版把"画哪些面"完全收到无方向那一趟里决定，带方向时一律返回空表
        if (direction != null) {
            return Collections.emptyList();
        }
        Set<Direction> culled = data.get(CULLED);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction d : Iterate.directions) {
            if (culled != null && culled.contains(d)) {
                continue;
            }
            quads.addAll(super.getQuads(state, d, random, data, renderType));
        }
        // ⚠️ 收尾这一趟原版 FluidTankModel 也有（javap offset 103..122），漏了就丢带观察窗的几何：
        //    dir == null 落到 SimpleBakedModel 的 unculledFaces。
        quads.addAll(super.getQuads(state, null, random, data, renderType));
        return quads;
    }

    // ------------------------------------------------------------ 连接纹理的贴图偏移

    private static synchronized CTSpriteShiftEntry side() {
        if (shiftSide == null) {
            shiftSide = CTSpriteShifter.getCT(AllSpriteShifts.FLUID_TANK.getType(),
                    texture("hardened_fluid_tank"), texture("hardened_fluid_tank_connected"));
        }
        return shiftSide;
    }

    private static synchronized CTSpriteShiftEntry top() {
        if (shiftTop == null) {
            shiftTop = CTSpriteShifter.getCT(AllSpriteShifts.FLUID_TANK_TOP.getType(),
                    texture("hardened_fluid_tank_top"), texture("hardened_fluid_tank_top_connected"));
        }
        return shiftTop;
    }

    private static synchronized CTSpriteShiftEntry inner() {
        if (shiftInner == null) {
            shiftInner = CTSpriteShifter.getCT(AllSpriteShifts.FLUID_TANK_INNER.getType(),
                    texture("hardened_fluid_tank_inner"), texture("hardened_fluid_tank_inner_connected"));
        }
        return shiftInner;
    }

    /** 方块图集里的 sprite 位置（不含 {@code textures/} 与 {@code .png}）。 */
    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(CreateHardener.MODID, "block/" + name);
    }
}
