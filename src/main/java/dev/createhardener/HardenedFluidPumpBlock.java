package dev.createhardener;

import net.minecraft.world.level.block.entity.BlockEntityType;

import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;

/**
 * **硬化流体泵**（2026-09-27 起）—— 直接继承原版 {@link PumpBlock}。
 *
 * <p>所以"抽取 / 压力分配 / 管网端点搜索 / 护目镜 / 扳手 / 水浸 / 物理化"全部照旧，
 * 我们只换贴图 + 方块实体类型 + **应力值**（2 倍，在 {@link CreateHardener} 里注册）。
 *
 * <p><b>设计背景</b>（用户 2026-09-27 拍板）：原版转速上限 256 是"硬上限 + 超速炸源方块"
 * （{@code RotationPropagator.propagateNewSource} 里 {@code destroyBlock}），所以真正的 512 转速做不到、
 * 也不做了。改用原版机制下的等价手段：**我们的泵压力翻倍**（{@code PumpBlockEntity.distributePressureTo}
 * 是 protected，可覆写；压力公式实测为 {@code |转速| ÷ (该距离档端点数-1)}，由
 * {@code FluidTransportBehaviour.addPressure} 施加），代价是**应力翻倍**。
 *
 * <p><b>⚠️ 尚未实现（等下一步）</b>：
 * <ol>
 *   <li>压力翻倍 —— 覆写 {@code HardenedFluidPumpBlockEntity.distributePressureTo}；</li>
 *   <li>"压力 &gt; 256 时摧毁普通管道" —— 破坏动作必须由**我们的泵**执行
 *       （原版管道是 Create 的方块，我们加不了代码）：泵自己遍历管网，遇到
 *       {@code FluidPipeBlock.isPipe} 但不是我们的硬化管道就摧毁。原版
 *       {@code PumpBlockEntity.searchForEndpointRecursively} 是 protected，可直接复用或自写 BFS。</li>
 * </ol>
 */
public class HardenedFluidPumpBlock extends PumpBlock {

    public HardenedFluidPumpBlock(Properties properties) {
        super(properties);
        // 应力 = 原版泵的 2 倍（用户拍板；这是"压力翻倍"的代价）。
        // 用**惰性** DoubleSupplier：查询时才去读原版泵的值，因此不依赖两个模组的注册先后。
        // 在这里注册（等于"方块构造时"）而不是在静态块里，是为了避开 DeferredHolder 尚未绑定的问题。
        com.simibubi.create.api.stress.BlockStressValues.IMPACTS.register(this,
                (java.util.function.DoubleSupplier) () -> com.simibubi.create.api.stress.BlockStressValues
                        .getImpact(com.simibubi.create.AllBlocks.MECHANICAL_PUMP.get()) * 2.0D);
    }

    @Override
    public Class<PumpBlockEntity> getBlockEntityClass() {
        return PumpBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PumpBlockEntity> getBlockEntityType() {
        return CreateHardener.HARDENED_FLUID_PUMP_BE.get();
    }
}
