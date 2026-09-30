package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **烧红的硬化块·半砖**（{@code hot_hardened_block_slab}，2026-09-28 新增）。
 *
 * <p>行为与整砖完全一致，逻辑走 {@link HotCuring}（冷却出口、遇水/遇冰、站上烫伤都同一套）。
 * 形状/形态由原版 {@link SlabBlock} 负责；冷却成硬化块时用
 * {@link HardeningStates#copySharedProperties} 保住 `type`（上半砖/下半砖/双层）与水浸状态。
 */
public class HotHardenedSlabBlock extends SlabBlock {

    public HotHardenedSlabBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state.is(oldState.getBlock())) {
            return;
        }
        level.scheduleTick(pos, this, HotCuring.COOL_TICKS);
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.SLAB);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.SLAB)) {
            HotCuring.coolNaturally(level, pos, HardeningStages.Family.SLAB);
        }
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.SLAB);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.SLAB);
        }
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        HotCuring.damageStandingOn(level, entity);
        super.stepOn(level, pos, state, entity);
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        super.entityInside(state, level, pos, entity);
        HotCuring.damageInside(level, entity);
    }
}
