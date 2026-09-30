package dev.createhardener;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 黑曜石粉恢复一档的共用逻辑（四种形状都走这里）。
 *
 * <p>恢复时用原版 {@code Block.updateFromNeighbourShapes} 无法保留朝向，
 * 这里按需保留：方块状态里有的同名属性直接照搬，避免墙/楼梯恢复后朝向错乱。
 */
public final class HardeningInteraction {

    private HardeningInteraction() {
    }

    public static ItemInteractionResult tryRestore(HardeningStages.Family family, int stage, BlockState current,
                                                   ItemStack stack, Level level, BlockPos pos, Player player) {
        if (stage <= 0 || !stack.is(CreateHardener.OBSIDIAN_POWDER)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide) {
            Hardening.previousOf(family, stage).ifPresent(restored -> {
                BlockState placed = HardeningStates.copySharedProperties(current, restored);
                level.setBlockAndUpdate(pos, placed);
                level.playSound(null, pos, SoundEvents.HONEYCOMB_WAX_ON, SoundSource.BLOCKS,
                        0.9F, 0.9F + level.random.nextFloat() * 0.2F);
                if (level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.WAX_ON,
                            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                            8, 0.3D, 0.3D, 0.3D, 0.0D);
                }
            });
            if (player == null || !player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
}
