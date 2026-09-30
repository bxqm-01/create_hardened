package dev.createhardener;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

/**
 * **硬化流体储罐**（2026-09-26 用户新增要求）：原版**流体储罐的变体** ——
 * "模型什么的套用原版就行，不过贴图使用我给你的这一版。目前先实现原本已有的功能。"
 *
 * <p>做法是**整套继承原版** {@link FluidTankBlock}：形状属性（{@code TOP}/{@code BOTTOM}/{@code SHAPE}）、
 * 多方块拼装、扳手调窗、碰撞箱、比较器输出、锅炉联动全部沿用原版；我们只换贴图
 * （模型与方块状态是照原版复刻的 24 个变体，贴图指向 {@code createhardener:block/hardened_fluid_tank*}）。
 *
 * <p>⚠️ 因为原版 {@code FluidTankBlock.isTank()} 判的是 {@code instanceof FluidTankBlock}，
 * **我们的储罐会和原版储罐连成同一个多方块**。这是继承的必然结果，也符合"沿用原版功能"的默认表现；
 * 若要隔离，得覆写整套连通逻辑（成本高），暂不做。
 */
public class HardenedFluidTankBlock extends FluidTankBlock {

    public HardenedFluidTankBlock(BlockBehaviour.Properties properties) {
        // 第二个参数 false = 普通储罐（true 是创造模式储罐），与原版 FluidTankBlock.regular 一致
        super(properties, false);
    }

    /**
     * 沿用父类的方块实体**类**。
     *
     * <p>为什么不返回我们自己的子类：{@code IBE<T>} 的签名是 {@code Class<T> getBlockEntityClass()}，
     * 而本类继承下来的是 {@code IBE<FluidTankBlockEntity>}；{@code Class<HardenedFluidTankBlockEntity>}
     * **不是** {@code Class<FluidTankBlockEntity>} 的子类型（泛型不协变），所以只能返回父类 ——
     * 功能上没问题，我们的方块实体本来就是它的子类。
     */
    @Override
    public Class<FluidTankBlockEntity> getBlockEntityClass() {
        return FluidTankBlockEntity.class;
    }

    /** ⚠️ 必须覆写成**我们自己的**方块实体类型，否则会创建出原版储罐的方块实体。 */
    @Override
    public BlockEntityType<? extends FluidTankBlockEntity> getBlockEntityType() {
        return CreateHardener.HARDENED_FLUID_TANK_BE.get();
    }
}
