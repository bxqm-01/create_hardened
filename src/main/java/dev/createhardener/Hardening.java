package dev.createhardener;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 硬化块的共性：档位常量、逐档解析、风化判定。
 *
 * <p>注意：原版 {@code WeatheringCopper.NEXT_BY_BLOCK} 是 {@code ImmutableBiMap}，
 * 第三方无法往里注册（put 会抛 UnsupportedOperationException）。
 * 因此各形状方块**各自覆写 {@code getNext(BlockState)}**，用本模组自己的阶段表解析下一档。
 *
 * <p><b>⚠️ 风化有两条完全独立的路径（2026-09-26 用户拍板拆分，别再合回去）</b>：
 * <ol>
 *   <li><b>自然风化</b>（{@link #randomTickWeathering} → {@link #rollNaturalWeathering}）：
 *       环境氧化，**照抄原版铜的"扩散"语义** —— 邻居越多（同档扎堆）越慢，邻居越风化越快。</li>
 *   <li><b>碰撞风化</b>（{@link #tryAdvanceFromImpact} → {@link #rollImpactWeathering}）：
 *       外力冲击，**完全不看邻居分布**，只跟"撞多狠 + 有没有碰到水"有关。</li>
 * </ol>
 * 为什么要拆：两者物理上无关（邻居多不多，跟你撞它一下会掉多少皮没关系）。原先塞进同一函数、
 * 共用原版邻居项，导致**物理化结构（一坨同族方块紧挨着）里碰撞概率被压到 1/27 甚至归零** ——
 * 那是给"自然生成的大片平铺铜"设计的语义，搬到紧凑刚体上就反了。
 *
 * <p><b>手感/物理口径（2026-09-24 收敛，别再改错方向）</b>：
 * <ul>
 *   <li>方块**手感**用原版默认摩擦系数 0.6，四档一样 —— 真实差异不在这里；</li>
 *   <li>真实差异走航空学（Sable）的物理属性：{@code data/sable/physics_block_properties/*.json}
 *       里 {@code sable:friction} = 1.0 / 1.5 / 2.0 / 2.5（越高越粘），已由用户实测通过。**那里才是唯一生效处。**</li>
 * </ul>
 */
public final class Hardening {

    private Hardening() {
    }

    // ======================= 自然风化的参数 =======================

    /**
     * 外层门槛，**照抄原版** {@code ChangeOverTimeBlock.changeOverTime} 里的常量 0.05688889F。
     */
    public static final float BASE_CHANCE = 0.05688889F;

    /** 邻居扫描半径，与 {@code ChangeOverTimeBlock.SCAN_DISTANCE} 一致（曼哈顿距离 ≤ 4）。 */
    public static final int SCAN_DISTANCE = 4;

    /**
     * **自然风化（随机刻）速度倍率** —— 乘在原版基准上。用户 2026-09-26："把自然风化速度调低一点"。
     * 取 0.5 = 原版基准的一半 → 孤立一块约 40～53 分钟一档。
     *
     * <p>**只作用于自然路径**；碰撞风化是独立机制，见 {@link #IMPACT_MIN_CHANCE}。
     */
    public static final float NATURAL_MULTIPLIER = 0.5F;

    // ======================= 碰撞风化的参数（独立机制） =======================

    /**
     * 撞击速度门槛（m/s）：低于它**完全不判定**。
     *
     * <p>与 {@link HardeningCollisionCallback} 用的是同一个值（那边直接引用本常量，避免两处真相）。
     * 取 4.0 是照抄 Sable 自带 {@code FragileBlockCallback.getTriggerVelocity()}。
     */
    public static final double IMPACT_MIN_VELOCITY = 4.0D;

    /** 达到这个速度就必定推进（速度曲线的饱和点）。 */
    public static final double IMPACT_FULL_VELOCITY = 20.0D;

    /** 刚过门槛时的概率。 */
    public static final float IMPACT_MIN_CHANCE = 0.20F;

    /** 饱和时的概率。 */
    public static final float IMPACT_MAX_CHANCE = 1.00F;

    // ======================= 两条路径共用的加成 =======================

    /** 碰到水时的概率倍率（用户 2026-09-26 拍板：×3）。**两条路径都吃这一项。** */
    public static final float WATER_MULTIPLIER = 3.0F;

    /** 每一个"风化更重"的同类邻居带来的概率加成（用户 2026-09-26 拍板：0.25）。 */
    public static final float NEIGHBOUR_BONUS = 0.25F;

    /**
     * 档位 → 原版 {@code WeatheringCopper.WeatherState}（四档一一对应）。
     *
     * <p>为什么要给每档不同的 WeatherState：原版 {@code ChangeOverTimeBlock.getNextState} 会扫描周围
     * 4 格内的同类方块，统计"比自己老/和自己一样"的数量来算风化概率。如果四档都返回同一个状态，
     * 那个统计就永远算不出差异，所以让档位与状态一一对应。
     *
     * <p>另：{@code WeatheringCopper.getChanceModifier()} 默认实现给 {@code UNAFFECTED} 档 0.75、
     * 其余档 1.0 —— 也就是说**第 0 档（硬化块）自然风化天然最慢**，这是原版行为，保留。
     * （碰撞路径不吃这一项 —— 那条是独立机制，不引用原版的档位修饰。）
     */
    public static WeatheringCopper.WeatherState ageOf(int stage) {
        return switch (Math.max(0, Math.min(HardeningStages.MAX_STAGE, stage))) {
            case 1 -> WeatheringCopper.WeatherState.EXPOSED;
            case 2 -> WeatheringCopper.WeatherState.WEATHERED;
            case 3 -> WeatheringCopper.WeatherState.OXIDIZED;
            default -> WeatheringCopper.WeatherState.UNAFFECTED;
        };
    }

    // ======================= 我方方块 → 档位查表（含"隔离原版铜"） =======================

    private static volatile Map<Block, Integer> stageByBlock;

    /**
     * 我方方块 → 档位；**不是我方方块返回 -1**。
     *
     * <p>这张表是"隔离原版铜干扰"的关键：原版 {@code getNextState} 判"同类"用的是
     * {@code getAge().getClass()}，而我们与铜共用 {@code WeatheringCopper.WeatherState}，
     * 于是附近一块**更新鲜的原版铜**会让我们的方块直接停止风化。改成只认自己的方块。
     */
    public static int stageOfBlock(Block block) {
        Map<Block, Integer> map = stageByBlock;
        if (map == null) {
            synchronized (Hardening.class) {
                map = stageByBlock;
                if (map == null) {
                    map = buildStageMap();
                    stageByBlock = map;
                }
            }
        }
        Integer stage = map.get(block);
        return stage == null ? -1 : stage;
    }

    /** 懒建：必须在 RegisterEvent 之后才能解析 DeferredBlock，所以不能在静态初始化里建。 */
    private static Map<Block, Integer> buildStageMap() {
        Map<Block, Integer> map = new HashMap<>();
        for (int stage = 0; stage <= HardeningStages.MAX_STAGE; stage++) {
            for (HardeningStages.Family family : HardeningStages.Family.values()) {
                map.put(HardeningStages.of(family, stage).get(), stage);
            }
        }
        return map;
    }

    // ======================= 邻居扫描（两条路径共用） =======================

    /**
     * 扫曼哈顿距离 ≤ 4 的邻居，返回 {@code [older, same]}。
     *
     * <p>只统计**本模组自己的四档硬化块** —— 不是我们的方块（含原版铜）一律跳过，这就是"隔离铜"。
     *
     * <p>⚠️ <b>与正版的一处有意差异</b>：原版遇到<b>比自己更新鲜</b>的同类会直接
     * {@code return Optional.empty()}（本刻完全不判定）。那会造成死锁 —— 结构里先推进的方块
     * 会被旁边还没风化的方块顶成 0%、永久停住。这里改成**把"更新鲜的"也计入 {@code same}**：
     * 保留减速效果，但不再归零。
     *
     * <p>碰撞路径只用 {@code older}（只加速不减速），所以这个改动对碰撞没有副作用。
     */
    private static int[] scanNeighbours(int stage, ServerLevel level, BlockPos pos) {
        int older = 0;
        int same = 0;
        for (BlockPos other : BlockPos.withinManhattan(pos, SCAN_DISTANCE, SCAN_DISTANCE, SCAN_DISTANCE)) {
            if (other.distManhattan(pos) > SCAN_DISTANCE) {
                break;
            }
            if (other.equals(pos)) {
                continue;
            }
            int otherStage = stageOfBlock(level.getBlockState(other).getBlock());
            if (otherStage < 0) {
                continue;                       // 不是我方方块（含原版铜）→ 不计入
            }
            if (otherStage > stage) {
                older++;
            } else {
                same++;                         // 同档 + 更新鲜的，都算 same（不再归零）
            }
        }
        return new int[] { older, same };
    }

    // ======================= 路径①：自然风化（随机刻） =======================

    /**
     * 自然风化的判定 —— **照抄原版铜的"扩散"语义**：
     * <pre>
     *   概率 = 0.05688889（原版门槛） × ratio² × getChanceModifier() × 0.5（{@link #NATURAL_MULTIPLIER}）
     *        × 遇水 ×3 × (1 + older × 0.25)
     *   ratio = (older+1)/(older+same+1)     上限恒为 1.0 —— 同档扎堆会显著变慢
     * </pre>
     * 孤立一块（无同类邻居、不碰水）= 0.05688889 × 1.0 × modifier × 0.5，约 40～53 分钟一档。
     */
    private static boolean rollNaturalWeathering(WeatheringCopper copper, int stage, ServerLevel level,
                                                BlockPos pos, RandomSource random) {
        int[] neighbours = scanNeighbours(stage, level, pos);
        int older = neighbours[0];
        int same = neighbours[1];

        float ratio = (older + 1.0F) / (older + same + 1.0F);
        float chance = BASE_CHANCE * ratio * ratio * copper.getChanceModifier() * NATURAL_MULTIPLIER;
        chance *= waterMultiplier(level, pos);
        chance *= 1.0F + older * NEIGHBOUR_BONUS;
        return random.nextFloat() < clamp(chance);
    }

    /**
     * 风化推进（**必须由方块自己调用**）。
     *
     * <p>为什么不能省：原版把 {@code randomTick} / {@code isRandomlyTicking} 实现**在具体的铜方块类上**，
     * {@code WeatheringCopper} 接口里没有这两个方法。任何 {@code extends Block implements WeatheringCopper}
     * 的第三方方块都必须自己实现 —— 否则引擎根本不会把它排进随机刻，永远不风化
     * （2026-09-24 用户实测：等到游戏内 5 天、tick 速率调到 2000 也毫无变化）。
     *
     * <p>同时**不能照抄原版的 {@code isRandomlyTicking}**：原版那行是
     * {@code WeatheringCopper.getNext(state.getBlock()).isPresent()}，走的是**只含原版铜方块**的静态表，
     * 对我们的方块永远返回 false。所以这里改用我们自己的阶段判断。
     */
    public static void randomTickWeathering(WeatheringCopper copper, int stage, BlockState state,
                                            ServerLevel level, BlockPos pos, RandomSource random) {
        if (isLastStage(stage)) {
            return;
        }
        if (rollNaturalWeathering(copper, stage, level, pos, random)) {
            advance(copper, state, level, pos);
        }
    }

    // ======================= 路径②：碰撞风化（独立机制） =======================

    /**
     * **物理化状态下被撞击时的风化判定 —— 一条完全独立的机制。**
     *
     * <p>与自然路径的区别（这是拆分的全部意义）：
     * <ul>
     *   <li><b>不扫 {@code same}、不乘 {@code ratio}</b> —— 邻居多不多与"撞一下会掉多少皮"无关。
     *       所以物理化结构里每一块的碰撞概率都符合下面的表，不再被同族邻居压到 1/27 或归零。</li>
     *   <li><b>不吃 {@code getChanceModifier()}</b> —— 那条是原版的档位修饰，这里用纯粹的速度曲线。</li>
     *   <li>邻居只用来**加速**：{@code ×(1 + older × 0.25)}，永不减速。</li>
     * </ul>
     *
     * <p>概率 = 速度曲线 × 遇水 ×3 × 邻居加成（只加速），最后钳到 100%：
     * <pre>
     *   撞击速度   4     8     12    16    ≥20   m/s
     *   概率       20%   40%   60%   80%   100%      （线性插值，{@link #IMPACT_MIN_CHANCE}→{@link #IMPACT_MAX_CHANCE}）
     * </pre>
     * 叠加项举例：撞水里的硬化块（×3）、旁边有 4 个更风化的同类（×(1+1.0)=×2）。
     */
    /** 碰撞路径只需要"更风化的邻居数"这一项 —— 单开一个方法，省掉 same 的统计与 `int[]` 分配。 */
    private static int olderNeighbours(int stage, ServerLevel level, BlockPos pos) {
        int older = 0;
        for (BlockPos other : BlockPos.withinManhattan(pos, SCAN_DISTANCE, SCAN_DISTANCE, SCAN_DISTANCE)) {
            if (other.distManhattan(pos) > SCAN_DISTANCE) {
                break;
            }
            if (other.equals(pos)) {
                continue;
            }
            if (stageOfBlock(level.getBlockState(other).getBlock()) > stage) {
                older++;
            }
        }
        return older;
    }

    private static boolean rollImpactWeathering(int stage, ServerLevel level, BlockPos pos,
                                               double velocity, RandomSource random) {
        double t = (velocity - IMPACT_MIN_VELOCITY) / (IMPACT_FULL_VELOCITY - IMPACT_MIN_VELOCITY);
        if (t < 0.0D) {
            t = 0.0D;
        } else if (t > 1.0D) {
            t = 1.0D;
        }
        float chance = (float) (IMPACT_MIN_CHANCE + (IMPACT_MAX_CHANCE - IMPACT_MIN_CHANCE) * t);
        chance *= waterMultiplier(level, pos);

        // 速度够快时基础概率已经 100%（遇水更容易到），此时**不必再扫邻居** ——
        // 邻居加成只可能让它更高，而扫描是 128 次方块查表（2026-09-26 性能修）。
        if (chance >= 1.0F) {
            return true;
        }
        chance *= 1.0F + olderNeighbours(stage, level, pos) * NEIGHBOUR_BONUS;
        return random.nextFloat() < clamp(chance);
    }

    /**
     * 物理化状态下被撞击时补一次风化判定（由 {@link HardeningCollisionCallback} 调用）。
     *
     * @param velocity 撞击速度（m/s），由 Sable 的碰撞回调给出；低于
     *                 {@link #IMPACT_MIN_VELOCITY} 时概率为 0
     */
    public static void tryAdvanceFromImpact(BlockState state, ServerLevel level, BlockPos pos, double velocity) {
        Block block = state.getBlock();
        if (!(block instanceof WeatheringCopper copper)) {
            return;
        }
        int stage = stageOfBlock(block);
        if (stage < 0 || isLastStage(stage)) {
            return;
        }
        if (rollImpactWeathering(stage, level, pos, velocity, level.getRandom())) {
            advance(copper, state, level, pos);
        }
    }

    // ======================= 工具 =======================

    /** 自身（水封的半砖/楼梯）或六个方向贴水 → 返回 {@link #WATER_MULTIPLIER}，否则 1.0。 */
    private static float waterMultiplier(Level level, BlockPos pos) {
        if (level.getFluidState(pos).is(FluidTags.WATER)) {
            return WATER_MULTIPLIER;
        }
        for (Direction direction : Direction.values()) {
            if (level.getFluidState(pos.relative(direction)).is(FluidTags.WATER)) {
                return WATER_MULTIPLIER;
            }
        }
        return 1.0F;
    }

    private static float clamp(float chance) {
        return chance > 1.0F ? 1.0F : chance;
    }

    /** 真正把方块换成下一档（等价于原版 {@code ChangeOverTimeBlock} 里那个 lambda）。 */
    private static void advance(WeatheringCopper copper, BlockState state, ServerLevel level, BlockPos pos) {
        copper.getNext(state).ifPresent(next -> level.setBlockAndUpdate(pos, next));
    }

    /** 还有下一档才需要随机刻（最后一档自动停掉，省掉无意义的随机刻开销）。 */
    public static boolean isRandomlyTicking(int stage) {
        return !isLastStage(stage);
    }

    /** 下一档同族方块的默认状态；已是最后一档返回 empty。 */
    public static Optional<BlockState> nextOf(HardeningStages.Family family, int stage) {
        if (stage >= HardeningStages.MAX_STAGE) {
            return Optional.empty();
        }
        return Optional.of(HardeningStages.of(family, stage + 1).get().defaultBlockState());
    }

    /** 半砖：目标是 double 时收敛为单砖，避免把不合法的状态写进世界。 */
    public static BlockState toSingleSlab(BlockState state) {
        if (state.hasProperty(net.minecraft.world.level.block.SlabBlock.TYPE)
                && state.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE) {
            return state.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.SlabType.BOTTOM);
        }
        return state;
    }

    /** 上一档同族方块的默认状态（黑曜石粉恢复用）；已是 0 档返回 empty。 */
    public static Optional<BlockState> previousOf(HardeningStages.Family family, int stage) {
        if (stage <= 0) {
            return Optional.empty();
        }
        return Optional.of(HardeningStages.of(family, stage - 1).get().defaultBlockState());
    }

    public static boolean isLastStage(int stage) {
        return stage >= HardeningStages.MAX_STAGE;
    }
}
