package dev.createhardener.client.ponder;

import java.util.function.Function;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

import dev.createhardener.CreateHardener;

/**
 * 思索（Ponder）插件：把场景挂到对应方块上。
 *
 * <p>注册键按**方块注册表**解析（Ponder 的 {@code PonderSceneRegistry} 用的是
 * {@code BuiltInRegistries.BLOCK}），所以这里传方块的 {@link ResourceLocation}；
 * 方块与物品同名时，手持该物品按 W（思索键）就能打开对应场景。
 *
 * <p>故事板 id（如 {@code hb01}）就是资源路径
 * {@code assets/createhardener/ponder/hb01.nbt}；场景标题、正文用的是**另一套键**
 * （{@code createhardener.ponder.hb01.header} / {@code .text_1} …），见
 * {@link HardenerPonderScenes}。
 *
 * <p>注册时机由 {@link HardenerPonderSetup} 负责：必须早于 Ponder 的
 * {@code PonderIndex.registerAll()}（modLoadCompleted 时执行）。
 */
public class HardenerPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return CreateHardener.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // Function.identity()：组件本身就是注册键（ResourceLocation），不用再做映射
        PonderSceneRegistrationHelper<ResourceLocation> scenes = helper.withKeyFunction(Function.identity());

        // 硬化块有三个场景（展示台 / 带水场 / 最简台）
        var hardened = scenes.forComponents(rl("hardened_block"));
        hardened.addStoryBoard("hb01", HardenerPonderScenes::hardenedBlock1);
        hardened.addStoryBoard("hb02", HardenerPonderScenes::hardenedBlock2);
        hardened.addStoryBoard("hb03", HardenerPonderScenes::hardenedBlock3);

        // 烧红硬化块单独一个场景（管路 / 注液器 / 水 / 蓝冰 = 四种冷却出口）
        scenes.forComponents(rl("hot_hardened_block"))
                .addStoryBoard("hhb01", HardenerPonderScenes::hotHardenedBlock);

        // 谐振感磁块有两个场景（继承链 / 另一个待定），都挂在同一个方块上
        var magneto = scenes.forComponents(rl("resonant_magnetosensitive_block"));
        magneto.addStoryBoard("rmb01", HardenerPonderScenes::resonantMagnet1);
        magneto.addStoryBoard("rmb02", HardenerPonderScenes::resonantMagnet2);

        scenes.forComponents(rl("blast_turbine_engine"))
                .addStoryBoard("bte01", HardenerPonderScenes::blastTurbineEngine);

        scenes.forComponents(rl("hardened_fluid_tank"))
                .addStoryBoard("hft01", HardenerPonderScenes::hardenedFluidTank);

        scenes.forComponents(rl("hardened_fluid_pump"))
                .addStoryBoard("hfp01", HardenerPonderScenes::hardenedFluidPump);
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateHardener.MODID, path);
    }
}
