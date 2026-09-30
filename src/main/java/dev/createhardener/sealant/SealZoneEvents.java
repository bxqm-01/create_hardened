package dev.createhardener.sealant;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import dev.createhardener.CreateHardener;

/**
 * 塑封外壳的行为逻辑 —— 全部走 NeoForge 事件，**不使用 Mixin**。
 *
 * <p><b>机制（用户 2026-09-23 定稿：套壳）</b>：
 * <ul>
 *   <li><b>挖掘</b>：挖到有外壳的方块 → **取消这次破坏**（方块本体不掉），只拆掉该格外壳并把原方块放回。
 *       也就是"先挖掉外壳，再挖才掉方块"。</li>
 *   <li><b>爆炸</b>：波及到的外壳**全部被震碎**（方块本体保住），**第二次爆炸**才会炸掉方块。</li>
 *   <li>外壳逐格独立 —— 拆掉一格不影响其他格。</li>
 * </ul>
 *
 * <p>框选的结算入口是 {@link #applySelection}（由 {@link SealSelectionPacket} 触发）——
 * 两次点击之间服务端不持有任何状态，所以不存在前后手不同步的问题。
 */
public final class SealZoneEvents {

    /** 允许塑封的单边上限（真正的限制是塑封剂耐久，这里只防手滑）。 */
    public static final int MAX_EDGE = 32;

    /** 调试开关：true 时打日志便于验收；验收完改回 false。 */
    public static final boolean DEBUG_LOG = false;

    /** 孤儿记录巡检间隔（tick）。 */
    private static final int SCAN_INTERVAL = 100;

    private static int tickCounter;

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("createhardener-seal");

    private SealZoneEvents() {
    }

    // ------------------------------------------------------------ 框选结算（客户端一次性提交两个角）

