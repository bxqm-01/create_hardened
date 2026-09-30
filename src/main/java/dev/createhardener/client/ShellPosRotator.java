package dev.createhardener.client;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 把"可能是子世界（航空学物理结构）里的坐标"换算成**世界坐标**，供描边使用。
 *
 * <p><b>为什么需要</b>：被物理化的方块住在子世界的 plot 坐标空间里，坐标数值和世界坐标不是一回事。
 * 直接把那些 BlockPos 交给 {@code Outliner} 画，就会得到用户实测的那种怪象 ——
 * 只画出几根横线、而且不在方块的棱角上。
 *
 * <p><b>为什么必须每帧算（关键，别退回旧实现）</b>：
 * {@code Sable.HELPER.projectOutOfSubLevel(level, position)} 内部是
 * "找到包含该坐标的 SubLevel → 取**当前姿态**（客户端取含插值的渲染姿态）→ {@code transformPosition}"
 * —— 也就是**平移和旋转都在这一步里**。所以：
 * <ul>
 *   <li>物理结构一移动、一旋转，结果立刻跟着变（这正是把它一次性存下来的旧写法做不到的）；
 *   <li>把它缓存成"固定世界坐标"是错的：物体挪走框会留在原地，转过角度的方块根本画不出来。</li>
 * </ul>
 *
 * <p><b>性能</b>：照"原版只处理必要量"的思路做快速路径 ——
 * 先用 {@code SubLevelContainer.inBounds(BlockPos)}（**chunk 查表，O(1)**）判断该格是否落在 plot 空间里；
 * 绝大多数方块不在物理结构里，一次查表就早退，不会去做投影。
 *
 * <p><b>兼容性</b>：全部用 {@link MethodHandle} + 静态初始化 {@code catch (Throwable)} 访问 Sable，
 * **没装航空学时本类也能安全加载**（{@link #available()} 返回 false，调用方退回原始坐标）。
 */
public final class ShellPosRotator {

    private ShellPosRotator() {
    }

    private static boolean available;
    private static MethodHandle projectOutOfSubLevel;
    /** SubLevelContainer.getContainer(Level) —— 拿容器（按世界取，内部是查表）。 */
    private static MethodHandle getContainer;
    /** SubLevelContainer.inBounds(BlockPos) —— O(1) 判断该格是否落在 plot 空间内。 */
    private static MethodHandle inBounds;
    /** Sable.HELPER.getContainingClient(Position) → ClientSubLevel（判"这一格属于哪个刚体"）。 */
    private static MethodHandle getContainingClient;
    /** ClientSubLevel.renderPose(float partialTick) → Pose3dc（**含插值的当前姿态**）。 */
    private static MethodHandle renderPose;
    /** Pose3dc.bakeIntoMatrix(Matrix4d) —— 把姿态烘进矩阵（含平移+旋转+缩放）。 */
    private static MethodHandle bakeIntoMatrix;

    static {
        try {
            Class<?> companion = Class.forName("dev.ryanhcode.sable.ActiveSableCompanion");
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sable.getField("HELPER").get(null);
            projectOutOfSubLevel = MethodHandles.lookup()
                    .findVirtual(companion, "projectOutOfSubLevel",
                            MethodType.methodType(Vec3.class, Level.class, Vec3.class))
                    .bindTo(helper);
            // getContainingClient 的重载很多，取 (Position) 那个
            try {
                getContainingClient = MethodHandles.lookup()
                        .findVirtual(companion, "getContainingClient",
                                MethodType.methodType(Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel"),
                                        net.minecraft.core.Position.class))
                        .bindTo(helper);
            } catch (Throwable ignored) {
                getContainingClient = null;
            }
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        try {
            Class<?> container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            getContainer = MethodHandles.lookup()
                    .findStatic(container, "getContainer",
                            MethodType.methodType(container, Level.class));
            inBounds = MethodHandles.lookup()
                    .findVirtual(container, "inBounds",
                            MethodType.methodType(boolean.class, BlockPos.class));
        } catch (Throwable ignored) {
            getContainer = null;
            inBounds = null;
        }
        try {
            Class<?> clientSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            Class<?> pose3dc = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
            renderPose = MethodHandles.lookup()
                    .findVirtual(clientSubLevel, "renderPose", MethodType.methodType(pose3dc, float.class));
            bakeIntoMatrix = MethodHandles.lookup()
                    .findVirtual(pose3dc, "bakeIntoMatrix",
                            MethodType.methodType(org.joml.Matrix4d.class, org.joml.Matrix4d.class));
        } catch (Throwable ignored) {
            renderPose = null;
            bakeIntoMatrix = null;
        }
    }

    /** Sable 的投影 API 是否可用。 */
    public static boolean available() {
        return available;
    }

    /**
     * 快速路径：这一格是否落在某个物理结构（子世界 plot）的坐标空间里。
     *
     * <p>不可判定时保守返回 true（宁可多算一次投影，也不要漏画）。
     */
    public static boolean inSubLevelSpace(Level level, BlockPos pos) {
        if (getContainer == null || inBounds == null || !(level instanceof ClientLevel clientLevel)) {
            return true;
        }
        try {
            Object container = getContainer.invoke(clientLevel);
            return container != null && (boolean) inBounds.invoke(container, pos);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 把一格的坐标换算成世界坐标。**必须每帧调用**（见类注释：姿态含平移与旋转且随时间变化）。
     *
     * <p>不在物理结构里的方块直接返回原坐标 —— 这是绝大多数情况，开销只有一次 chunk 查表。
     *
     * @param level 客户端所在的世界（null 时取 {@code Minecraft.getInstance().level}）
     */
    public static Vec3 toWorld(Level level, BlockPos pos) {
        Vec3 raw = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (!available) {
            return raw;
        }
        Level target = level != null ? level : Minecraft.getInstance().level;
        if (target == null) {
            return raw;
        }
        if (!inSubLevelSpace(target, pos)) {
            return raw;                 // 快速路径：普通方块，零投影开销
        }
        try {
            return (Vec3) projectOutOfSubLevel.invoke(target, raw);
        } catch (Throwable t) {
            return raw;                 // 单点失败不影响整帧
        }
    }

    // ------------------------------------------------------------ 姿态（供"斜着贴合"的线框）

    public static boolean poseAvailable() {
        return getContainingClient != null && renderPose != null && bakeIntoMatrix != null;
    }

    /** 拿到某个世界坐标所属刚体的**当前渲染姿态矩阵**（含插值）；不属于任何刚体时返回 null。 */
    public static org.joml.Matrix4d poseMatrixAt(Level level, Vec3 worldPos, float partialTick) {
        if (!poseAvailable()) {
            return null;
        }
        Level target = level != null ? level : Minecraft.getInstance().level;
        if (target == null) {
            return null;
        }
        try {
            Object subLevel = getContainingClient.invoke(target, worldPos);
            if (subLevel == null) {
                return null;
            }
            Object pose = renderPose.invoke(subLevel, partialTick);
            if (pose == null) {
                return null;
            }
            return (org.joml.Matrix4d) bakeIntoMatrix.invoke(pose, new org.joml.Matrix4d());
        } catch (Throwable t) {
            return null;
        }
    }
}
