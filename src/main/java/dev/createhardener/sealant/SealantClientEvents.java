package dev.createhardener.sealant;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import dev.createhardener.CreateHardener;

/**
 * 客户端侧：**整个两次点击框选的唯一实现处**（2026-09-24 定稿）。
 *
 * <p><b>血的教训（务必别再拆）</b>：曾把状态机拆成"事件层写起点 + {@code useOn} 读并结算"两半，
 * 结果**两次点击永远只被当成第一次** → 表现为"只能一个个点击塑封"、紫色选框完全不显示。
 * 根因是**客户端 `RightClickBlock` 事件在 `Item#useOn` 之前触发**：
 * 事件先把 pending 清掉，`useOn` 再跑时已经什么都不剩。
 *
 * <p>所以约定：**判读与清除框选状态的动作只能有一个所有者**（就是这里）。
 * {@link ObsidianSealantItem#useOn} 不读、不写、不清任何框选状态，只负责"吞掉这次点击"。
 */
@EventBusSubscriber(modid = CreateHardener.MODID, value = Dist.CLIENT)
public final class SealantClientEvents {

    private SealantClientEvents() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (player == null || !player.level().isClientSide) {
            return;
        }
        if (!event.getItemStack().is(CreateHardener.OBSIDIAN_SEALANT.get())) {
            return;
        }
        if (SealZoneClient.hasPending()) {
            // 第二次点击：把两个角一次性提交给服务端结算，然后清掉本地框选
            var first = SealZoneClient.pending();
            var stack = event.getItemStack();
            SealSelectionPacket.sendToServer(first, event.getPos(),
                    stack.getMaxDamage() - stack.getDamageValue());
            SealZoneClient.clearPending();
            return;
        }
        // 第一次点击：只记在客户端，一个包都不发。
        // 刻意**不给任何文字提示**（用户 2026-09-24："选框的时候将不再提示玩家文字，这样太出戏了"）
        // —— 玩家靠青色预览框判断起点已经选好。全流程唯一的文字提示是
        // "整片选不出任何可塑封方块"时的红字（服务端结算后发，见 SealZoneEvents.applySelection）。
        SealZoneClient.setPending(event.getPos());
    }
}
