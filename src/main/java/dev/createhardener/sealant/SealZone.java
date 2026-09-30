package dev.createhardener.sealant;

import net.minecraft.core.BlockPos;

/**
 * 一次塑封框选的区域（两次右键点出的对角）。
 *
 * <p><b>语义（2026-09-23 定稿）</b>：区域只是**圈的框**，套不套、套几格由服务端在
 * {@link SealShellHelper#applyTo} 里**逐格静默判定**（空气、黑名单、已有方块实体、非完整碰撞形状都会被跳过），
 * 所以区域里最终可能只有一部分格子被套上外壳 —— 每格独立，互不影响。
 *
 * <p>（早期设计是"整个区域共享一次保护"，那套语义已废弃：现在保护的是每一格自己的外壳。）
 *
 * @param min 区域最小角（含）
 * @param max 区域最大角（含）
 */
public record SealZone(BlockPos min, BlockPos max) {

    public static SealZone of(BlockPos a, BlockPos b) {
        return new SealZone(
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    public int sizeX() {
        return max.getX() - min.getX() + 1;
    }

    public int sizeY() {
        return max.getY() - min.getY() + 1;
    }

    public int sizeZ() {
        return max.getZ() - min.getZ() + 1;
    }
}
