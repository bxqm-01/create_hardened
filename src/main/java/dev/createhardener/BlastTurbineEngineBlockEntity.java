package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;

/**
 * **冲爆引擎的方块实体** —— 原版动能来源骨架（{@link GeneratingKineticBlockEntity}）+ 我们自己的"应力池"。
 *
 * <h2>机制（2026-09-27 用户定稿：固定转速 + 只让池变化）</h2>
 * 这是照 **风车轴承** 的做法反推出来的（那份字节码：风车只覆写 {@code getGeneratedSpeed()}，
 * 容量是固定的注册值；因为显示容量 = 容量 × 转速，所以转速一变、显示值就跟着变）——
 * 结论是 **不要同时动"容量"和"转速"两个变量**。我们这里更简单：
 * <pre>
 *   转速：恒定 {@value #SPEED} RPM（池 &gt; 0 就一直跑这个速度；池 = 0 立刻停）
 *   容量：就是应力池本身（每转速值 per-RPM），由爆炸强度驱动、随时间线性衰减
 *   ⇒ 玩家看到的 SU = 池 × {@value #SPEED} —— ★ 只有一个变量在动，平滑、可预期、无跳变
 * </pre>
 *
 * <h2>数值（"除一下转速"的算法）</h2>
 * 上一版是"池上限 64 × 最高转速 256 = 16384 SU"。现在转速固定 32，要保持同一个上限：
 * {@code 16384 / 32 = 512} ⇒ 池上限取 **{@value #MAX_STRESS}**。其余按同一比例放大 8 倍：
 * <table>
 *   <tr><th>项</th><th>值</th><th>含义</th></tr>
 *   <tr><td>池上限</td><td>{@value #MAX_STRESS}</td><td>满池 ⇒ 512 × 32 = <b>16384 SU</b></td></tr>
 *   <tr><td>自然衰减</td><td>−{@value #STRESS_DECAY_PER_SECOND}/秒</td><td>池的 12.5%/秒 ⇒ 满池 <b>8 秒</b>见底</td></tr>
 *   <tr><td>单发增量</td><td>{@code 64 × f}</td><td>贴脸 TNT（f≈2）≈ <b>+125</b> ⇒ <b>4 发填满</b>、一发约 2 秒动力</td></tr>
 *   <tr><td>转速</td><td>{@value #SPEED}</td><td>恒定；池为 0 时归零</td></tr>
 * </table>
 *
 * <h2>玩家看到的东西</h2>
 * <table>
 *   <tr><td>池</td><td>0</td><td>128</td><td>256</td><td>384</td><td>512（满）</td></tr>
 *   <tr><td><b>显示 SU</b></td><td>0</td><td>4096</td><td>8192</td><td>12288</td><td><b>16384</b></td></tr>
 *   <tr><td>剩余动力</td><td>0</td><td>2 秒</td><td>4 秒</td><td>6 秒</td><td>8 秒</td></tr>
 * </table>
 * <b>参考系</b>：水车 ≈256 SU、大型水车/风车 ≈512 SU、风车满配（16 转 × 512）= 8192 SU
 * ⇒ 满池的冲爆引擎 **16384 SU 约为风车满配的两倍**，而它只在挨炸后的那 8 秒里出力。
 *
 * <h2>损伤阶段（2026-09-28 用户定稿：4 个状态，纯外观；最后一步是"摧毁"）</h2>
 * <pre>
 *   每承受一次爆炸：50% 概率进入下一阶段（**同一场爆炸只判一次**；判定就在 BE 的产生应力那一处）
 *   阶段 0(完好) → 1(轻损) → 2(重损) → 3(损毁)
 *   ★ 四个阶段**全都正常出力**（转速/池/衰减全不变）—— 阶段只影响外观
 *   ★ 已经在第 3 阶段时**再挨一次爆炸 ⇒ 方块被直接摧毁**（掉落自身，玩家要重造一台）
 *   自然冷却：每 **1.6 秒**降一个阶段；六个相邻面每贴着一个硬化块加快 0.3 秒、最多算 4 个 ⇒ 最快 0.4 秒
 *   （2026-09-28 用户把基准从 2.0 调到 1.6："稍微调快一点，要不然数值不太平衡"）
 *   ⚠️ **挨炸会重置这个计时**（beta99 修）—— 冷却只在"没在挨炸"时累积，否则高频轰炸永远攒不起来、
 *      也就永远攒不住档位（用户实机反馈"转化机制很奇怪、毁不掉"就是这个原因）
 *   ⇒ 于是"允许的爆炸间隔 = 冷却时间"：间隔够大就能把档位降回来（**停手能救**），贪高频就会被摧毁
 * </pre>
 *
 * <p>⚠️ 转速恒定还有一个好处：**不会带着整张动力网变速**（Create 一张网只有一个转速，
 * 源的速度一变、挂在网上的机器全都跟着变），也避开了原版"多来源/超速冲突 → destroyBlock"的防呆。
 * 即便如此，仍**建议冲爆引擎单独一张网**。
 */
