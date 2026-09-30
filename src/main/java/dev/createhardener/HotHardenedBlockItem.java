package dev.createhardener;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 烧红的硬化块的**物品形态** —— 比普通 {@link BlockItem} 多一件事：
 * **待在玩家背包里时会持续伤害玩家**（用户 2026-09-26 要求："这个方块出现在玩家背包时，会对玩家造成持续伤害"）。
 *
 * <p>实现走 {@link #inventoryTick}：每 20 刻烫一次、伤害 1（伤害源用 {@code onFire}，
 * 与"被烫到"的观感一致）。只对**玩家**生效，避免误伤别的实体容器。
 */
public class HotHardenedBlockItem extends BlockItem {

    /** 烫伤间隔（tick）。 */
    private static final int DAMAGE_INTERVAL = 20;
    /** 背包内单次伤害。 */
    private static final float INVENTORY_DAMAGE = 1.0F;

    public HotHardenedBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (level.isClientSide || !(entity instanceof Player player)) {
            return;
        }
        if (player.getAbilities().instabuild || player.isCreative()) {
            return;                       // 创造模式不受影响，免得调试时一直掉血
        }
        if (level.getGameTime() % DAMAGE_INTERVAL != 0) {
            return;
        }
        player.hurt(level.damageSources().onFire(), INVENTORY_DAMAGE);
    }
}
