package dev.createhardener;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 硬化块楼梯。朝向/形状/半高等由原版 StairBlock 负责。 */
public class HardeningStairBlock extends StairBlock
        implements WeatheringCopper, dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback {

    private final int stage;

    public HardeningStairBlock(BlockState baseState, Properties properties, int stage) {
        super(baseState, properties);
        this.stage = stage;
    }

    /** 基材用原版同类方块状态，避免自定义属性缺失导致构造失败。 */
    public static HardeningStairBlock create(Properties properties, int stage) {
        return new HardeningStairBlock(Blocks.OAK_STAIRS.defaultBlockState(), properties, stage);
    }

    @Override
    public WeatherState getAge() {
        // one-to-one with vanilla WeatherState, so the ChangeOverTimeBlock neighbour stats work
        return Hardening.ageOf(this.stage);
    }

    @Override
    public Optional<BlockState> getNext(BlockState state) {
        // ⚠️ 必须搬同名属性，否则楼梯风化后 FACING/HALF/SHAPE 全被打回默认（朝向乱掉）、
        //    水浸的还会丢 WATERLOGGED。见 HardeningFullBlock 同处注释（2026-09-26 修）。
        return Hardening.nextOf(HardeningStages.Family.STAIR, this.stage)
                .map(next -> HardeningStates.copySharedProperties(state, next));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return HardeningInteraction.tryRestore(HardeningStages.Family.STAIR, this.stage, state,
                stack, level, pos, player);
    }

    /**
     * MUST implement these two ourselves: vanilla defines randomTick / isRandomlyTicking on the
     * CONCRETE copper block classes (e.g. WeatheringCopperFullBlock), NOT on the WeatheringCopper
     * interface. A third-party block that only does "extends Block implements WeatheringCopper"
     * therefore never gets scheduled for random ticks -> it never weathers.
     * Vanilla isRandomlyTicking also consults the ImmutableBiMap NEXT_BY_BLOCK (vanilla copper only),
     * so copying that implementation would return false for our blocks forever.
     */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return Hardening.isRandomlyTicking(this.stage);
    }

    /** Random tick: advance one weathering stage. */
    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level,
                              BlockPos pos, net.minecraft.util.RandomSource random) {
        Hardening.randomTickWeathering(this, this.stage, state, level, pos, random);
    }

    /**
     * 被物理化后，与其他物体碰撞时额外补一次风化判定（见 {@link HardeningCollisionCallback}）。
     *
     * <p>⚠️ 一旦实现这个接口，Sable 那条"数据包 {@code sable:fragile} 分支"对我们就失效了 ——
     * {@code BlockWithSubLevelCollisionCallback.sable$getCallback(state)} 是先判 {@code instanceof} 的。
     * 所以以后要给脆化档做"撞击碎裂"，必须在本回调里转调 Sable 的 {@code FragileBlockCallback.INSTANCE}，
     * 只写数据包不会生效。
     */
    @Override
    public dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback sable$getCallback() {
        return HardeningCollisionCallback.INSTANCE;
    }
}