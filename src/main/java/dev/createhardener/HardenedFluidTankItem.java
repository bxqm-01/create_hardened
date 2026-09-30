package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandItem;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

/**
 * 硬化流体储罐的物品形态 —— 补上原版那套**「往整层上一放，整层自动铺满」**的放置行为。
 *
 * <h2>为什么必须自己写：原版 {@code FluidTankItem.tryMultiPlace} 对我们无效</h2>
 * 原版的储罐物品是 {@code FluidTankItem extends BlockItem}，靠私有的
 * {@code tryMultiPlace(BlockPlaceContext)} 实现自动铺层。但它内部把方块实体类型**写死成 Create 自己的**
 * （{@code AllBlockEntityTypes.FLUID_TANK} / {@code CREATIVE_FLUID_TANK} 二选一，再交给
 * {@code ConnectivityHandler.partAt(type, …)}）；而 {@code partAt} 里是 {@code be.getType() == type}
 * 的引用比较 → **对我们的罐子永远返回 null**，铺层直接早退。
 * 所以这里照抄一份，只把类型换成 {@link CreateHardener#HARDENED_FLUID_TANK_BE}，
 * 并去掉创造罐分支。宽度是**问我们的控制器要**（{@code controller.getWidth()}），
 * 因此 7×7 也自动跟着走，不需要在这里写死任何尺寸。
 *
 * <h2>原版语义（逐条照抄，别自己简化）</h2>
 * <ul>
 *   <li>**潜行时不触发** —— 潜行放置 = 老老实实只放一格（这是玩家控制"单放"的开关）；</li>
 *   <li>**只有点击面是上/下**（{@code face.getAxis().isVertical()}）才触发，点击侧面不铺；</li>
 *   <li>被放那格的**邻格（点击面的反面）必须是已有罐子**，否则不铺；</li>
 *   <li>玩家快捷栏里有**对称杖**时不动手（避免和对称放置打架）；</li>
 *   <li>现有控制器 **width ≤ 1 直接返回**（1×1 的"整层"就是刚放的那一格，没什么可铺）；</li>
 *   <li>**新层的 Y 必须正好贴住现有罐的顶/底** —— 也就是"接着整层往上/往下加"，斜着放不触发；</li>
 *   <li>先数这一层有几个空格：**只要有一格被不可替换的方块挡住，整层都不铺**（宁可不动，也不铺一半）；</li>
 *   <li>非创造模式下**手上数量不够铺满整层就整层不铺**；</li>
 *   <li>真正铺的时候逐格 {@code super.place(...)}（每次消耗 1 个），期间给玩家的持久数据打上
 *       {@code SilenceTankSound} 标记 —— 原版储罐实体见到这个标记就不重复播放放置音效。</li>
 * </ul>
 *
 * <p><b>与原版两处有意不同</b>：① 没有创造罐分支（我们只有普通罐）；
 * ② 没有覆写 {@code updateCustomBlockEntityTag}（原版那份只是清洗"带着罐子 NBT 的物品"，
 * 例如创造模式中键取出的满罐物品：删掉 {@code Size}/{@code Height}/{@code Controller} 等由连通性重算的字段、
 * 把 {@code TankContent} 钳到单格容量）。留着的是 {@link BlockItem} 的通用实现，够用；
 * 真要放"带内容的罐子物品"再说。
 */
public class HardenedFluidTankItem extends BlockItem {

    public HardenedFluidTankItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext ctx) {
        InteractionResult result = super.place(ctx);
        if (result.consumesAction()) {
            tryFillLayer(ctx);
        }
        return result;
    }

    /**
     * 对应原版私有的 {@code FluidTankItem.tryMultiPlace}（同名会让人误以为是覆写，故改叫 tryFillLayer）。
     */
    private void tryFillLayer(BlockPlaceContext ctx) {
        Player player = ctx.getPlayer();
        if (player == null) {
            return;
        }
        if (player.isShiftKeyDown()) {
            return;
        }
        Direction face = ctx.getClickedFace();
        if (!face.getAxis().isVertical()) {
            return;
        }
        ItemStack stack = ctx.getItemInHand();
        Level level = ctx.getLevel();
        // 原版这里的口径是"**放置目标格**"（已经含点击面偏移），不是被点的那一格
        BlockPos placedPos = ctx.getClickedPos();
        BlockPos anchor = placedPos.relative(face.getOpposite());
        if (!FluidTankBlock.isTank(level.getBlockState(anchor))) {
            return;
        }
        if (SymmetryWandItem.presentInHotbar(player)) {
            return;
        }

        FluidTankBlockEntity be =
                ConnectivityHandler.partAt(CreateHardener.HARDENED_FLUID_TANK_BE.get(), level, anchor);
        if (be == null) {
            return;
        }
        FluidTankBlockEntity controller = be.getControllerBE();
        if (controller == null) {
            return;
        }
        int width = controller.getWidth();
        if (width <= 1) {
            return;
        }

        BlockPos layer = face == Direction.DOWN
                ? controller.getBlockPos().below()
                : controller.getBlockPos().above(controller.getHeight());
        if (layer.getY() != placedPos.getY()) {
            return;
        }

        // 第一遍：数这一层还缺几格（有任何一格被挡住就整层放弃）
        int missing = 0;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                BlockState state = level.getBlockState(layer.offset(x, 0, z));
                if (FluidTankBlock.isTank(state)) {
                    continue;
                }
                if (!state.canBeReplaced()) {
                    return;
                }
                missing++;
            }
        }
        if (!player.isCreative() && stack.getCount() < missing) {
            return;
        }

        // 第二遍：逐格放置（消耗物品、临时静音放置音效）
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                BlockPos pos = layer.offset(x, 0, z);
                if (FluidTankBlock.isTank(level.getBlockState(pos))) {
                    continue;
                }
                BlockPlaceContext at = BlockPlaceContext.at(ctx, pos, face);
                player.getPersistentData().putBoolean("SilenceTankSound", true);
                super.place(at);
                player.getPersistentData().remove("SilenceTankSound");
            }
        }
    }
}
