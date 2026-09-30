package dev.createhardener.sealant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：同步**该维度全部已装外壳的坐标**，供客户端描边显示。
 * 只传坐标（`asLong` 压缩），256 格外壳也只有约 2KB。
 *
 * <h2>为什么要"合并到每 tick 一次"（2026-09-24 加固）</h2>
 * 原来每次外壳变化都**立刻**给全体玩家发一张**全量表**。单独一次没问题，但
 * {@link SealZoneEvents#onExplosionDetonate} 是**每次爆炸**都调一次 —— 连爆 / TNT 链时
 * 一个 tick 里能触发十几次全量广播，属于"能玩但给服务器添堵"。
 * 改法**不改语义**：{@link #sendAll} 只把该维度登记成"脏"，真正的发送合并到本服务端 tick 末
 * （{@link #onServerTick}）执行一次。
 *
 * <h2>2026-09-26 两处修复（审查发现）</h2>
 * <ol>
 *   <li><b>"全世界累计超 32768 格外壳就会踢人"</b>：包体是**该维度的全表**，而编解码用的是
 *       {@code ByteBufCodecs.list(MAX_SHELLS_PER_PACKET)} —— 一旦超过上限，编码直接抛异常 → 掉线。
 *       那个常量原本是按"一次框选 32³"定的，却承担了"世界累计量"的上限，属于设计错误。
 *       现在改成**分包**：每包最多 {@link #CHUNK_SIZE} 条，客户端收齐后再整体替换，
 *       上限只用来兜住单包体积，世界规模不再受它限制。</li>
 *   <li><b>广播给了所有维度的玩家、且包内不带维度</b>：别的维度会看到幽灵紫框；更糟的是
 *       两个维度同 tick 都脏时，后发的包会**覆盖**前一个（客户端是替换语义）→ 玩家自己维度的
 *       描边凭空消失。现在只发给 {@code player.level() == level} 的玩家。</li>
 * </ol>
 */
public record SealZoneSync(List<BlockPos> shells, int partIndex, int partCount) implements CustomPacketPayload {

    public static final Type<SealZoneSync> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(dev.createhardener.CreateHardener.MODID, "seal_zone_sync"));

    /**
     * **单包**最多几条外壳坐标。
     *
     * <p>它现在只约束"一包多大"（解码侧也是防呆上限），**不再**限制一个世界的总外壳数 ——
     * 超出就分包（见 {@link #flush}）。
     */
    private static final int MAX_SHELLS_PER_PACKET = 32768;

    /** 每包实际装多少条。留足余量，避免贴着上限。 */
    private static final int CHUNK_SIZE = 16384;

    public static final StreamCodec<RegistryFriendlyByteBuf, SealZoneSync> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG.map(BlockPos::of, BlockPos::asLong)
                            .apply(ByteBufCodecs.list(MAX_SHELLS_PER_PACKET)),
                    SealZoneSync::shells,
                    ByteBufCodecs.VAR_INT,
                    SealZoneSync::partIndex,
                    ByteBufCodecs.VAR_INT,
                    SealZoneSync::partCount,
                    SealZoneSync::new);

    /** 本 tick 里被改动过外壳、待同步的维度。 */
    private static final Set<ServerLevel> DIRTY_LEVELS = new HashSet<>();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 登记"这个维度需要重新同步描边"。**不再立刻发包** —— 真正的发送在本服务端 tick 末合并执行。
     */
    public static void sendAll(ServerLevel level) {
        if (level != null) {
            DIRTY_LEVELS.add(level);
        }
    }

    /** 每服务端 tick 末，把本 tick 攒下的脏维度各发一次。 */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DIRTY_LEVELS.isEmpty()) {
            return;
        }
        List<ServerLevel> pending = new ArrayList<>(DIRTY_LEVELS);
        DIRTY_LEVELS.clear();
        for (ServerLevel level : pending) {
            flush(level);
        }
    }

    /**
     * 真正发包：把该维度当前的全部外壳坐标推给**该维度**的所有玩家，超量时分包。
     *
     * <p>注意空表也要发一包（{@code partCount = 1}）—— 那是"外壳都没了，清掉客户端描边"的唯一信号。
     */
    private static void flush(ServerLevel level) {
        if (level.getServer() == null) {
            return;
        }
        List<ServerPlayer> players = level.getServer().getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        List<BlockPos> all = SealShellHelper.allShellPositions(level);
        int parts = Math.max(1, (all.size() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int index = 0; index < parts; index++) {
            int from = index * CHUNK_SIZE;
            int to = Math.min(all.size(), from + CHUNK_SIZE);
            SealZoneSync payload = new SealZoneSync(List.copyOf(all.subList(from, to)), index, parts);
            for (ServerPlayer player : players) {
                // ⚠️ 只发本维度的玩家（2026-09-26 修）：否则别的维度会看到幽灵框，
                //    而且多维度同 tick 都脏时客户端会被后到的包覆盖掉自己那份。
                //    ServerLevel 每个维度只有一个实例，直接比引用即可。
                if (player.level() == level) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }

    public static void handle(SealZoneSync payload, IPayloadContext context) {
        context.enqueueWork(() ->
                SealZoneClient.receiveChunk(payload.partIndex(), payload.partCount(), payload.shells()));
    }
}
