package dev.createhardener;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * **爆炸把硬化块变成烧红状态**（用户 2026-09-28 要求："这些方块受到爆炸会概率变为烧红状态的特性，
 * 这样玩家还可以利用爆炸来除风化"）。
 *
 * <h2>三条定稿</h2>
 * <ol>
 *   <li><b>概率随风化程度递增</b>（用户选方案 A）：档 0 → <b>20%</b>、档 1 → <b>40%</b>、档 2 → <b>60%</b>、档 3 → <b>80%</b>。
 *       含义：风化越重越容易"重塑回炉"，但深档同时更容易被炸掉（抗性 15 那一档 TNT 贴脸就可能直接没）——
 *       低档安全但机会小、深档机会大但可能直接损失，这正是用户要的风险曲线。</li>
 *   <li><b>保持形状</b>：整砖→烧红整砖、半砖→烧红半砖、楼梯→烧红楼梯。</li>
 *   <li><b>不给保护</b>：本次爆炸**本就会炸掉的格子直接跳过**（`Detonate` 的 `getAffectedBlocks()`）——
 *       用户原话："有可能重塑自身，也有可能直接被炸掉"。别自作聪明去"救"它们。</li>
 * </ol>
 *
 * <p>钩子用 **NeoForge 官方事件** `ExplosionEvent.Detonate`（爆炸已结算射线、还没拆方块的那一刻），
 * **不写 mixin** —— 这样跟引擎那处 `ExplosionChargeMixin` 互不干扰。事件只在有爆炸时触发，成本可控。
 */
public final class ScorchingEvents {

    /** 各风化档的"变烧红"概率（2026-09-28 用户把方案 A 整体砍半：20/40/60/80 → **10/20/30/40%**）。 */
    private static final float[] CHANCE = { 0.10F, 0.20F, 0.30F, 0.40F };

    /**
     * 扫描半径上限（格）。
     *
     * <p>扫描量按 {@code (2r+3)³} 增长：TNT（r=4）是 11³ ≈ 1.3k 次、还行；但模组大炸弹 r=30 就是 61³ ≈ 22 万次
     * {@code getBlockState}，r=100 直接 840 万次 ⇒ 单次爆炸就能卡住主线程。设上限纯粹是防这个。
     * 超过上限的部分**不会漏掉"该重塑的"** —— 那种威力的爆炸本来就会把范围内的方块直接炸掉。
     */
    private static final int MAX_SCAN_RADIUS = 16;

    private ScorchingEvents() {
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide) {
            return;
        }
        Explosion explosion = event.getExplosion();
        Vec3 center = explosion.center();
        double radius = explosion.radius();

        // 本次会被炸掉的格子：跳过 = 不加保护（用户定）
        Set<BlockPos> doomed = new HashSet<>(event.getAffectedBlocks());

        BlockPos origin = BlockPos.containing(center);
        int r = Math.min(Mth.ceil(radius) + 1, MAX_SCAN_RADIUS);   // 上限见 MAX_SCAN_RADIUS 的说明
        double limit = (radius + 0.5D) * (radius + 0.5D);
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-r, -r, -r), origin.offset(r, r, r))) {
            if (doomed.contains(pos)) {
                continue;
            }
            if (center.distanceToSqr(Vec3.atCenterOf(pos)) > limit) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            int stage = Hardening.stageOfBlock(state.getBlock());
            if (stage < 0) {
                continue;                                   // 不是我方硬化块（烧红块自己、别的模组方块都跳过）
            }
            if (level.random.nextFloat() >= CHANCE[Math.min(stage, CHANCE.length - 1)]) {
                continue;
            }
            HardeningStages.Family family = HardeningStages.familyOf(state.getBlock());
            if (family == null) {
                continue;
            }
            level.setBlockAndUpdate(pos, hotState(family));
        }
    }

    /** 同形状的烧红方块状态。 */
    private static BlockState hotState(HardeningStages.Family family) {
        return switch (family) {
            case FULL -> CreateHardener.HOT_HARDENED_BLOCK.get().defaultBlockState();
            case SLAB -> CreateHardener.HOT_HARDENED_BLOCK_SLAB.get().defaultBlockState();
            case STAIR -> CreateHardener.HOT_HARDENED_BLOCK_STAIRS.get().defaultBlockState();
        };
    }
}
