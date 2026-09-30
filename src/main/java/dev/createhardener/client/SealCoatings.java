package dev.createhardener.client;

import net.createmod.catnip.render.BindableTexture;
import net.minecraft.resources.ResourceLocation;

import dev.createhardener.CreateHardener;

/**
 * 供 Catnip {@code Outliner} 使用的贴图（{@link BindableTexture}）。
 *
 * <p><b>这是"照抄强力胶"的关键一步</b>：Create 的超级胶水在选中方块时那层半透明覆盖，
 * 不是自己写渲染，而是
 * {@code Outliner.showCluster(...).withFaceTextures(AllSpecialTextures.GLUE, ...)} ——
 * {@code OutlineParams.withFaceTexture/withFaceTextures} 会把贴图**糊在选中方块的表面上**，
 * 描边与覆盖一起画。所以只要提供一个 {@link BindableTexture}，我们也能得到同样效果。
 *
 * <p>贴图路径按 Create 的约定放在 {@code assets/<ns>/textures/special/} 下
 * （Create 的 glue.png / checkerboard.png 就在那儿），`getLocation()` 返回
 * {@code <ns>:<special 下的相对路径>}（不含 {@code textures/} 前缀与 {@code .png}）。
 */
public enum SealCoatings implements BindableTexture {

    /**
     * 用户给的斜条纹涂层贴图：`assets/createhardener/textures/special/seal_coating.png`。
     *
     * <p>⚠️ **常量名必须与贴图文件名一致** —— `getLocation()` 是用 `name().toLowerCase()` 拼出来的。
     * 之前常量叫 `COATING` 而文件叫 `seal_coating.png`，结果去取 `special/coating.png`（不存在）
     * → 游戏里显示紫黑"缺失贴图"。要改贴图名就连常量名一起改。
     */
    SEAL_COATING;

    public static final String ASSET_PATH = "textures/special/";

    /**
     * 路径**只在类加载时算一次**（Create 的 {@code AllSpecialTextures} 就是这个做法）。
     *
     * <p>为什么必须缓存（2026-09-26 修）：这个 getter 被 Catnip 在**每条描边每一帧的 6 个面**
     * 上各调一次，原来每次都做 {@code toLowerCase} + 字符串拼接 + {@code fromNamespaceAndPath}
     * （含路径字符校验）→ N 个外壳就是 6N 次/帧的白费开销。
     */
    private final ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
            CreateHardener.MODID, "textures/special/" + name().toLowerCase(java.util.Locale.ROOT) + ".png");

    @Override
    public ResourceLocation getLocation() {
        return this.location;
    }
}
