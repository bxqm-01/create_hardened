package dev.createhardener;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * 硬化乳浊液的流体类型：**只负责"它看起来是什么样 / 摸起来什么感觉 / 会不会发光"**。
 *
 * <p><b>发光</b>（用户 2026-09-26 要求"发出的光亮比岩浆要弱一点"）：取 {@link #LIGHT_LEVEL} = 12，岩浆是 15。
 * 受伤 / 燃烧 / 与水岩浆相遇那几条**不在这个类里** —— 它们是方块行为，见 {@link HardenedEmulsionBlock}。
 *
 * <p>贴图（用户提供，放在 {@code assets/createhardener/textures/block/}）：
 * <ul>
 *   <li>{@code hardened_emulsion.png} —— 静止贴图，**16×64 = 4 帧**（配同名 {@code .mcmeta}）；</li>
 *   <li>{@code hardened_emulsion_flow.png} —— 流动贴图，**32×256 = 8 帧**（流动贴图必须 32 宽，
 *       且每帧内容要**铺满整帧** —— 原版顶面取的是"以贴图中心为圆心、随水流方向旋转的正方形"）。</li>
 * </ul>
 *
 * <p>流动**速度**不在这里控制 —— 那是 {@link HardenedEmulsionFluids#PROPERTIES} 的
 * {@code tickRate / levelDecreasePerBlock / slopeFindDistance}。
 */
public class HardenedEmulsionFluidType extends FluidType {

    private static final String TEX_ROOT = "block/";

    /** 发光等级：岩浆为 15，这里取 12（"比岩浆弱一点"）。 */
    public static final int LIGHT_LEVEL = 12;

    public HardenedEmulsionFluidType() {
        super(FluidType.Properties.create()
                .descriptionId("fluid_type." + CreateHardener.MODID + ".hardened_emulsion")
                .density(1000)
                .viscosity(1000)
                .lightLevel(LIGHT_LEVEL)
                .canSwim(true)
                .canDrown(true)
                .canPushEntity(true)
                .canConvertToSource(false)
                .supportsBoating(false));
    }

    @Override
    public void initializeClient(java.util.function.Consumer<IClientFluidTypeExtensions> consumer) {
        consumer.accept(new IClientFluidTypeExtensions() {
            @Override
            public ResourceLocation getStillTexture() {
                return ResourceLocation.fromNamespaceAndPath(CreateHardener.MODID, TEX_ROOT + "hardened_emulsion");
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return ResourceLocation.fromNamespaceAndPath(CreateHardener.MODID,
                        TEX_ROOT + "hardened_emulsion_flow");
            }
        });
    }
}
