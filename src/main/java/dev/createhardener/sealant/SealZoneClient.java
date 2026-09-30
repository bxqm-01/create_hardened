package dev.createhardener.sealant;

import java.util.ArrayList;
import java.util.List;

import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import dev.createhardener.client.SealCoatings;
import dev.createhardener.client.SealShellRenderer;

/**
 * 客户端显示与框选状态的**存放处**（用 Create 自带的 Catnip {@link Outliner}）。
 *
 * <p>状态机本身在 {@link SealantClientEvents}（事件层），这里只存值并负责画：
 * <ul>
 *   <li>已装外壳 → 紫色描边 + 涂层贴图；</li>
 *   <li>正在框选 → 青色预览框，跟着鼠标实时变化；</li>
 *   <li>注视中的那一格 → 单独的涂层覆盖。</li>
 * </ul>
 *
 * <p>视觉规则（用户要求）：这些**只在玩家手持黑曜石塑封剂时出现**。
 *
 * <h2>坐标口径（2026-09-24 重定，beta44 起 —— 照抄航空学自己的做法）</h2>
 * <p><b>绝对不要自己把 plot 坐标换算成世界坐标。</b>航空学（Sable）给 Create 的 Catnip 描边打了整套兼容层
 * （`dev.ryanhcode.sable.neoforge.mixin.compatibility.create.render_fixes`，
 * 登记在 `sable-neoforge.mixins.json` 里），其中：
 * <ul>
 *   <li>{@code AABBOutlineMixin} 打在 {@link AABBOutline} 上：渲染时 {@code pushPose()} →
 *       {@code Sable.HELPER.getContainingClient(盒中心)} 找到那块物理体
 *       → {@code SublevelRenderOffsetHelper.posePlotToProjected(刚体, poseStack)} →
 *       盒子平移 {@code -translation(中心)} → 画完 {@code popPose()}；</li>
 *   <li>⚠️ 这个变换**默认是关的**（字段 {@code sable$renderWithTransform} 初值 false，整个航空学里
 *       只有 Create 的蓝图工具会开它），所以我们必须自己调
 *       {@code sable$shouldTransform(true)} 打开 —— 见 {@link #enableSableTransform}。</li>
 * </ul>
 * 所以**标准做法**就是：把**方块在自己坐标空间里的原始坐标**盒子包成 {@link AABBOutline} 交给
 * {@code Outliner.showOutline(...)}，剩下的全交给航空学。
 *
 * <p><b>为什么旧写法是错的</b>（beta30→beta42 反复出问题的那条路）：旧代码用
 * {@code BlockPos.containing(ShellPosRotator.toWorld(...))} 自己算世界坐标再喂给 {@code showAABB}
 * —— 等于**绕开了航空学的变换**，还叠加了自己猜的一套投影，于是线框既不跟着旋转、也不贴身
 * （用户："紫色框卡在方块中心、不旋转"）。
 *
 * <p>性能：不手持塑封剂时直接 return、一行不算；每格只是把一个**复用的** {@link AABBOutline}
 * 交给 Outliner 并更新它的盒子，全部变换在渲染期由航空学做一次，无额外查表、无缓存的世界坐标。
 */
@EventBusSubscriber(modid = dev.createhardener.CreateHardener.MODID, value = Dist.CLIENT)
public final class SealZoneClient {

    /** 服务端同步过来的**原始坐标**（可能位于物理结构的 plot 坐标空间）。 */
    private static final List<BlockPos> SHELL_POSITIONS = new ArrayList<>();

    /**
     * 分包接收缓冲：一帧全量可能被拆成多包（见 {@code SealZoneSync.flush}）。
     * 收齐之前不动 {@link #SHELL_POSITIONS}，避免"收到一半"导致描边闪一下。
     */
    private static List<List<BlockPos>> pendingParts;

    private static final int COLOR_SEALED = 0x8B5CF6;   // 紫：已装外壳
    private static final int COLOR_PREVIEW = 0x22D3EE;  // 青：正在框选

    /** 本次框选的第一个角（**只在客户端**，存的就是服务端那套原始坐标）。 */
    private static BlockPos firstPos;

