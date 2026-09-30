package dev.createhardener.sealant;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：**一次性提交整个框选**（两个角 + 本刻剩余耐久）。
 *
 * <p>照 Create 超级胶水的状态模型：**两次点击之间服务端手里什么都没有**，
 * 所以不存在"客户端以为这是第一次点击、服务端以为是第二次"这种前后手颠倒的问题
 * —— 服务端**根本没有可以错位的中间状态**。
 *
 * <p>服务端收到后才做全部判定（哪些格可塑封、实际套了几格、扣多少耐久）。
 */
public record SealSelectionPacket(BlockPos from, BlockPos to, int remaining)
        implements CustomPacketPayload {

    public static final Type<SealSelectionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(dev.createhardener.CreateHardener.MODID, "seal_selection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SealSelectionPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SealSelectionPacket::from,
                    BlockPos.STREAM_CODEC, SealSelectionPacket::to,
                    ByteBufCodecs.VAR_INT, SealSelectionPacket::remaining,
                    SealSelectionPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端提交框选。 */
    public static void sendToServer(BlockPos from, BlockPos to, int remaining) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new SealSelectionPacket(from, to, remaining));
    }

    public static void handle(SealSelectionPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer player)) {
                return;
            }
            SealZoneEvents.applySelection(player, payload.from(), payload.to(), payload.remaining());        });
    }
}
