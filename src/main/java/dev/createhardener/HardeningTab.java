package dev.createhardener;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 独立创造标签页：全部硬化块（整砖/半砖/楼梯 × 4 档 = 12 个）+ 黑曜石塑封剂，
 * 不再挤进原版「建筑方块」栏。
 */
public final class HardeningTab {

    private HardeningTab() {
    }

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(net.minecraft.core.registries.Registries.CREATIVE_MODE_TAB,
                    CreateHardener.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("hardening",
            HardeningTab::makeTab);

    private static CreativeModeTab makeTab() {
        // NeoForge 1.21.1 的 API 是 builder(Row, 列号)：Row.TOP 的第一列，排在原版标签页之前
        return CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                .title(Component.translatable("itemGroup.createhardener"))
                .icon(() -> new ItemStack(HardeningStages.FULL.get(0).get()))
                .displayItems((params, output) -> {
                    CreateHardener.BLOCK_ITEMS.forEach(item -> output.accept(item.get()));
                    output.accept(CreateHardener.OBSIDIAN_SEALANT.get());
                    // 硬化乳浊液桶（液体方块本身没有对应物品，靠桶放出）
                    output.accept(HardenedEmulsionFluids.EMULSION_BUCKET.get());
                    // 谐振感磁块
                    output.accept(CreateHardener.RESONANT_BLOCK_ITEM.get());
                    // 硬化流体储罐（原版流体储罐的变体）
                    output.accept(CreateHardener.HARDENED_FLUID_TANK_ITEM.get());
                    // 硬化流体泵（2026-09-27；配套的硬化管道已于同日整套删除）
                    // ⚠️ 新物品**必须在这里显式加一次**：创造栏的"搜索"只搜已被某个标签页收录的物品，
                    //    漏了这一步的表现就是"游戏里怎么搜都搜不到"（beta77 就是这个坑）。
                    output.accept(CreateHardener.HARDENED_FLUID_PUMP_ITEM.get());
                    // 冲爆引擎（应力来源：挨爆炸攒应力与转速）
                    output.accept(CreateHardener.BLAST_TURBINE_ENGINE_ITEM.get());
                    // 遗骸沉积岩（乳浊液遇岩浆生成）+ 远古合金碎屑
                    // ⚠️ 必须传**物品**（BlockItem），不能传 Block —— 传 Block 会拿到空栈并崩溃
                    output.accept(HardenedEmulsionFluids.ANCIENT_DEBRIS_SEDIMENTARY_ROCK_ITEM.get());
                    output.accept(HardenedEmulsionFluids.ANCIENT_ALLOY_SCRAP.get());
                    // 材料链（2026-09-28）：硬化锭 → 硬化板 → 硬化构件（+ 系列组装的过渡件）
                    output.accept(CreateHardener.HARDENED_INGOT.get());
                    output.accept(CreateHardener.HARDENED_PLATE.get());
                    output.accept(CreateHardener.HARDENED_COMPONENT.get());
                    output.accept(CreateHardener.INCOMPLETE_HARDENED_COMPONENT.get());
                })
                .build();
    }
}
