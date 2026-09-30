package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **烧红的硬化块**（{@code hot_hardened_block}，整砖）—— 硬化块"刚出炉"的形态。
 *
 * <p>发光 12、站上去烫伤、背包里烫手（见 {@link HotHardenedBlockItem}）；冷却出口见 {@link HotCuring}
 * （用户 2026-09-28 定稿：注水→普通硬化块、方块遇水→微裂、遇冰→风化、自然冷却→普通硬化块）。
 * 半砖 / 楼梯见 {@link HotHardenedSlabBlock} / {@link HotHardenedStairBlock}；三者共用 {@link HotCuring} 的逻辑。
 */
public class HotHardenedBlock extends Block {

    /** 自然冷却刻数（保留这个名字是为了兼容旧引用，实际值在 {@link HotCuring#COOL_TICKS}）。 */
    public static final int COOL_TICKS = HotCuring.COOL_TICKS;

    public HotHardenedBlock(Properties properties) {
        super(properties);
    }

    /** 放下后开始计时；放下时就已经挨着水/冰的话立刻淬（不等邻居更新）。 */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state.is(oldState.getBlock())) {
            return;
        }
        level.scheduleTick(pos, this, HotCuring.COOL_TICKS);
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.FULL);
    }

    /** 定时到点：周围有水/冰就淬（微裂/风化），否则自然冷却回普通硬化块。 */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.FULL)) {
            HotCuring.coolNaturally(level, pos, HardeningStages.Family.FULL);
        }
    }

    /**
     * 周期性自查有没有水/冰。
     *
     * <p>⚠️ 只靠 {@link #neighborChanged} **不够**：用户实测"放进水里不会立刻变，必须有别的方块触发邻居更新才行"。
     * 原版混凝土粉末靠的是**随机刻**查水，所以这里照抄那个机制。
     */
    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.FULL);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.FULL);
        }
    }

    /** **必须用 `stepOn`**（原因见 {@link HotCuring#damageStandingOn}），照抄原版 {@code MagmaBlock}。 */
    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        HotCuring.damageStandingOn(level, entity);
        super.stepOn(level, pos, state, entity);
    }

    /** 兜底（见 {@link HotCuring#damageInside}）。 */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        super.entityInside(state, level, pos, entity);
        HotCuring.damageInside(level, entity);
    }
}