    private SealZoneClient() {
    }

    /**
     * 收到一包外壳坐标。单包（{@code partCount == 1}）直接替换；多包则先攒齐再替换。
     *
     * <p>2026-09-26 新增：服务端因为"世界累计外壳数可能超过单包上限"而改成分包发送，
     * 客户端必须攒齐后再整体替换 —— 否则每包都会把前一份覆盖掉，只剩最后一段。
     */
    public static void receiveChunk(int partIndex, int partCount, List<BlockPos> shells) {
        if (partCount <= 1) {
            pendingParts = null;
            setShells(shells);
            return;
        }
        if (pendingParts == null || pendingParts.size() != partCount) {
            pendingParts = new ArrayList<>(java.util.Collections.nCopies(partCount, null));
        }
        if (partIndex < 0 || partIndex >= pendingParts.size()) {
            return;                             // 防御：异常索引直接丢
        }
        pendingParts.set(partIndex, shells);
        List<BlockPos> all = new ArrayList<>();
        for (List<BlockPos> part : pendingParts) {
            if (part == null) {
                return;                         // 还没收全
            }
            all.addAll(part);
        }
        pendingParts = null;
        setShells(all);
    }

    public static void setShells(List<BlockPos> shells) {
        SHELL_POSITIONS.clear();
        SHELL_POSITIONS.addAll(shells);
        // 顺手丢掉已经消失的外壳所对应的复用实例，避免槽位缓存无限增长
        if (!RETAINED.isEmpty()) {
            java.util.Set<String> alive = new java.util.HashSet<>();
            for (BlockPos pos : SHELL_POSITIONS) {
                alive.add("createhardener_shell_" + pos.asLong());
            }
            RETAINED.keySet().removeIf(k -> k.startsWith("createhardener_shell_") && !alive.contains(k));
        }
    }

    public static void setPending(BlockPos pos) {
        firstPos = pos;
    }

    public static boolean hasPending() {
        return firstPos != null;
    }

    public static BlockPos pending() {
        return firstPos;
    }

    public static void clearPending() {
        firstPos = null;
    }

