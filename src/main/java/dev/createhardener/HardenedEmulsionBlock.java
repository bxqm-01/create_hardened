package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * 硬化乳浊液的**液体方块** —— 流体本体只是"会流动的液体"，所有"碰到它会怎样"都在这里。
 *
 * <p><b>用户 2026-09-26 要求的特性</b>（原话）："现在我们的流体会导致玩家受伤，同时使玩家燃烧就像岩浆那样的效果……
 * 然后使我们的液体与水在一起时会生成深板岩，与岩浆在一起时，则会生成遗骸沉积岩"。
 * <ul>
 *   <li>{@link #entityInside} —— 像岩浆一样：持续伤害 + 点燃（外加烟雾粒子，观感对齐岩浆）；</li>
 *   <li>{@link #onPlace} + {@link #neighborChanged} —— 与**水**相邻 → 自己变成**深板岩**；
 *       与**岩浆**相邻 → 自己变成**遗骸沉积岩**。⚠️ **两个入口缺一不可**（见下方说明）。</li>
 * </ul>
 *
 * <p><b>为什么不像原版那样走 {@code LiquidBlock.shouldSpreadLiquid}</b>：那个方法是 **private**，
 * 而且只在"本流体属于 {@code #minecraft:lava} 标签"时才把水变成黑曜石/圆石。
 * 我们的流体**不在** lava 标签里（否则原版会先把自己变成黑曜石，就轮不到我们的规则了），
 * 所以改成在 {@code onPlace} + {@code neighborChanged} 两个入口里自己检测相邻流体并转换 ——
 * 这样"水变深板岩、岩浆变遗骸沉积岩"两条规则都归我们管，也不会和原版规则打架。
 */
public class HardenedEmulsionBlock extends LiquidBlock {

    /** 伤害间隔（tick）：与岩浆一致（每 10 tick 一次）。 */
    private static final int DAMAGE_INTERVAL = 10;
    /** 单次伤害：与岩浆一致。 */
    private static final float DAMAGE = 4.0F;
    /** 点燃秒数：与岩浆一致。1.21.1 的点火方法名是 {@code igniteForSeconds(float)}。 */
    private static final float FIRE_SECONDS = 15.0F;

    public HardenedEmulsionBlock(FlowingFluid fluid, Properties properties) {
        super(fluid, properties);
    }

    // ------------------------------------------------------------ 碰到会受伤 / 燃烧

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        super.entityInside(state, level, pos, entity);
        if (level.isClientSide || !(entity instanceof LivingEntity living)) {
            return;
        }
        // 与岩浆同款：每 tick 尝试一次（原版岩浆也**不看**无敌帧，靠伤害源自身限制频率），
        // 伤害值 4、点燃 15 秒 —— 都对齐岩浆。
        living.hurt(level.damageSources().lava(), DAMAGE);
        living.igniteForSeconds(FIRE_SECONDS);

        // 冒烟（观感对齐岩浆）
        if (level instanceof ServerLevel serverLevel && level.random.nextInt(4) == 0) {
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.LARGE_SMOKE,
                    pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                    1, 0.25D, 0.1D, 0.25D, 0.02D);
        }
    }

    // ------------------------------------------------------------ 与水 / 岩浆相遇

    /**
     * 流体相遇转换。规则（用户指定）：
     * <ul>
     *   <li>相邻（含下方）有**水** → 本格变成**深板岩**；</li>
     *   <li>相邻（含下方）有**岩浆** → 本格变成**遗骸沉积岩**；</li>
     *   <li>两者都有时，**岩浆优先**（遗骸沉积岩更"贵"，且岩浆现场通常也伴随水）。</li>
     * </ul>
     * 转换后播放下界岩遇水的"滋滋"声（{@code levelEvent 1501}）与白烟，手感与原版一致。
     *
     * <p><b>⚠️ 必须同时挂在 {@link #onPlace} 和 {@link #neighborChanged} 两个入口上</b>
     * （2026-09-26 修复：原先只挂 `neighborChanged`，导致"我们的液体流到水旁边"时不转化）。
     * 原版 {@code LiquidBlock} 的这两个方法体是**逐字节相同**的，都调它的 {@code shouldSpreadLiquid} ——
     * 不是冗余，而是因为两种到达顺序各只会触发其中一个：
     * <ul>
     *   <li><b>我们的液体自己流过去</b>（放到水旁边）→ 走 {@link #onPlace}。
     *       此时游戏通知的是**它的邻居**，而邻居（水）没变化、不会反过来通知我们，
     *       所以 {@code neighborChanged} 根本不会响 —— 这就是原来的 bug；</li>
     *   <li><b>水/岩浆流过来</b>（放到我们旁边）→ 对方那格发生变化，才轮到我们的 {@link #neighborChanged}。</li>
     * </ul>
     * `LevelChunk.setBlockState` 里对**任何**状态变化都会调 {@code onPlace}（含流体液面变化），
     * 所以 `onPlace` 这一路的覆盖面很广，本格液面从 8 变 7 也会顺手判一次。
     */
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        tryConvertToSolid(level, pos);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        tryConvertToSolid(level, pos);
    }

    /** 六个方向找水/岩浆；找到就把本格换成对应方块。没有则什么都不做。 */
    private void tryConvertToSolid(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return;
        }

        boolean water = false;
        boolean lava = false;
        for (Direction dir : Direction.values()) {
            FluidState fs = level.getFluidState(pos.relative(dir));
            if (fs.is(FluidTags.LAVA)) {
                lava = true;
                break;                       // 岩浆优先，不用再看别的方向
            }
            if (fs.is(FluidTags.WATER)) {
                water = true;
            }
        }

        Block result = null;
        if (lava) {
            result = HardenedEmulsionFluids.ANCIENT_DEBRIS_SEDIMENTARY_ROCK.get();
        } else if (water) {
            result = Blocks.DEEPSLATE;
        }
        if (result == null) {
            return;
        }

        // 与 vanilla LiquidBlock.shouldSpreadLiquid 同样的做法：在 onPlace / neighborChanged 里
        // 直接 setBlockAndUpdate（原版也是这么写的，重入是安全的）。
        level.setBlockAndUpdate(pos, result.defaultBlockState());
        level.levelEvent(1501, pos, 0);       // 原版"岩浆遇水"的滋滋声 + 白烟
    }
}
