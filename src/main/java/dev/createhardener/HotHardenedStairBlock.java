package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **烧红的硬化块·楼梯**（{@code hot_hardened_block_stairs}，2026-09-28 新增）。
 *
 * <p>行为与整砖一致，逻辑走 {@link HotCuring}。朝向/半高/形状由原版 {@link StairBlock} 负责；
 * 冷却成硬化块时用 {@link HardeningStates#copySharedProperties} 保住 `FACING`/`HALF`/`SHAPE`
 * 与水浸状态（不搬的话楼梯会突然转向 —— 风化那边栽过同样的坑）。
 */
public class HotHardenedStairBlock extends StairBlock {

    public HotHardenedStairBlock(BlockState baseState, Properties properties) {
        super(baseState, properties);
    }

    /**
     * 基材用**原版同类方块**的状态，避免自定义属性缺失导致构造失败
     * —— 与 {@link HardeningStairBlock#create} 的做法一致，别改成我们自己的方块状态。
     */
    public static HotHardenedStairBlock create(Properties properties) {
        return new HotHardenedStairBlock(Blocks.OAK_STAIRS.defaultBlockState(), properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level.isClientSide || state.is(oldState.getBlock())) {
            return;
        }
        level.scheduleTick(pos, this, HotCuring.COOL_TICKS);
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.STAIR);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.STAIR)) {
            HotCuring.coolNaturally(level, pos, HardeningStages.Family.STAIR);
        }
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.STAIR);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            HotCuring.coolIfQuenched(level, pos, HardeningStages.Family.STAIR);
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
