package dev.createhardener.sealant;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import dev.createhardener.CreateHardener;
import dev.createhardener.SealShellBlockEntity;
import dev.createhardener.SealShellBlocks;

/**
 * 塑封外壳的安装 / 拆除。
 *
 * <p><b>外壳 = 自定义的完整方块伪装方块</b>（{@code createhardener:sealed_shell}）：
 * 完整立方体、硬度 25 / 爆炸抗性 600；客户端渲染器把被套方块的原模型画出来，
 * 所以套壳后**外观完全不变**（见 {@link dev.createhardener.client.SealShellRenderer}）。
 * 被套方块的**状态与方块实体 NBT** 存进 {@link SealShellData}，外壳被拆时原样放回。
 */
public final class SealShellHelper {

    /** 单次塑封最多套多少格。 */
    public static final int MAX_SHELLS = 4096;

    private SealShellHelper() {
    }

    public static int shellCount(ServerLevel level) {
        return SealShellData.get(level).size();
    }

    public static List<BlockPos> allShellPositions(ServerLevel level) {
        return SealShellData.get(level).positions();
    }

    public static boolean hasShell(ServerLevel level, BlockPos pos) {
        return SealShellData.get(level).isShell(level, pos);
    }

    public static boolean isShellBlock(BlockState state) {
        return state != null && state.is(SealShellBlocks.SEALED_SHELL.get());
    }

    /**
     * 给一片区域套壳。玩家只是圈定范围，**套不套由这里逐格静默决定**（不向玩家报错）。
     *
     * @param maxShells 本次最多套几格（由塑封剂剩余耐久决定）
     * @return 实际套上的数量
     */
    public static int applyTo(ServerLevel level, BlockPos min, BlockPos max, int maxShells) {
        SealShellData data = SealShellData.get(level);
        int placed = 0;

        for (BlockPos cursor : BlockPos.betweenClosed(min, max)) {
            if (placed >= maxShells || placed >= MAX_SHELLS) {
                break;
            }
            BlockPos pos = cursor.immutable();
            if (data.isShell(level, pos)) {
                continue;                       // 已套过，不重复
            }
            BlockState state = level.getBlockState(pos);
            if (!isSealable(level, pos, state)) {
                continue;                       // 静默跳过
            }
            if (!coat(level, pos, state)) {
                continue;
            }
            placed++;
        }
        return placed;
    }