    // ------------------------------------------------------------ 状态维护（每 tick）

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            SHELL_POSITIONS.clear();
            pendingParts = null;
            clearPending();
            return;
        }
        if (!SealShellRenderer.isHoldingSealant()) {
            // 手里不拿塑封剂了 → 框选作废（确定性规则，不依赖"看向哪里"）
            clearPending();
        }
    }

    // ------------------------------------------------------------ 绘制（每帧）

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        if (!SealShellRenderer.isHoldingSealant()) {
            return;                     // 不手持：一次都不画
        }

        Outliner outliner = Outliner.getInstance();

        // 1) 已装外壳：逐格一个斜贴盒子（交给航空学的 AABBOutline 兼容层去套姿态）。
        //    同一个槽位复用同一个 Outline 实例（航空学就是这么用的，见 SchematicAndQuillHandlerMixin）。
        if (!SHELL_POSITIONS.isEmpty()) {
            for (BlockPos pos : SHELL_POSITIONS) {
                AABBOutline box = retained("createhardener_shell_" + pos.asLong(),
                        projectedBox(level, pos));
                outliner.showOutline("createhardener_shell_" + pos.asLong(), box)
                        .colored(COLOR_SEALED)
                        .withFaceTexture(SealCoatings.SEAL_COATING)
                        .disableLineNormals()
                        .lineWidth(1 / 32f);
            }
        }

        // 2) 框选预览：两个角都用**原始坐标**（第一次点的那格 + 当前注视的那格）。
        if (firstPos != null && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos hovered = ((BlockHitResult) mc.hitResult).getBlockPos();
            AABBOutline box = retained("createhardener_seal_preview", span(firstPos, hovered));
            outliner.showOutline("createhardener_seal_preview", box)
                    .colored(COLOR_PREVIEW)
                    .withFaceTexture(SealCoatings.SEAL_COATING)
                    .disableLineNormals()
                    .lineWidth(1 / 24f);
        }

        // 3) 注视中的那一格：单独糊一层涂层贴图（用户要求"覆盖只对选中的那个方块显示"）。
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            BlockPos looked = ((BlockHitResult) mc.hitResult).getBlockPos();
            AABBOutline box = retained("createhardener_seal_hover",
                    projectedBox(level, looked).inflate(0.002));
            outliner.showOutline("createhardener_seal_hover", box)
                    .colored(COLOR_SEALED)
                    .withFaceTexture(SealCoatings.SEAL_COATING)
                    .disableLineNormals()
                    .lineWidth(1 / 40f);
        }
    }

    /**
     * 按槽位保留同一个 {@link AABBOutline} 实例，只更新它的盒子。
     *
     * <p>复用实例（而不是每帧 new）有两个理由：① 航空学给描边开的那个姿态变换开关是**开在实例上的**，
     * 每帧重建就得每帧重开一次；② 每帧新建海量临时对象没必要。
     * 更新盒子用 {@link AABBOutline#setBounds(AABB)}。
     */
    private static AABBOutline retained(String slot, AABB box) {
        AABBOutline outline = RETAINED.get(slot);
        if (outline == null) {
            outline = new AABBOutline(box);
            enableSableTransform(outline);
            RETAINED.put(slot, outline);
        } else {
            outline.setBounds(box);
        }
        return outline;
    }

    /**
     * 打开航空学的"把盒子套进物理体姿态"开关。
     *
     * <p>{@code AABBOutlineMixin} 默认**不开**这个变换（字段 {@code sable$renderWithTransform} 初值 false，
     * 整个航空学里只有 Create 的蓝图工具 {@code DeployToolMixin} 会调它），所以我们必须自己开。
     * 开关方法 {@code sable$shouldTransform(boolean)} 声明在航空学的
     * {@code ...create.renderers.AABBOutlineRenderingOptions} 接口上，那个接口所在的包没有对模组开放，
     * 因此这里用反射调用（与项目里 {@code ShellPosRotator} 访问 Sable 的口径一致：
     * 没装航空学时静默跳过，不影响加载）。
     */
    private static void enableSableTransform(AABBOutline outline) {
        if (!SABLE_TRANSFORM_PROBED) {
            SABLE_TRANSFORM_PROBED = true;
            try {
                Class<?> options = Class.forName(
                        "dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.create.renderers"
                                + ".AABBOutlineRenderingOptions");
                SABLE_TRANSFORM = options.getMethod("sable$shouldTransform", boolean.class);
            } catch (Throwable ignored) {
                SABLE_TRANSFORM = null;         // 没装航空学：描边退回普通世界坐标盒子
            }
        }
        if (SABLE_TRANSFORM == null) {
            return;
        }
        try {
            SABLE_TRANSFORM.invoke(outline, Boolean.TRUE);
        } catch (Throwable ignored) {
            // 单个描边失败不影响整帧
        }
    }

    private static boolean SABLE_TRANSFORM_PROBED;
    private static java.lang.reflect.Method SABLE_TRANSFORM;

    /** 槽位 → 复用的描边实例。清空时机见 {@link #setShells}。 */
    private static final java.util.Map<String, AABBOutline> RETAINED = new java.util.HashMap<>();

    // ------------------------------------------------------------ 盒子口径

    /**
     * 一格的盒子，**用在哪里画就返回哪里的坐标** ——
     * 方块在物理结构（子世界）里就返回它**自己的原始坐标**盒子，由航空学在渲染期负责套姿态；
     * 普通方块本来就是世界坐标，不需要任何转换。
     *
     * <p>所以这里刻意**不做**任何 {@code toWorld} 投影：那是 beta30→beta42 画不对的根源。
     */
    private static AABB projectedBox(Level level, BlockPos pos) {
        return new AABB(pos);
    }
    /** 两点之间的包围盒（BlockPos 版，落在同一坐标空间里，不做投影）。 */
    private static AABB span(BlockPos a, BlockPos b) {
        return new AABB(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1.0, Math.max(a.getY(), b.getY()) + 1.0,
                Math.max(a.getZ(), b.getZ()) + 1.0);
    }
}
