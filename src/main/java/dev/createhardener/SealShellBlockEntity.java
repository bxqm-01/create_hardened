package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 外壳方块实体：记住"被套住的方块状态"，供客户端**渲染成那个方块**。
 *
 * <p>状态通过 {@link #getUpdateTag} / {@link #getUpdatePacket} 同步到客户端。
 *
 * <p>⚠️ 注意：航空学（Sable）的装配监听 {@code BlockSubLevelAssemblyListener}
 * **不在这个类上实现** —— Sable 判的是 `getBlock() instanceof ...`，即**方块**。
 * 见 {@link SealShellBlocks.SealShellBlock}。
 */
public class SealShellBlockEntity extends BlockEntity {

    /** 被套住的方块（外壳要冒充的外观）。 */
    private BlockState material = Blocks.STONE.defaultBlockState();

    public SealShellBlockEntity(BlockPos pos, BlockState state) {
        super(CreateHardener.SEAL_SHELL_BE.get(), pos, state);
    }

    public BlockState material() {
        return this.material;
    }

    public void setMaterial(BlockState state) {
        this.material = state;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ------------------------------------------------------------ 同步 / 序列化

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("material", NbtUtils.writeBlockState(material));
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("material")) {
            this.material = NbtUtils.readBlockState(
                    registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("material"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("material", NbtUtils.writeBlockState(material));
    }
}