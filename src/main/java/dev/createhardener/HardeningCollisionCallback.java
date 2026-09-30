package dev.createhardener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3d;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 「物理化状态下被撞 → 额外补一次风化判定」（2026-09-26 用户要求）。
 *
 * <p><b>为什么走这个接口</b>：硬化块被物理组装器搬进航空学（Sable）的子世界后，住的是 plot 坐标空间，
 * 引擎的随机刻依然照常跑（自然风化不受影响），但"碰撞"这件事只有 Sable 的物理管线知道。
 * Sable 为此提供了 {@link BlockSubLevelCollisionCallback}：方块实现 {@code BlockWithSubLevelCollisionCallback}
 * 并返回一个回调，物理体上的每次接触判定都会调 {@code sable$onCollision(pos, motion, velocity)}。
 *
 * <p><b>⚠️ 铁律一（本项目在 beta6 栽过，导致 Rapier Rust 层 abort 整个进程）</b>：
 * 接口那个原生桥接方法 {@code double[] onCollision(int,int,int,double,double,double,double)} 的字节码是
 * <pre>
 *   CollisionResult r = sable$onCollision(pos, motion, v);
 *   Vector3dc tangent = r.tangentMotion;      // 不判空
 *   out[0]=tangent.x(); ... out[3]= r.removeCollision()?1:0;
 * </pre>
 * 只要返回 <b>null</b>、或让 {@code tangentMotion} 为 null，这里就抛异常 → 原生层拿到 null 数组
 * → {@code NullPtr("get_double_array_region array argument")} → 进程 abort。
 * 所以本类**所有路径都返回非 null 的 {@code CollisionResult.NONE}**。
 *
 * <p><b>⚠️ 铁律二（2026-09-26 加固）：绝不能让异常外抛。</b>
 * 这个回调是从原生（Rust/Rapier）经 JNI 调进来的。异常穿出 JNI 会让原生侧 panic，
 * 而 natives 里带着 {@code Rayon: detected unexpected panic; aborting} —— 同样是 abort 进程。
 * 所以整个回调体包在 {@code catch (Throwable)} 里，静默吞掉（宁可这一次不判定）。
 *
 * <p><b>⚠️ 铁律三：线程身份不可假设。</b>
 * {@code SubLevelPhysicsSystem.getCurrentlySteppingSystem()} 读的是一个**非 volatile 的 public static
 * 字段**，natives 里又能找到 {@code rayon-1.11.0} / {@code AttachCurrentThreadAsDaemon} 这类字符串 ——
 * 回调**有可能**跑在 rayon 工作线程上。因此冷却表用并发容器（普通 {@code HashMap} 并发写可能死循环），
 * 且不依赖任何非同步的共享可变状态。
 *
 * <p><b>两条保险丝</b>：
 * <ol>
 *   <li>{@link Hardening#IMPACT_MIN_VELOCITY} 速度门槛 —— 回调是按"接触"触发的，静置的刚体也会持续
 *       产生接触，不设门槛就会每刻狂刷判定。4.0 m/s 照抄 Sable 自带 {@code FragileBlockCallback}。</li>
 *   <li>{@link #COOLDOWN_TICKS} 每格冷却 —— 一次真实撞击会在若干刻内产生一串接触。</li>
 * </ol>
 */
public final class HardeningCollisionCallback implements BlockSubLevelCollisionCallback {

    /** 全局唯一实例（方块只返回这一个，别每次 new）。 */
    public static final HardeningCollisionCallback INSTANCE = new HardeningCollisionCallback();

    /** 同一格两次碰撞判定之间的最小间隔（游戏刻）。 */
    public static final long COOLDOWN_TICKS = 40L;

    /** 冷却表超过这个条目数就清一次，避免长期运行下无限增长。 */
    private static final int PRUNE_THRESHOLD = 4096;

    /**
     * 每格的上次判定时刻。
     *
     * <p>并发容器：回调线程身份不可假设（见类注释铁律三）。
     *
     * <p>⚠️ 用 {@link BlockPos} 做键：Sable 桥接方法里是 {@code new BlockPos(x,y,z)}，是不可变对象，做键安全。
     * 跨维度同坐标会共用一条冷却记录 —— 影响可忽略，不值得为它加维度键。
     */
    private static final Map<BlockPos, Long> LAST_ROLL = new ConcurrentHashMap<>();

    private HardeningCollisionCallback() {
    }

    @Override
    public CollisionResult sable$onCollision(BlockPos pos, Vector3d motion, double velocity) {
        try {
            roll(pos, velocity);
        } catch (Throwable ignored) {
            // 故意吞掉：异常绝不可以穿出 JNI（见类注释铁律二）。宁可这一次不判定。
        }
        // 不改切向速度、不取消碰撞 —— 我们只是"顺便判一次风化"。
        return CollisionResult.NONE;
    }

    /** 真正干活的部分，与上面那层 try/catch 分开，保持回调体一眼能看完。 */
    private static void roll(BlockPos pos, double velocity) {
        if (velocity * velocity < Hardening.IMPACT_MIN_VELOCITY * Hardening.IMPACT_MIN_VELOCITY) {
            return;
        }

        // ⚠️ 这个方法**没有 null 返回值**：没在步进时它抛 IllegalStateException
        //    （字节码：ifnonnull → areturn；否则 new IllegalStateException("No physics system is
        //     currently stepping") → athrow）。所以这里不做 null 判断（那是死代码），
        //    由上面的 catch 兜住。
        SubLevelPhysicsSystem system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        ServerLevel level = system.getLevel();

        long now = level.getGameTime();

        // 冷却判定**放在方块查询之前**（2026-09-26 修）：40 刻冷却期内的每一个接触点
        // 都不该再白做一次 getBlockState + 档位查表。
        Long last = LAST_ROLL.get(pos);
        if (last != null && now - last < COOLDOWN_TICKS) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        int stage = Hardening.stageOfBlock(state.getBlock());
        if (stage < 0 || Hardening.isLastStage(stage)) {
            return;                      // 不是硬化块 / 已是最后一档：不占用冷却名额
        }

        LAST_ROLL.put(pos, now);
        if (LAST_ROLL.size() > PRUNE_THRESHOLD) {
            prune(now);
        }

        Hardening.tryAdvanceFromImpact(state, level, pos, velocity);
    }

    /** 清掉已经过冷却期的记录；若清完还超量（同一刻大量新位置），直接整表清空兜底。 */
    private static void prune(long now) {
        LAST_ROLL.entrySet().removeIf(entry -> now - entry.getValue() >= COOLDOWN_TICKS);
        if (LAST_ROLL.size() > PRUNE_THRESHOLD) {
            LAST_ROLL.clear();
        }
    }
}
