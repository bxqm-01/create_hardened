package dev.createhardener.client.ponder;

/**
 * 把思索（Ponder）插件挂进 Ponder —— **客户端专用**，由
 * {@link dev.createhardener.CreateHardener} 的构造器在 {@code Dist.CLIENT} 下调用。
 *
 * <p>时机很关键：必须在 Ponder 的 {@code PonderIndex.registerAll()}（modLoadCompleted 时执行）
 * 之前。模组构造期是最早的时机，也是 Create 自己注册场景的时机（CreateClient 构造期），
 * 而且构造期是单线程，不会和 PonderIndex 里的普通集合打架。
 *
 * <p>Ponder 由 Create 内嵌提供（{@code META-INF/jarjar/ponder-neoforge-*.jar}），
 * 正常情况下类一定在；包一层 try/catch 只是保证万一环境异常时不连累其它客户端注册。
 */
public final class HardenerPonderSetup {

    private HardenerPonderSetup() {
    }

    public static void init() {
        try {
            net.createmod.ponder.foundation.PonderIndex.addPlugin(new HardenerPonderPlugin());
            org.slf4j.LoggerFactory.getLogger("createhardener")
                    .info("[硬化剂] 思索（Ponder）插件已注册：hb01 / rmb01 / rmb02 / bte01 / hft01 / hfp01");
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("createhardener")
                    .warn("[硬化剂] 注册思索（Ponder）插件失败（不影响其它功能）: {}", t.toString());
        }
    }
}