public class BlastTurbineEngineBlockEntity extends GeneratingKineticBlockEntity {

    /** 固定转速：池 &gt; 0 时一直跑这个速度；池为 0 时归零。 */
    public static final float SPEED = 32.0F;
    /** 应力池上限（**每转速值 per-RPM**，见类注释：满池 ⇒ 512 × 32 = 16384 SU）。 */
    public static final float MAX_STRESS = 512.0F;
    /** 每秒自然衰减（per-RPM 口径）：64/秒 = 池的 12.5%/秒 ⇒ 满池 8 秒见底。 */
    public static final float STRESS_DECAY_PER_SECOND = 64.0F;
    /** 原版爆炸强度 f → 应力的倍率（per-RPM 口径）：贴脸 TNT 约 +125，4 发填满。 */
    public static final float STRESS_PER_STRENGTH = 64.0F;

    /** 每 tick 的衰减量。 */
    private static final float STRESS_DECAY_PER_TICK = STRESS_DECAY_PER_SECOND / 20.0F;
    /** 容量变化 / 客户端同步的节流间隔（4 次/秒足够；爆炸充能时立即推）。 */
    private static final int SYNC_INTERVAL_TICKS = 5;

    // ---------------------------------------------------------------- 损伤阶段（用户 2026-09-27 定）

    /** 最高阶段 = 损毁（0 完好 / 1 轻损 / 2 重损 / 3 损毁）。 */
    public static final int MAX_STAGE = 3;
    /** 每承受一次爆炸进入下一阶段的概率（用户定：50%）。 */
    public static final float PROMOTE_CHANCE = 0.5F;
    /** 自然冷却的基础间隔：**1.6 秒（32 刻）**降一个阶段（用户 2026-09-28：原来 2 秒偏慢，调成 1.6）。 */
    public static final int COOL_TICKS_BASE = 32;
    /** 六个相邻面每贴着一个硬化块，冷却加快 0.3 秒（6 刻）。 */
    public static final int COOL_TICKS_PER_HARDENED = 6;
    /** 最多计 4 个硬化块 ⇒ 最快 1.6 − 4 × 0.3 = 0.4 秒（8 刻）。 */
    public static final int MAX_HARDENED_NEIGHBOURS = 4;

    private static final String NBT_STRESS = "BlastStress";
    private static final String NBT_STAGE = "BlastStage";

    /** 应力池（per-RPM）。转速由它推出来，不另外存。 */
    private float stress;

    /** 损伤阶段（0..{@value #MAX_STAGE}）。**只影响外观与"还能不能工作"，不影响任何数值**（用户 2026-09-27 明确）。 */
    private int stage;
    /** 距离下一次降温还剩多少刻（只在 {@code stage > 0} 时有意义）。 */
    private int coolTicks;

    public BlastTurbineEngineBlockEntity(BlockPos pos, BlockState state) {
        super(CreateHardener.BLAST_TURBINE_ENGINE_BE.get(), pos, state);
    }

    /**
     * 原版 {@code SmartBlockEntity} 把这一句声明成 abstract，必须实现。
     * 冲爆引擎**不需要任何 behaviour**：没有滚轮、没有库存、没有流体 —— 全部逻辑都在本类里。
     */
    @Override
    public void addBehaviours(java.util.List<com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour> behaviours) {
        // 有意留空
    }

    // ---------------------------------------------------------------- 原版骨架要的两口子

    /** 转速**由池推出来**（不单独存字段）：池 &gt; 0 就跑 {@link #SPEED}，池 = 0 就停。**损伤阶段不影响出力**。 */
    @Override
    public float getGeneratedSpeed() {
        return stress > 0.0F ? SPEED : 0.0F;
    }

    /**
     * 我们的容量 = 应力池本身（per-RPM）。原版实现只会去 {@code BlockStressValues.CAPACITIES}
     * 查一个固定值；这里照旧覆写，但**只返回池值，转速不再参与**（转速已经在网络那一层乘过了）。
     */
    @Override
    public float calculateAddedStressCapacity() {
        float capacity = stress;                             // 损伤阶段不影响容量
        lastCapacityProvided = capacity;
        return capacity;
    }

