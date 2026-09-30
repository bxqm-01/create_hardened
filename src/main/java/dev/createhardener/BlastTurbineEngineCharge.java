package dev.createhardener;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;

/**
 * **把"原版爆炸判定算出来的强度"喂给冲爆引擎**（唯一调用方：{@code mixin.ExplosionChargeMixin}）。
 *
 * <h2>为什么强度不自己算</h2>
 * 原版 {@code Explosion.explode()} 的真实强度是
 * <pre>
 *   f = 半径 × (0.7 + 随机 × 0.6)        ← 带随机因子，永远不是整数
 *   沿射线从中心向外步进，每格：f -= (该格抗性 + 0.3) × 0.3；每步再 f -= 0.225
 * </pre>
 * —— **与路径有关、带随机**，自己复刻必然不准。所以这里只做"记账"，
 * 强度一律取原版传给 {@code shouldBlockExplode(...)} 的那个 {@code f}。
 *
 * <h2>三条规则</h2>
 * <ol>
 *   <li><b>只收一个方向</b>：爆炸中心必须落在引擎**接收面**（{@code FACING} 的反面）那一侧
 *       —— 也就是 {@code dot(接收面法线, 爆炸中心 − 方块中心) ≥ 0}。从输出面那边炸过来不算。</li>
 *   <li><b>同一次爆炸取最大值</b>：一次爆炸会有很多条射线打到同一个方块上，每次 f 都不同；
 *       按"这次爆炸打在我身上的最大值"结算，只补差额、不重复计数。</li>
 *   <li><b>每次爆炸只判一次损伤阶段</b>：靠 {@code Hit.counted} 标记"这一场爆炸是否已经判定过"，
 *       并把这个标记随应力一起交给 {@code BlastTurbineEngineBlockEntity.addBlastCharge(...)}
 *       —— **判定与加应力在 BE 内的同一处**（用户 2026-09-28 要求），但一场爆炸仍然只判一次。</li>
 * </ol>
 *
 * <p>{@link WeakHashMap} 以 {@link Explosion} 实例为键 —— 爆炸是短命对象，用完随 GC 走，不需要手动清理。
 * 只在服务端线程调用（mixin 里已经挡了客户端）。
 */
public final class BlastTurbineEngineCharge {

    private BlastTurbineEngineCharge() {
    }

    /** 记账单元：这次爆炸在这个方块上已经"发出去"的强度 + 是否已经做过损伤判定。 */
    private static final class Hit {
        float delivered;
        boolean counted;
    }

    private static final Map<Explosion, Map<BlockPos, Hit>> HITS = new WeakHashMap<>();

    /**
     * @param strength 原版 {@code Explosion.explode()} 算出来的、传给 {@code shouldBlockExplode} 的 f
     */
    public static void receive(BlockGetter levelAccessor, BlockPos pos, Explosion explosion, float strength) {
        if (!(levelAccessor instanceof Level level) || level.isClientSide) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() != CreateHardener.BLAST_TURBINE_ENGINE.get()) {
            return;
        }
        if (!isFromIntakeSide(state, pos, explosion)) {
            return;                                     // 规则①：只认接收面那一侧来的爆炸
        }
        if (!(level.getBlockEntity(pos) instanceof BlastTurbineEngineBlockEntity engine)) {
            return;
        }

        float safeStrength = Math.max(0.0F, strength);
        Map<BlockPos, Hit> perBlock = HITS.computeIfAbsent(explosion, e -> new HashMap<>());
        Hit hit = perBlock.get(pos);
        if (hit == null) {
            hit = new Hit();
            perBlock.put(pos, hit);
        }

        float delta = safeStrength - hit.delivered;
        if (delta <= 0.0F) {
            return;                                     // 规则②：这一条射线没打出更高的值，不重复计数
        }
        hit.delivered = safeStrength;

        // ★ "这一场爆炸是不是第一次真正给这台引擎加应力" —— 损伤判定随应力一起进 BE
        //   （用户 2026-09-28 要求：把"承受一次爆炸"的检测挪到**产生应力的那一处**）。
        //   用 counted 标记而不是"是不是新建的 Hit"：这样即使先来一条 f=0 的射线，也不会把
        //   唯一那次判定机会吃掉。
        boolean firstHitOfThisExplosion = !hit.counted;
        hit.counted = true;
        engine.addBlastCharge(delta, firstHitOfThisExplosion);
    }

    /** 爆炸中心是否落在接收面（FACING 的反面）那一侧。 */
    private static boolean isFromIntakeSide(BlockState state, BlockPos pos, Explosion explosion) {
        Direction intake = state.getValue(DirectionalKineticBlock.FACING).getOpposite();
        Vec3 center = explosion.center();
        Vec3 self = Vec3.atCenterOf(pos);
        double dot = (center.x - self.x) * intake.getStepX()
                + (center.y - self.y) * intake.getStepY()
                + (center.z - self.z) * intake.getStepZ();
        return dot >= 0.0D;
    }
}
