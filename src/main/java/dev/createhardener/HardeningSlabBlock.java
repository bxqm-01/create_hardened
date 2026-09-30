package dev.createhardener;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** 硬化块半砖。 */
public class HardeningSlabBlock extends SlabBlock
        implements WeatheringCopper, dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback {

    private final int stage;

    public HardeningSlabBlock(Properties properties, int stage) {
        super(properties);
        this.stage = stage;
    }

    @Override
    public WeatherState getAge() {
        // one-to-one with vanilla WeatherState, so the ChangeOverTimeBlock neighbour stats work
        return Hardening.ageOf(this.stage);
    }

    /**
     * 风化时：**先搬同名属性、再把 double 收敛成单砖**。
     *
     * <p>顺序不能反 —— 先收敛再搬运的话，TYPE 又会被源状态的 DOUBLE 覆盖回来。
     * 搬运是为了保住 WATERLOGGED（否则水浸半砖风化后那格的水会凭空消失）与 TYPE 的上下形态。
     */
    @Override
    public Optional<BlockState> getNext(BlockState state) {
        return Hardening.nextOf(HardeningStages.Family.SLAB, this.stage)
                .map(next -> HardeningStates.copySharedProperties(state, next))
                .map(Hardening::toSingleSlab);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return HardeningInteraction.tryRestore(HardeningStages.Family.SLAB, this.stage, state,
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