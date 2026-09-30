package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import com.simibubi.create.content.fluids.pump.PumpBlockEntity;

import dev.createhardener.CreateHardener;

/**
 * 硬化流体泵的方块实体 —— 直接继承 {@link PumpBlockEntity}。
 *
 * <p>只有两参构造器（NeoForge 要求）与类型指向 {@link CreateHardener#HARDENED_FLUID_PUMP_BE}。
 *
 * <p>**"同一转速下搬两倍液体"的实现在 {@code mixin.PumpPressureBoostMixin}**，不在本类。
 *
 * <p>⚠️ <b>2026-09-27 清理</b>：这里原先还覆写过一个 {@code distributePressureTo}（把原版方法跑两遍
 * "让压力翻倍"）。后来读字节码查实它**是无效路径**：真正的搬运量是
 * {@code FluidNetwork.transferSpeed = (int) max(1f, 压力 / 2f)}，而那个式子只认**网络起点那个连接**
 * （也就是泵自己的连接）的压力，并且泵的 behaviour 每 tick 都会把它 {@code set} 回 {@code |转速|}
 * —— 加在下游管网上的量每 tick 都被覆盖，所以用户实测"并不更快"。
 * 正确的翻倍落点是 {@code PumpPressureBoostMixin}（在泵 behaviour 的 {@code tick()} TAIL 上翻倍）。
 * 那个无用的覆写已经删除（它没有正面作用，却让每次管网变化多跑一遍端点搜索）。
 */
public class HardenedFluidPumpBlockEntity extends PumpBlockEntity {

    public HardenedFluidPumpBlockEntity(BlockPos pos, BlockState state) {
        super(CreateHardener.HARDENED_FLUID_PUMP_BE.get(), pos, state);
    }
}
