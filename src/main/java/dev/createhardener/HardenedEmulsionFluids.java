package dev.createhardener;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.BaseFlowingFluid;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 硬化乳浊液（流体）与它的桶。
 *
 * <p>内容：注册 {@code createhardener:hardened_emulsion}（源 + 流动两态）、对应的液体方块、
 * 以及 **硬化乳浊液桶**。用户 2026-09-24 明确：**暂时不给任何特性，就是正常流体**，
 * 唯一要求是**流动速度慢一些**。
 *
 * <p>写法照抄原版水 + NeoForge 的 {@link BaseFlowingFluid}。流速由三个参数控制：
 * <ul>
 *   <li>{@code tickRate} —— 流动**推进间隔**（原版水 = 5 tick）；调大 → 慢；</li>
 *   <li>{@code levelDecreasePerBlock} —— 每走一格**掉多少液面**（原版 1）；调大 → 铺得近、更黏；</li>
 *   <li>{@code slopeFindDistance} —— 找"能往下走的位置"的搜索半径（原版水 = 4）。</li>
 * </ul>
 * 这里取 {@code tickRate=15 / levelDecreasePerBlock=2 / slopeFindDistance=3}（约水的 1/3 速）。
 * 想调快调慢只改这三个数。
 *
 * <h2>为什么要用静态块（别改成字段初始化器链，会编译不过）</h2>
 * 桶引用流体、液体方块引用流体、流体又引用桶和方块，三向循环。{@link BaseFlowingFluid.Properties}
 * 把这些引用都收成 {@link Supplier}，所以"先建 Properties、再建流体、最后挂桶和方块"就能闭环。
 * <p>但 javac 有两条硬规则挡路：① **非法前向引用**（字段初始化器不能引用声明在后面的字段）；
 * ② **明确赋值**（static 块里 lambda 一旦捕获"还没赋值的字段"，报"可能尚未初始化变量"）。
 * 故这里：对外字段声明为 {@link DeferredHolder}（泛型自动逆变，不别扭），
 * 传给 Properties 的 {@link Supplier} 存成**局部变量**，最后一次性赋值给字段。
 * **改动时请保持这个结构。**
 */
public final class HardenedEmulsionFluids {

    private HardenedEmulsionFluids() {
    }

