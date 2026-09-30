package dev.createhardener;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 硬化块（整砖）。
 *
 * <p>风化推进**由本类自己实现** {@link #isRandomlyTicking(BlockState)} + {@link #randomTick}，
 * 而不是"复用 {@code ChangeOverTimeBlock} 的默认 randomTick" —— **原版根本没有那个默认实现**：
 * 那两个方法定义在具体的铜方块类上，接口里没有，所以第三方方块必须自己写（否则永不风化）。
 * （本注释过去写反了，导致排查多轮走偏，2026-09-24 改正。）
 * 本类只额外覆写 {@link #getNext(BlockState)} 指向自家阶段表 —— 原版 {@code NEXT_BY_BLOCK}
 * 是 ImmutableBiMap，第三方无法注册。不覆写 createBlockStateDefinition，状态空间与原版一致。
 */
public class HardeningFullBlock extends Block
        implements WeatheringCopper, dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback {

    private final int stage;

    public HardeningFullBlock(Properties properties, int stage) {
        super(properties);
        this.stage = stage;
    }

    @Override
    public WeatherState getAge() {
        // one-to-one with vanilla WeatherState, so the ChangeOverTimeBlock neighbour stats work
        return Hardening.ageOf(this.stage);
    }

    @Override
    public Optional<BlockState> getNext(BlockState state) {
        // ⚠️ 必须把原状态的同名属性搬过去（等价原版 Block.withPropertiesOf）——
        //    否则风化后朝向/形状/水浸全丢（2026-09-26 修：楼梯会转回默认朝向、水浸方块的水会消失）。
        //    这样才与黑曜石粉恢复那条路径（HardeningInteraction）口径一致。
        return Hardening.nextOf(HardeningStages.Family.FULL, this.stage)
                .map(next -> HardeningStates.copySharedProperties(state, next));
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        return HardeningInteraction.tryRestore(HardeningStages.Family.FULL, this.stage, state,
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