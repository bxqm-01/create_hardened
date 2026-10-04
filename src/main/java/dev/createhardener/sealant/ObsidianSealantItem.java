package dev.createhardener.sealant;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import dev.createhardener.CreateHardener;

/**
 * 黑曜石塑封剂（2026-09-24 定稿）。
 *
 * <p>用法：两次右键点出对角 → 给该范围内**符合条件**的方块逐个套壳。
 * 外壳是自定义的完整方块伪装方块，外观仍是原方块；玩家必须先挖掉外壳才能挖到方块本体，
 * 爆炸一次震碎范围内的外壳，**第二次爆炸**才会炸掉方块本体。
 *
 * <p><b>职责只有两件</b>（框选状态一律不碰）：
 * <ol>
 *   <li>手持塑封剂时**吞掉这次点击**（返回 SUCCESS），不让箱子/工作台等方块抢走它
 *       —— 等价于 Create 的 {@code glueItemAlwaysPlacesWhenUsed}；</li>
 *   <li>就这样。两次点击的判读、发包、清状态**全部**在 {@link SealantClientEvents} 里完成。</li>
 * </ol>
 *
 * <p>为什么不在这个类里判断"这是第几次点击"：客户端 `RightClickBlock` 事件**先于** `useOn` 触发，
 * 两个处理器同时管一个状态就会出现"哪边先跑决定行为"的脆弱结构 —— 曾因此在 `useOn` 里看不到 pending，
 * 导致选框不显示、只能单格塑封。
 */
public class ObsidianSealantItem extends Item {

    public ObsidianSealantItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() == null
                || !context.getItemInHand().is(CreateHardener.OBSIDIAN_SEALANT.get())) {
            return InteractionResult.PASS;
        }
        return InteractionResult.SUCCESS;   // 只吞点击；框选逻辑见 SealantClientEvents
    }

    /**
     * 悬浮说明（用户 2026-10-04 指定的文案）：
     * "可以把任意完整且不进行交互的方块强化，可以为方块抵挡一次爆炸或挖掘"。
     *
     * <p>文案走语言键 {@code tooltip.createhardener.obsidian_sealant}（zh_cn / en_us 都有）。
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.createhardener.obsidian_sealant")
                .withStyle(ChatFormatting.GRAY));
    }
}