    /**
     * 结算一次框选。客户端已经把两个角一次性发过来了（{@link SealSelectionPacket}），
     * 服务端在这里**自己**扫这一片、逐格静默判定、扣耐久、回提示。
     */
    public static void applySelection(ServerPlayer player, BlockPos from, BlockPos to, int remaining) {
        ServerLevel level = player.serverLevel();
        SealZone zone = SealZone.of(from, to);

        if (zone.sizeX() > MAX_EDGE || zone.sizeY() > MAX_EDGE || zone.sizeZ() > MAX_EDGE) {
            player.displayClientMessage(Component.translatable(
                    "message.createhardener.sealant.too_big", MAX_EDGE).withStyle(ChatFormatting.RED), true);
            return;
        }
        if (remaining <= 0) {
            player.displayClientMessage(Component.translatable(
                    "message.createhardener.sealant.not_enough_durability", 1, 0)
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        int placed = SealShellHelper.applyTo(level, zone.min(), zone.max(), remaining);
        if (placed <= 0) {
            player.displayClientMessage(Component.translatable(
                    "message.createhardener.sealant.nothing_to_seal").withStyle(ChatFormatting.RED), true);
            return;
        }

        // 耐久：每装一格外壳消耗 1 点（用户 2026-09-23 明确：消耗玩家资源是可接受范围，不退）
        if (!player.getAbilities().instabuild) {
            ItemStack stack = player.getMainHandItem().is(CreateHardener.OBSIDIAN_SEALANT.get())
                    ? player.getMainHandItem() : player.getOffhandItem();
            if (stack.is(CreateHardener.OBSIDIAN_SEALANT.get())) {
                stack.hurtAndBreak(placed, level, player,
                        item -> player.onEquippedItemBroken(item, net.minecraft.world.entity.EquipmentSlot.MAINHAND));
            }
        }

        SealZoneSync.sendAll(level);
        player.displayClientMessage(Component.translatable("message.createhardener.sealant.sealed",
                placed, zone.sizeX(), zone.sizeY(), zone.sizeZ()).withStyle(ChatFormatting.GREEN), true);
        // 放置音用黑曜石的（原版 OBSIDIAN 走 SoundType.STONE）；破坏音保持玻璃（见下）。
        level.playSound(null, to, SoundType.STONE.getPlaceSound(), SoundSource.BLOCKS, 0.9F, 0.9F);

        if (DEBUG_LOG) {
            LOG.info("[塑封] {} 套壳 {} 格 from={} to={}", player.getName().getString(), placed, from, to);
        }    }

    // ------------------------------------------------------------ 挖掘：先挖壳

    /** 挖到外壳方块 → 取消破坏，改为拆掉外壳并把原方块放回。 */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.isClientSide) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        BlockPos pos = event.getPos();
        if (!SealShellHelper.hasShell(level, pos)) {
            return;
        }
        // 有记录、但世界里的方块**其实不是**外壳（命令 /setblock、别的模组覆盖过）→ 不拦，
        // 免得玩家挖不动。
        // ⚠️ 2026-09-26 修：原先写的是 `getBlockEntity(pos) == null && entry(level, pos) == null`，
        //    而 hasShell 本身就是 `entry != null`，所以第二项恒假、整个条件**恒假（死代码）**。
        //    后果是"陈旧记录窗口"（孤儿巡检最长 100 tick）内，玩家挖那格会被 cancel，
        //    并被 `breakShell` 把记录里的老方块**写回**、覆盖他正在挖的方块（表现为"挖不掉还变样"）。
        if (!SealShellHelper.isShellBlock(level.getBlockState(pos))) {
            return;
        }

        // 创造模式：**不取消事件** —— breakShell 先把原方块还原，随后原版把它整格清掉，
        // 净效果就是"直接清掉"，方便拿创造模式快速清理一大片（不掉落）。
        // ⚠️ 这与生存模式语义不同（生存是先还原、不掉落）—— 这是 2026-09-26 用户拍板保留的行为。
        if (player.getAbilities().instabuild) {
            SealShellHelper.breakShell(level, pos);
            SealZoneSync.sendAll(level);
            return;
        }

        event.setCanceled(true);
        if (!SealShellHelper.breakShell(level, pos)) {
            return;
        }
        SealZoneSync.sendAll(level);
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.8F, 0.6F);
        if (DEBUG_LOG) {
            LOG.info("[塑封] 外壳被挖碎 pos={}", pos);
        }
    }

    // ------------------------------------------------------------ 爆炸：震碎壳

    /** 爆炸：把波及到的外壳全部震碎（方块本体保住）；第二次爆炸才炸掉方块。 */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<BlockPos> affected = event.getAffectedBlocks();
        if (affected.isEmpty()) {
            return;
        }
        SealShellData data = SealShellData.get(level);
        if (data.size() == 0) {
            return;
        }

        List<BlockPos> shelled = new ArrayList<>();
        for (BlockPos pos : affected) {
            if (data.isShell(level, pos)) {
                shelled.add(pos);
            }
        }
        if (shelled.isEmpty()) {
            return;
        }

        // ⚠️ 用 HashSet 包一层：List.removeAll(Collection) 是"遍历自己、对每个元素调 c.contains"，
        //    而 ArrayList.contains 是 O(m) → 原先是 O(n×m)（大爆炸 n=1e4 / m=1e3 就是 1e7 次
        //    BlockPos.equals）。包成 HashSet 后 contains 变 O(1)，整体 O(n)。
        affected.removeAll(new java.util.HashSet<>(shelled));   // 本体先移出爆炸列表 —— 不掉
        SealShellHelper.stripShells(level, shelled);            // 外壳全被震碎
        SealZoneSync.sendAll(level);

        if (event.getExplosion() != null) {
            level.playSound(null, BlockPos.containing(event.getExplosion().center()),
                    SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.0F, 0.5F);
        }
        if (DEBUG_LOG) {
            LOG.info("[塑封] 爆炸震碎外壳 {} 格", shelled.size());
        }
    }

    // ------------------------------------------------------------ 方案 A1 的兜底：孤儿记录巡检

    /**
     * **方案 A1 的第二半**：定时巡检塑封记录，把**孤儿记录**清掉。
     *
     * <p>正常的物理化路径由 {@code SealShellBlock#beforeMove} 处理
     * （搬运前就把记录解绑）。这里防的是"钩子没触发的路径"（命令、复制方块实体等）：
     * 记录还在、但那个位置**已经加载**且**方块已经不是外壳** → 这条记录就是幽灵，删掉。
     *
     * <p>⚠️ 必须先判 {@link Level#isLoaded}，否则会把"区块没加载"误判成"方块没了"，
     * 那就会在世界各处误删记录。
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < SCAN_INTERVAL) {
            return;
        }
        tickCounter = 0;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            scrubOrphans(level);
        }
    }

    /** 清掉该维度里的全部孤儿记录，返回清掉的数量。 */
    public static int scrubOrphans(ServerLevel level) {
        SealShellData data = SealShellData.get(level);
        if (!data.hasAny()) {
            return 0;
        }
        List<BlockPos> orphans = new ArrayList<>();
        for (BlockPos pos : data.positionSet()) {
            if (!level.isLoaded(pos)) {
                continue;                       // 区块没加载 → 无法判断，跳过（绝不能当成"方块没了"）
            }
            if (!SealShellHelper.isShellBlock(level.getBlockState(pos))) {
                orphans.add(pos);
            }
        }
        for (BlockPos pos : orphans) {
            data.remove(level, pos);            // 带维度：只删自己这个维度的记录
            if (DEBUG_LOG) {
                LOG.info("[塑封] 清除孤儿记录（该格已不是外壳）pos={}", pos);
            }
        }
        if (!orphans.isEmpty()) {
            SealZoneSync.sendAll(level);
        }
        return orphans.size();
    }
}