    /** 流体类型注册表（NeoForge 自带，不在原版 Registries 里）。 */
    public static final DeferredRegister<FluidType> FLUID_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.FLUID_TYPES, CreateHardener.MODID);

    /** 原版流体注册表。 */
    public static final DeferredRegister<Fluid> FLUIDS =
            DeferredRegister.create(Registries.FLUID, CreateHardener.MODID);

    /** 流体类型：密度/黏度/是否可游可沉 + 客户端贴图（见 {@link HardenedEmulsionFluidType}）。 */
    public static final DeferredHolder<FluidType, FluidType> EMULSION_TYPE;

    /** 流体属性（源、流动、桶、液体方块四个引用都挂在它上面）。 */
    public static final BaseFlowingFluid.Properties PROPERTIES;

    /** 静态源（倒下去的那一格）。 */
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Source> EMULSION_SOURCE;

    /** 流动态。 */
    public static final DeferredHolder<Fluid, BaseFlowingFluid.Flowing> EMULSION_FLOWING;

    /** 液体方块（世界里那层流体）：用水的属性打底，行为见 {@link HardenedEmulsionBlock}。 */
    public static final DeferredBlock<HardenedEmulsionBlock> EMULSION_BLOCK;

    /**
     * 硬化乳浊液桶。
     *
     * <p>{@code craftRemainder} 设成空桶 —— 倒出乳浊液后手里留下空桶（与水桶/岩浆桶一致；
     * 不设的话桶用一次就消失了）。
     */
    public static final DeferredItem<BucketItem> EMULSION_BUCKET;

    /**
     * **遗骸沉积岩**（2026-09-26 新增，用户原话："与岩浆在一起时，则会生成遗骸沉积岩，
     * 这个遗骸沉积岩需要我们新注册一个素材……它是和深板岩属性差不多的石头"）。
     *
     * <p>属性照深板岩：硬度 3.0 / 爆炸抗性 6.0 / 需要正确工具 / 声音 {@code DEEPSLATE}。
     * 由 {@link HardenedEmulsionBlock} 在"乳浊液遇到岩浆"时就地生成。
     */
    public static final DeferredBlock<Block> ANCIENT_DEBRIS_SEDIMENTARY_ROCK;

    /** 遗骸沉积岩的**物品形态**（必须注册，否则创造页放不进去）。 */
    public static final DeferredItem<BlockItem> ANCIENT_DEBRIS_SEDIMENTARY_ROCK_ITEM;

    /**
     * **远古合金碎屑**（2026-09-26 新增，用户原话："同时注册一个新物品，叫做远古合金碎屑，
     * 后续关于我们这个模组的配方相关，可能会用到它"）。
     *
     * <p>暂时没有任何配方/用途，先注册出来备用。
     */
    public static final DeferredItem<Item> ANCIENT_ALLOY_SCRAP;

    static {
        // ⚠️ 全程只用**局部变量**：任何一个 lambda 里只要读了"还没赋值的静态字段"，
        //    javac 就会按"明确赋值"规则直接报错（即使那个 lambda 要到注册之后才执行）。
        //    规矩：**lambda 只捕获局部变量，字段只在最后统一赋值。**
        //
        //    又因为流体工厂要读 Properties、而 Properties 又要引用流体，用长度 1 的数组
        //    做一个"先声明、后填值"的中转，顺序就完全顺了。

        BaseFlowingFluid.Properties[] propsRef = new BaseFlowingFluid.Properties[1];

        DeferredHolder<FluidType, FluidType> type = FLUID_TYPES.register(
                "hardened_emulsion", HardenedEmulsionFluidType::new);

        DeferredHolder<Fluid, BaseFlowingFluid.Source> source = FLUIDS.register(
                "hardened_emulsion", () -> new BaseFlowingFluid.Source(propsRef[0]));
        DeferredHolder<Fluid, BaseFlowingFluid.Flowing> flowing = FLUIDS.register(
                "flowing_hardened_emulsion", () -> new BaseFlowingFluid.Flowing(propsRef[0]));

        // ⚠️ **发光必须加在"液体方块自己的 Properties"上**（BlockBehaviour.Properties.lightLevel），
        //    在 FluidType.Properties 上设的 lightLevel 只管流体元数据、**不影响世界光照**
        //    （beta55 只设了后者 → 用户报"我们的硬化乳浊液并不能发光"）。
        DeferredBlock<HardenedEmulsionBlock> block = CreateHardener.BLOCKS.register("hardened_emulsion",
                () -> new HardenedEmulsionBlock(source.get(),
                        BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)
                                .lightLevel(s -> HardenedEmulsionFluidType.LIGHT_LEVEL)));
        DeferredItem<BucketItem> bucket = CreateHardener.ITEMS.register("hardened_emulsion_bucket",
                () -> new BucketItem(source.get(),
                        new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1)));

        // 遗骸沉积岩：属性照深板岩（用户："和深板岩属性差不多的石头"）。
        // ⚠️ 它是**普通方块**，所以**必须注册对应的 BlockItem** —— 否则创造模式标签页
        //    `output.accept(block)` 会拿到空物品栈（count=0），NeoForge 直接抛
        //    "The stack count must be 1" 崩客户端（beta55 就栽在这）。
        DeferredBlock<Block> sedimentaryRock = CreateHardener.BLOCKS.register(
                "ancient_debris_sedimentary_rock",
                () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.DEEPSLATE)));
        DeferredItem<BlockItem> sedimentaryRockItem = CreateHardener.ITEMS.registerSimpleBlockItem(
                "ancient_debris_sedimentary_rock", sedimentaryRock);

        // 远古合金碎屑：暂时没有用途，先注册出来（用户说"后续配方可能用到"）
        DeferredItem<Item> alloyScrap = CreateHardener.ITEMS.register(
                "ancient_alloy_scrap", () -> new Item(new Item.Properties()));

        BaseFlowingFluid.Properties props = new BaseFlowingFluid.Properties(
                () -> type.get(),
                () -> source.get(),
                () -> flowing.get())
                .bucket(() -> bucket.get())
                .block(() -> block.get())
                // 流速三件套（比水慢约 1/3）
                .tickRate(15)
                .levelDecreasePerBlock(2)
                .slopeFindDistance(3);
        propsRef[0] = props;

        // 最后一次性赋给对外字段
        EMULSION_TYPE = type;
        EMULSION_SOURCE = source;
        EMULSION_FLOWING = flowing;
        EMULSION_BLOCK = block;
        EMULSION_BUCKET = bucket;
        ANCIENT_DEBRIS_SEDIMENTARY_ROCK = sedimentaryRock;
        ANCIENT_DEBRIS_SEDIMENTARY_ROCK_ITEM = sedimentaryRockItem;
        ANCIENT_ALLOY_SCRAP = alloyScrap;
        PROPERTIES = props;
    }

    /** 供 {@link CreateHardener} 在模组构造期挂到事件总线（流体类型与流体都要注册）。 */
    public static void register(IEventBus bus) {
        FLUID_TYPES.register(bus);
        FLUIDS.register(bus);
    }

    /** 该流体类型所在的注册表 key（调试用）。 */
    public static final ResourceKey<Registry<FluidType>> FLUID_TYPE_KEY = NeoForgeRegistries.Keys.FLUID_TYPES;
}