    /**
     * 该格能不能套壳 —— **弱判定**，只排除明确不该动的：
     * ① 空气；② 黑名单标签（基岩/屏障/容器/可交互方块…）；
     * ③ 已套壳；④ 带方块实体的方块（套壳会失去交互）；
     * ⑤ 非完整碰撞形状（台阶/楼梯/栅栏等，套壳后碰撞体会变成完整立方体）。
     * 其余一律允许；不允许就静默跳过，**不向玩家报错、不影响他的操作节奏**。
     */
    public static boolean isSealable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (state.is(CreateHardener.SEAL_BLACKLIST)) {
            return false;
        }
        if (isShellBlock(state)) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.isCollisionShapeFullBlock(level, pos);
    }

    /** 把一格方块包进外壳：原状态记进方块实体（客户端照着渲染）与 {@link SealShellData}（拆壳时还原）。 */
    public static boolean coat(ServerLevel level, BlockPos pos, BlockState original) {
        CompoundTag beNbt = null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            beNbt = be.saveWithFullMetadata(level.registryAccess());
        }

        level.setBlock(pos, SealShellBlocks.SEALED_SHELL.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof SealShellBlockEntity shell) {
            shell.setMaterial(original);
        } else {
            return false;
        }
        SealShellData.get(level).put(level, pos, original, beNbt);
        return true;
    }

    /** 拆掉一格外壳并把原方块放回原位（含方块实体数据）。 */
    public static boolean breakShell(ServerLevel level, BlockPos pos) {
        SealShellData data = SealShellData.get(level);
        SealShellData.ShellEntry entry = data.entry(level, pos);
        if (entry == null) {
            return false;
        }
        BlockState original = entry.state();

        level.levelEvent(2001, pos, Block.getId(original)); // 原方块的破坏粒子
        level.setBlock(pos, original, Block.UPDATE_ALL);

        if (entry.blockEntityNbt() != null) {
            CompoundTag nbt = entry.blockEntityNbt().copy();
            BlockEntity restored = BlockEntity.loadStatic(pos, original, nbt, level.registryAccess());
            if (restored != null) {
                level.setBlockEntity(restored);
            }
        }

        data.remove(level, pos);
        refreshClient(level, pos);
        return true;
    }

    /**
     * 强制把这一格重新同步给客户端（含该格的方块实体数据）。
     *
     * <p><b>为什么必须自己补这一步（2026-09-24 用户实测 bug）</b>：航空学搬方块**不走原版
     * {@code Level.setBlock} 的完整流程** —— {@code SubLevelAssemblyHelper.moveBlocks} 直接调
     * {@code LevelChunk.setBlockState(pos, state, false)} 改区块数据，整个方法的字节码里
     * **没有任何 {@code removeBlockEntity} / {@code Level.setBlockEntity}**，最后只补发一次
     * {@code sendBlockUpdated(...)}。于是那一格的**方块实体在客户端留下残留**：
     * 表现就是用户报的"**有碰撞、完全不显示**"（'物理组装器去掉物理化后，被塑封的方块变成有碰撞
     * 但不显示的方块，站在上面会卡住抽搐'），而随便在附近交互一下、放个方块触发正常同步就全部恢复。
     *
     * <p>这里是纯**显示层**兜底：服务端世界数据、塑封记录、拆壳还原一直都是对的，不会坏档。
     * 开销只在"真的改了方块"这一处发生（正常玩几乎不触发），不影响热路径。
     */
    private static void refreshClient(ServerLevel level, BlockPos pos) {
        BlockState now = level.getBlockState(pos);
        level.sendBlockUpdated(pos, now, now, Block.UPDATE_ALL);
        // 顺带合并登记一次描边同步（真正的发包被合并到本 tick 末，见 SealZoneSync）
        SealZoneSync.sendAll(level);
    }

    /**
     * **方案 A1 的核心动作**：把这一格的塑封记录解绑掉（删记录、**不动世界**）。
     *
     * <p>调用点是 {@code SealShellBlock#beforeMove}（航空学把方块搬进子世界之前）。
     * 记录先没了，航空学搬走的就是**干干净净的原方块**，也就不会产生"幽灵记录"
     * （幽灵记录的表现：框选不消失、之后任意一次爆炸把原方块塞回空气格）。
     */
    public static void unbindRecord(ServerLevel level, BlockPos pos) {
        SealShellData.get(level).remove(level, pos);
    }

    /**
     * **方案 B**：脏壳自毁。
     *
     * <p>「脏壳」= 世界里是塑封外壳方块，但**这一格没有对应的塑封记录**。
     * 典型来源就是方案 A1 之后的航空学搬运：记录在搬运前解绑了，外壳本体却跟着方块实体
     * 进了子世界。留着它就是个"挖了不还原、掉落也不对"的坏方块，所以直接让它消失。
     *
     * <p>安全性：正常套壳一定会同时写入记录（见 {@link #coat}），所以合法外壳**不会**被误杀；
     * 子世界里手动塑封的方块也有自己的记录，同样不会被误杀 —— 删的只是"没有主人的壳"。
     */
    public static void scrubStale(ServerLevel level, BlockPos pos) {
        if (!isShellBlock(level.getBlockState(pos))) {
            return;
        }
        if (SealShellData.get(level).isShell(level, pos)) {
            return;                          // 记录健在 → 正常外壳
        }
        level.removeBlock(pos, false);
        SealZoneSync.sendAll(level);
        if (SealZoneEvents.DEBUG_LOG) {
            org.slf4j.LoggerFactory.getLogger("createhardener-seal")
                    .info("[塑封] 脏壳自毁（该格没有塑封记录）pos={}", pos);
        }
    }

    /**
     * **方案 B 的可靠落点**：把某一格的塑封外壳**无条件**删掉（同时清掉它的记录）。
     *
     * <p>调用点是 {@code SealShellBlock#afterMove} —— 航空学把方块放到目标位置之后。
     * 与 {@link #scrubStale} 的区别：这里**不看记录**（哪怕那一格还有"合法记录"也照删），
     * 因为已经确定"这个方块是被搬过来的"，它就不该继续以壳的形态存在。
     *
     * <p>必须这么做的实测理由（用户 2026-09-24）：只靠 {@link #unbindRecord} 不够稳，
     * 会出现**同一格既有真方块又有塑封外壳**的叠加态（玉能看到两个 ID、换个角度就变样）。
     */
    public static void removeShellAt(ServerLevel level, BlockPos pos) {
        if (!isShellBlock(level.getBlockState(pos))) {
            return;
        }
        SealShellData.get(level).remove(level, pos);
        // 尽量把"它冒充的那个方块"还原回该格，保证底下不会变成空气
        BlockState material = (level.getBlockEntity(pos) instanceof SealShellBlockEntity shell)
                ? shell.material() : null;
        if (material != null && !material.isAir() && material != level.getBlockState(pos)) {
            level.setBlock(pos, material, Block.UPDATE_ALL);
        } else {
            level.removeBlock(pos, false);
        }
        refreshClient(level, pos);
        if (SealZoneEvents.DEBUG_LOG) {
            org.slf4j.LoggerFactory.getLogger("createhardener-seal")
                    .info("[塑封] 搬运落点撕壳 pos={} 还原为 {}", pos, material);
        }
    }

    /** 外壳被爆炸震碎（方块本体保留，只掉壳）。 */
    public static void stripShells(ServerLevel level, List<BlockPos> positions) {
        for (BlockPos pos : positions) {
            breakShell(level, pos);
        }
    }
}