    // ---------------------------------------------------------------- 唯一的曲线：池的衰减

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;                     // 池只在服务端推进；客户端靠 read/write 同步
        }
        tickCooling();                  // 冷却与池无关：池空了照样要降温
        if (stress <= 0.0F) {
            return;                     // 没充过能 / 已经停了 → 不用再推同步
        }

        stress = Math.max(0.0F, stress - STRESS_DECAY_PER_TICK);
        if (stress <= 0.0F) {
            // 池见底 → 转速归零（getGeneratedSpeed() 现在返回 0，这一句会把速度改动推下去）
            stress = 0.0F;
            updateGeneratedRotation();
            pushCapacity();
            sendUpdate();
            return;
        }
        // 连续衰减不必每 tick 都推（那等于每 tick 一个包）：节流到 4 次/秒
        if (level.getGameTime() % SYNC_INTERVAL_TICKS == 0L) {
            pushCapacity();
            sendUpdate();
        }
    }

    /**
     * 被一次爆炸打中（由 {@link BlastTurbineEngineCharge} 调用）。
     *
     * <p>★ <b>"产生应力"和"损伤判定"就在这一处</b>（2026-09-28 用户要求：把"承受一次爆炸"的检测挪到
     * 产生应力的地方 —— 挨一次爆炸加一次应力，就在这个时机推进损伤阶段）。
     *
     * @param deltaStrength 相对"这次爆炸已经记过的最大值"新增的那部分**原版强度 f** —— 同一次爆炸会有多条
     *                      射线打到我们身上，取值规则是"取最大"，因此只补差额，避免同一次爆炸被重复计数。
     * @param firstHitOfThisExplosion 这**一场**爆炸是否第一次真正往这台引擎里加应力。⚠️ 必须靠它保证
     *                      "一场爆炸只判一次"：本方法会被同一场爆炸用越来越大的 f 调用多次。
     */
    public void addBlastCharge(float deltaStrength, boolean firstHitOfThisExplosion) {
        if (level == null || level.isClientSide) {
            return;
        }
        if (firstHitOfThisExplosion) {
            // ① 挨炸 ⇒ 重置自然冷却计时（用户规格："**正常情况下**每两秒冷却"里的"正常情况" = 没在挨炸）
            coolTicks = coolIntervalTicks();
            // ② 已经在最后一个阶段（仍然正常工作）再挨一次 ⇒ 直接摧毁
            if (isAtLastStage()) {
                destroyEngine();
                return;
            }
            // ③ 否则 50% 概率进下一阶段
            if (level.random.nextFloat() < PROMOTE_CHANCE) {
                setStage(stage + 1);
            }
        }
        if (deltaStrength > 0.0F) {
            // ⚠️ 这里**必须乘倍率**（beta92 修过一次：漏乘 ⇒ 加进来只有 2 点、被每 tick 的衰减吃光 ⇒ 看起来完全没反应）
            stress = Math.min(MAX_STRESS, stress + deltaStrength * STRESS_PER_STRENGTH);
        }
        // 池从 0 变成 > 0 时，这一句会让引擎"自我建网并挂上去"（见 GeneratingKineticBlockEntity.applyNewSpeed
        // 的 prevSpeed == 0 分支：setSpeed + setNetwork(createNetworkId) + attachKinetics）—— 所以必须调它。
        updateGeneratedRotation();
        pushCapacity();
        sendUpdate();
    }

    /**
     * 把当前容量推给管网。
     *
     * <p>⚠️ <b>必须先判 {@code hasNetwork()}（beta90 修，实机崩因）</b>：Create 的
     * {@code notifyStressCapacityChange} 内部**直接**就是 {@code getOrCreateNetwork().updateCapacityFor(...)}，
     * 而 {@code getOrCreateNetwork()} **可能返回 null**（典型时刻：爆炸刚把旁边的传动轴炸掉、动力网正在解散）。
     */
    private void pushCapacity() {
        if (hasNetwork()) {
            notifyStressCapacityChange(stress);
        }
    }

    /** 一次性把"网络要的"和"客户端要的"都推出去。 */
    private void sendUpdate() {
        setChanged();
        notifyUpdate();
    }

    // ---------------------------------------------------------------- 损伤阶段 / 自然冷却

    /** 是否已到**最后一个阶段**（外观最破，但**仍然正常出力**；再挨一次爆炸就会被摧毁）。 */
    public boolean isAtLastStage() {
        return stage >= MAX_STAGE;
    }

    /**
     * **摧毁**：把方块破坏掉并**掉落自身**（= 玩家必须重造一台，这就是风险管控的代价）。
     *
     * <p>⚠️ 这个调用发生在 {@code Explosion.explode()} 的射线循环内部（我们的 mixin 是在
     * {@code shouldBlockExplode} 上取 f 的）—— 但它只动我们自己这一格、不会让原版循环重入，
     * 同一场爆炸后面那些射线读到的也只是空气 ⇒ 安全。
     */
    private void destroyEngine() {
        if (level != null && !level.isClientSide) {
            level.destroyBlock(worldPosition, true);
        }
    }

    /**
     * 护目镜里把阶段**数字**显示出来。
     * 加它的原因：用户实机反馈"看不出到底在第几档"，有了这行就不用靠贴图颜色猜。
     */
    @Override
    public boolean addToGoggleTooltip(java.util.List<net.minecraft.network.chat.Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);   // 保留原版那几行（转速 / 应力）
        // ⚠️ 前面空两格：不空的话这行会盖住护目镜左侧那个方块图标（用户 2026-09-28 指出）
        tooltip.add(net.minecraft.network.chat.Component.literal("  ").append(
                net.minecraft.network.chat.Component.translatable(
                        "createhardener.goggles.engine_stage", stage, MAX_STAGE)));
        return true;                    // 我们自己总会加一行 ⇒ 恒为 true
    }

    /**
     * 自然冷却：每 {@link #COOL_TICKS_BASE} 刻降一个阶段；六个相邻面每贴着一个硬化块少
     * {@link #COOL_TICKS_PER_HARDENED} 刻、最多算 {@link #MAX_HARDENED_NEIGHBOURS} 个
     * ⇒ **1.6 / 1.3 / 1.0 / 0.7 / 0.4 秒**（用户 2026-09-28 把基准从 2.0 调到 1.6）。
     */
    private void tickCooling() {
        if (stage <= 0) {
            coolTicks = 0;
            return;                     // 完好 ⇒ 一行不算（顺带省掉邻居扫描）
        }
        // 注意：**最后一个阶段也会降温**（停手 2 秒就能从最破降回一档）—— 这是"停手能救"的关键
        int interval = coolIntervalTicks();
        if (coolTicks > interval) {
            coolTicks = interval;       // 刚贴上硬化块 ⇒ 立刻按更短的间隔走
        }
        if (--coolTicks <= 0) {
            setStage(stage - 1);
            coolTicks = coolIntervalTicks();
        }
    }

    /** 冷却间隔（刻）= 32 − 6 × min(4, 周围硬化块数) ⇒ 1.6 / 1.3 / 1.0 / 0.7 / 0.4 秒。 */
    private int coolIntervalTicks() {
        return COOL_TICKS_BASE - COOL_TICKS_PER_HARDENED * Math.min(MAX_HARDENED_NEIGHBOURS, countHardenedNeighbours());
    }

    /**
     * 数六个相邻面里有几个硬化块（整砖/半砖/楼梯、任意风化档都算）。
     * 用 {@link Hardening#stageOfBlock} 判 —— 它只认我们自己的方块（顺便把原版铜排除在外）。
     */
    private int countHardenedNeighbours() {
        if (level == null) {
            return 0;
        }
        int found = 0;
        for (Direction dir : Direction.values()) {
            if (Hardening.stageOfBlock(level.getBlockState(worldPosition.relative(dir)).getBlock()) >= 0) {
                found++;
            }
        }
        return found;
    }

    /**
     * 改阶段：写进方块状态（外观 = 换贴图）+ 存 NBT + 推同步。
     * 走到最高阶段（损毁）时顺手清空应力池并停转。
     */
    private void setStage(int newStage) {
        newStage = Mth.clamp(newStage, 0, MAX_STAGE);
        if (newStage == stage) {
            return;
        }
        stage = newStage;
        BlockState state = getBlockState();
        if (level != null && state.hasProperty(BlastTurbineEngineBlock.STAGE)
                && state.getValue(BlastTurbineEngineBlock.STAGE) != stage) {
            level.setBlock(worldPosition, state.setValue(BlastTurbineEngineBlock.STAGE, stage), Block.UPDATE_ALL);
        }
        // 阶段只换外观：**不动池、不动转速**（用户 2026-09-28 明确"四个阶段没什么实际区别"）
        coolTicks = coolIntervalTicks();
        sendUpdate();
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        stress = tag.getFloat(NBT_STRESS);
        stage = Mth.clamp(tag.getInt(NBT_STAGE), 0, MAX_STAGE);
        coolTicks = coolIntervalTicks();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putFloat(NBT_STRESS, stress);
        tag.putInt(NBT_STAGE, stage);
    }

    /** 当前应力池（per-RPM）；玩家看到的 SU 是它的 {@value #SPEED} 倍。给调试/命令用。 */
    public float getStress() {
        return stress;
    }
}
