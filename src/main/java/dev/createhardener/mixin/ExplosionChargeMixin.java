package dev.createhardener.mixin;

import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import dev.createhardener.BlastTurbineEngineCharge;
import dev.createhardener.CreateHardener;

/**
 * **冲爆引擎的爆炸接收口**（2026-09-27 新增）—— 本模组的第二处注入。
 *
 * <h2>为什么注入在这里</h2>
 * {@code Explosion.explode()} 是**原版唯一的爆炸判定总闸门**，它对每一格依次做：
 * <pre>
 *   f = 半径 × (0.7 + 随机 × 0.6)                    // 起手强度（带随机）
 *   while (f > 0) {                                  // 沿射线从中心向外步进
 *       f -= (该格抗性 + 0.3) × 0.3                  // ← ① 抗性查询
 *       if (f > 0 && shouldBlockExplode(..., f))     // ← ② 拿 f 做"炸不炸"的判定
 *           待炸集合.add(该格)
 *       步进 0.3 格；f -= 0.225
 *   }
 * </pre>
 * <b>只有这里能拿到"原版自己算出来的、作用于我们这个方块的爆炸强度 f"</b> —— 而且任何走原版
 * {@code Explosion} 的模组爆炸都会经过它（模组自定义 {@link ExplosionDamageCalculator} 也照走），
 * 这就是"**主动适配其它模组的爆炸**"：不需要对方为我们做任何事。
 *
 * <h2>为什么要动 ①（报一个中等抗性，而不是方块属性里那个黑曜石级）</h2>
 * 抗性是**先减掉、再判断 f > 0**。引擎方块属性里的抗性是黑曜石级 1200 ⇒ {@code f -= (1200+0.3)*0.3 ≈ 360}
 * ⇒ f 立刻变成负数 ⇒ ② 那句**永远不会被执行**，我们也就永远收不到爆炸强度。
 * 所以这里在"查询我方方块"时报一个**中等**抗性（{@link #REPORTED_RESISTANCE}）：判定流程能继续走下去，
 * 我们把强度读走、再于 ② 处返回 false（**方块本身永远不会被炸掉**；黑曜石抗性仍写在方块属性里，
 * 对不走这条路的其它破坏方式依旧有效）。
 *
 * <h2>为什么不是 0（2026-09-27 用户实测后改的）</h2>
 * 一开始报的是 0：听得最全，但**射线会直接穿过引擎**，把它**身后**的传动轴/机器一起炸掉 ——
 * 更糟的是传动轴一没、动力网当场解散，引擎刚攒的转速与应力**立刻归零**，
 * 表现成"挨了炸却什么都没发生"。报 4.0 之后，射线在引擎内部每个采样点都要扣 1.29，
 * 两三个采样点就归零 ⇒ **身后那格基本保住**。代价：偏远/偏弱的爆炸收不到，
 * 且读到的 f 变小 ⇒ {@code STRESS_PER_STRENGTH} 翻倍补偿（见那里的说明）。
 *
 * <p>注入方式选 {@code @Redirect} 而不是读局部变量（{@code @ModifyVariable} + ordinal）：
 * 后者要靠字节码里的 STORE 序号，脆弱且失败时是**静默取错值**；{@code @Redirect} 拿到的
 * {@code f} 是原版当作参数传出来的，语义就是"这次爆炸打在这一格上的强度"，最稳。
 *
 * <p>{@code remap = false}：本实例运行期用的就是官方名（已验证 {@code client-…-srg.jar} 里
 * **0 个 {@code m_xxx_} 形式**，{@code setBlock}/{@code destroyBlock} 等全是官方名）。
 */
@Mixin(value = Explosion.class, remap = false)
public abstract class ExplosionChargeMixin {

    /**
     * **判定里报给原版的抗性**（不是方块属性里那个 1200）。
     *
     * <p>这个数**同时决定两件事**，所以是"听着灵不灵"与"挡不挡得住"的取舍点：
     * <ul>
     *   <li>它会被原版从 {@code f} 里减掉（{@code f -= (R + 0.3) × 0.3}）—— 减得越多，我们**读到的强度越小**、
     *       太弱的爆炸干脆 {@code f ≤ 0} 连判定都不进（收不到）；</li>
     *   <li>同时它决定射线能穿多深：**每 0.3 格采样点都减一次**，所以 R 越大，射线越早死在引擎内部
     *       —— **引擎后面的方块就保住了**。</li>
     * </ul>
     *
     * <p>取 <b>4.0</b>（石头级）的依据：TNT 贴着接收面炸时，到达引擎的 f ≈ 2.0~4.5（中心值乘 0.7~1.3 随机、
     * 再扣掉 1 格路程的 0.225/步），减掉 {@code (4+0.3)×0.3 = 1.29} 后仍有 ≈ 0.7~3.2 &gt; 0 ⇒ **照常充能**；
     * 而射线在引擎内部每采样点掉 1.29，两三个采样点就归零 ⇒ **后面的方块基本保住**。
     * 代价：**偏远/偏弱的爆炸收不到**（这是"当盾"必然的代价），并且读到的 f 比原来小 ⇒
     * {@code STRESS_PER_STRENGTH} 相应翻倍以保持"每秒一发 TNT 维持满值"的标定。
     */
    private static final float REPORTED_RESISTANCE = 4.0F;

    /**
     * ① 抗性查询：我方方块报一个**中等**抗性（见 {@link #REPORTED_RESISTANCE}）—— 既让判定流程能走到
     * {@code shouldBlockExplode}（否则黑曜石级 1200 会把 f 打成负数、那句永不执行），又让引擎吸收掉一部分爆炸。
     * 其它方块原样转发。
     */
    @Redirect(method = "explode", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;getBlockExplosionResistance"
                            + "(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
                            + "Lnet/minecraft/world/level/material/FluidState;)Ljava/util/Optional;"))
    private Optional<Float> createhardener$letEngineBeReached(ExplosionDamageCalculator calculator, Explosion explosion,
                                                              BlockGetter level, BlockPos pos, BlockState state,
                                                              FluidState fluid) {
        if (state.getBlock() == CreateHardener.BLAST_TURBINE_ENGINE.get()) {
            return Optional.of(REPORTED_RESISTANCE);
        }
        return calculator.getBlockExplosionResistance(explosion, level, pos, state, fluid);
    }

    /**
     * ② 破坏判定：把我方方块收到的 {@code strength}（= 原版算出来的 f）交给引擎，并返回 false
     * —— 于是它不会进"待炸集合"，**在爆炸里活下来**。
     */
    @Redirect(method = "explode", remap = false,
            at = @At(value = "INVOKE", remap = false,
                    target = "Lnet/minecraft/world/level/ExplosionDamageCalculator;shouldBlockExplode"
                            + "(Lnet/minecraft/world/level/Explosion;Lnet/minecraft/world/level/BlockGetter;"
                            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;F)Z"))
    private boolean createhardener$captureBlast(ExplosionDamageCalculator calculator, Explosion explosion,
                                                BlockGetter level, BlockPos pos, BlockState state, float strength) {
        boolean wouldExplode = calculator.shouldBlockExplode(explosion, level, pos, state, strength);
        if (state.getBlock() == CreateHardener.BLAST_TURBINE_ENGINE.get()) {
            BlastTurbineEngineCharge.receive(level, pos, explosion, strength);
            return false;
        }
        return wouldExplode;
    }
}
