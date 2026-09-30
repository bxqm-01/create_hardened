package dev.createhardener.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.PipeConnection;

import dev.createhardener.CreateHardener;

/**
 * **唯一的一处原版注入（2026-09-27 起）**：把**硬化流体泵**每 tick 的搬运量翻倍。
 *
 * <h2>为什么是"压力"</h2>
 * 一路读字节码得到的链路（全部亲读，不是猜的）：
 * <pre>
 *   PumpFluidTransferBehaviour.tick()   → 把泵每个连接的压力设成 |泵转速|（它只做这一件事）
 *   PipeConnection.manageFlows(...)     → new FluidNetwork(level, face, ...).tick()
 *   FluidNetwork.tick()                 → transferSpeed = (int) Math.max(1f, 压力 / 2f)
 *                                       → IFluidHandler.drain(带 transferSpeed 的 stack, EXECUTE) → fill(...)
 * </pre>
 * 也就是说：**真正搬多少液体 = 压力 / 2**，而压力就是泵的转速。所以压力乘 2 ⇒ 搬运量乘 2；
 * 而且走的是原版**真实**的 drain/fill（{@code FluidAction.EXECUTE}），**不是凭空造流体**，守恒、刷不出液体。
 *
 * <h2>为什么写成 TAIL 上"再翻一倍"</h2>
 * 泵的 behaviour 每 tick 都是 {@code pressure.set(侧, |转速|)} —— **是 set，不是 add**。所以：
 * <ol>
 *   <li>在别处（管网、别的泵、分压路径）加的量**每 tick 都会被它覆盖掉** ——
 *       这正是此前"给硬化泵做压力翻倍却实测无效"的真因（当时翻的是下游管网，而
 *       {@code transferSpeed} 只看**网络起点那个连接**，也就是泵自己的连接）；</li>
 *   <li>在它**之后**（TAIL）读当前值再乘 2，每 tick 重新算一次，**不会累积、不会漂移**。</li>
 * </ol>
 *
 * <h2>为什么必须注入，而不是自己写一个 behaviour 子类</h2>
 * {@code PumpFluidTransferBehaviour} 是**包私有**的（外部无法继承）；而且原版
 * {@code PumpBlockEntity.distributePressureTo} 用 {@code instanceof PumpFluidTransferBehaviour}
 * 做"**泵认泵**"（遍历管网时遇到另一个泵就停下） —— 一旦我们换掉 behaviour 类，原版泵不认我们的泵、
 * 我们的泵也不认别人的泵，整张管网的行为都会被改坏。
 *
 * <h2>口径</h2>
 * 全部只用公开 API：{@code BlockEntityBehaviour.getWorld()/getPos()}、
 * {@code FluidTransportBehaviour.interfaces}（public 字段）、{@code PipeConnection.getPressure()}、
 * {@code Couple.get/set(boolean, ...)} —— **一个私有字段都没碰**；两侧都乘（另一侧通常是 0，乘了还是 0），
 * 所以不需要知道"哪一侧在抽"，也就绕开了 protected 的 {@code isFront/isPullingOnSide}。
 *
 * <p>{@code targets = "..."} 而不是 {@code @Mixin(类.class)}：目标内部类包私有，源码里引用不到。
 * {@code remap = false} 因为目标是模组方法（NeoForge 运行期就是官方名，不需要重映射）。
 */
@Mixin(targets = "com.simibubi.create.content.fluids.pump.PumpBlockEntity$PumpFluidTransferBehaviour", remap = false)
public abstract class PumpPressureBoostMixin {

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void createhardener$doubleTransferSpeed(CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof FluidTransportBehaviour behaviour)) {
            return;
        }
        // ⚠️ TAIL 只对应"最后一个 return"，但 `tick()` 里还有几处提前 return（interfaces 为 null 就退），
        //    这里再兜一次，免得哪天原版改了控制流把我们带进 NPE。
        if (behaviour.interfaces == null || behaviour.interfaces.isEmpty()) {
            return;
        }
        Level level = behaviour.getWorld();
        BlockPos pos = behaviour.getPos();
        if (level == null || pos == null
                || level.getBlockState(pos).getBlock() != CreateHardener.HARDENED_FLUID_PUMP.get()) {
            return;
        }
        for (PipeConnection connection : behaviour.interfaces.values()) {
            Couple<Float> pressure = connection.getPressure();
            pressure.set(true, pressure.get(true) * 2.0F);
            pressure.set(false, pressure.get(false) * 2.0F);
        }
    }
}
