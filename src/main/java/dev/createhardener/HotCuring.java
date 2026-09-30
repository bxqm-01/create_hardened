package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * **烧红硬化块的共用行为**（整砖 / 半砖 / 楼梯三族共用这一套）。
 *
 * <h2>用户 2026-09-28 定稿的冷却出口</h2>
 * <pre>
 *   注水（注液器浇在方块上 → Create 官方的 StateChangingBehavior，见 CreateHardener）→ 普通硬化块（第 0 档）
 *   方块状态遇水                                                                     → 微裂（第 1 档）
 *   方块状态遇冰（原版 #minecraft:ice：ice / packed_ice / blue_ice / frosted_ice）    → 风化（第 2 档）
 *   自然冷却（1200 刻）                                                               → 普通硬化块（第 0 档）
 * </pre>
 * **优先级：冰 &gt; 水 &gt; 自然** —— 即"淬得越狠、损伤越大"（水淬微裂、冰淬风化，机器注水则是可控淬火、能拿回第 0 档）。
 */
public final class HotCuring {

    /** 自然冷却成硬化块所需的刻数（用户指定 1200 刻 = 60 秒）。 */
    public static final int COOL_TICKS = 1200;

    /** 站立时每次伤害量（与岩浆块一致）。节奏交给原版的受伤无敌帧（10 刻）限流，不自己加间隔。 */
    public static final float STAND_DAMAGE = 1.0F;

    private HotCuring() {
    }

    /** 六个面（含下方）任意一面挨着水即为真 —— 与混凝土粉末的判据一致。 */
    private static boolean touchingWater(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getFluidState(pos.relative(dir)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 六个面任意一面挨着冰即为真。
     * 用**原版现成的 `#minecraft:ice` 标签** ⇒ 以后加别的模组的冰也自动生效，不用改代码。
     */
    private static boolean touchingIce(Level level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).is(BlockTags.ICE)) {
                return true;
            }
        }
        return false;
    }

    /** 该冷却到哪一档：遇冰 → 2（风化）、遇水 → 1（微裂）、都没有 → -1（交给自然冷却走第 0 档）。 */
    public static int quenchStage(Level level, BlockPos pos) {
        if (touchingIce(level, pos)) {
            return 2;
        }
        if (touchingWater(level, pos)) {
            return 1;
        }
        return -1;
    }

    /**
     * 换成**同形状**的第 {@code stage} 档硬化块。
     * ⚠️ 用 {@link HardeningStates#copySharedProperties} 搬同名属性 ⇒ 楼梯的朝向/半高/形状、半砖的形态、
     * 水浸状态都保住（不搬的话楼梯会突然转向，2026-09-26 在风化那边栽过同样的坑）。
     */
    public static void cool(Level level, BlockPos pos, HardeningStages.Family family, int stage, boolean fizz) {
        BlockState target = HardeningStates.copySharedProperties(level.getBlockState(pos),
                HardeningStages.of(family, stage).get().defaultBlockState());
        level.setBlockAndUpdate(pos, target);
        if (fizz) {
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F);
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(ParticleTypes.LARGE_SMOKE,
                        pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                        8, 0.3D, 0.1D, 0.3D, 0.02D);
            }
        }
    }

    /** 遇水/遇冰就地冷却；返回是否发生了冷却（false = 周围没水也没冰）。 */
    public static boolean coolIfQuenched(Level level, BlockPos pos, HardeningStages.Family family) {
        int stage = quenchStage(level, pos);
        if (stage < 0) {
            return false;
        }
        cool(level, pos, family, stage, true);
        return true;
    }

    /** 自然冷却：回到第 0 档（普通硬化块）。 */
    public static void coolNaturally(Level level, BlockPos pos, HardeningStages.Family family) {
        cool(level, pos, family, 0, false);
    }

    /**
     * 站在上面烫伤（与岩浆块一致）。
     *
     * <p>⚠️ **必须由 {@code stepOn} 调用**，不能只写 {@code entityInside}：原版
     * {@code Entity.checkInsideBlocks} 的遍历从 {@code AABB.minY + 1.0E-7} 开始 ——
     * 站在满碰撞箱方块上的实体，脚下那格根本不在遍历范围内（2026-09-26 实证）。
     */
    public static void damageStandingOn(Level level, Entity entity) {
        if (!level.isClientSide && entity instanceof LivingEntity living && !living.isSteppingCarefully()) {
            living.hurt(level.damageSources().hotFloor(), STAND_DAMAGE);
        }
    }

    /** 兜底伤害：正常站立走不到这里，但被活塞/方块推进体积里时也吃伤害。 */
    public static void damageInside(Level level, Entity entity) {
        if (level.isClientSide || !(entity instanceof LivingEntity living)) {
            return;
        }
        if (living.hurtTime <= 0) {
            living.hurt(level.damageSources().hotFloor(), STAND_DAMAGE);
        }
    }
}
