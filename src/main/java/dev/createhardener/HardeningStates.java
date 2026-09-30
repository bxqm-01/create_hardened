package dev.createhardener;

import net.minecraft.world.level.block.state.BlockState;

/** 方块状态之间的属性搬运（恢复一档时保留朝向、连接、半砖形态等）。 */
public final class HardeningStates {

    private HardeningStates() {
    }

    /** 把 from 里与 to 同名的属性值照搬到 to 上（找不到的属性跳过）。 */
    public static BlockState copySharedProperties(BlockState from, BlockState to) {
        BlockState result = to;
        for (var property : from.getProperties()) {
            if (result.hasProperty(property)) {
                result = copyOne(result, property, from);
            }
        }
        return result;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static <T extends Comparable<T>> BlockState copyOne(BlockState to, net.minecraft.world.level.block.state.properties.Property<T> property, BlockState from) {
        return to.setValue(property, from.getValue(property));
    }
}
