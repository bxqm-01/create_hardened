package dev.createhardener;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.tank.FluidTankBlock;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import dev.createhardener.sealant.ObsidianSealantItem;
import dev.createhardener.sealant.SealZoneEvents;
import dev.createhardener.sealant.SealZoneSync;

/**
 * 机械动力：硬化剂
 *
 * <p>玩家可见名称统一中文，内部 id 一律英文（沿用用户其它模组的风格）。
 * 创造模式有独立栏位「机械动力：硬化剂」（见 {@link HardeningTab}）。
 */
@Mod(CreateHardener.MODID)
public class CreateHardener {

    public static final String MODID = "createhardener";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    /**
     * 方块注册表（本模组自己那两张）：硬化块族 + 塑封外壳，外加流体的液体方块。
     *
     * <p>（{@link HardeningStages} 与 {@link SealShellBlocks} 各有自己的 {@code BLOCKS}，
     * 这是历史写法，功能上等价；新内容统一往这里放。）
     */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** 塑封外壳的方块实体类型（外壳记着"被套住的方块"，客户端照着渲染）。 */
    public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE, MODID);

    public static final net.neoforged.neoforge.registries.DeferredHolder<
            net.minecraft.world.level.block.entity.BlockEntityType<?>, net.minecraft.world.level.block.entity.BlockEntityType<SealShellBlockEntity>>
            SEAL_SHELL_BE = BLOCK_ENTITY_TYPES.register("sealed_shell",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .<SealShellBlockEntity>of(SealShellBlockEntity::new, SealShellBlocks.SEALED_SHELL.get())
                            .build(null));

    /**
     * **硬化流体储罐**（2026-09-26 新增）：原版**流体储罐的变体** —— 形状/行为整套沿用原版
     * （继承 {@link FluidTankBlock}），只把贴图换成用户新画的那一版。
     *
     * <p>属性**直接照抄原版储罐**（{@code Properties.ofFullCopy}），不靠猜；万一 Create 的注册表
     * 还没绑定（注册顺序变化），退回一份手写属性 —— 宁可数值略有差异，也不要抛 unbound 把模组崩掉。
     */
    public static final DeferredBlock<HardenedFluidTankBlock> HARDENED_FLUID_TANK =
            BLOCKS.register("hardened_fluid_tank",
                    () -> new HardenedFluidTankBlock(tankProperties()));

    /**
     * 储罐的物品形态。**必须用我们自己的物品类**（不是普通 {@code BlockItem}）：
     * 原版那套"往整层上一放、整层自动铺满"的行为在 {@link HardenedFluidTankItem} 里，
     * 而原版的 {@code FluidTankItem} 因为把方块实体类型写死成 Create 自己的，对我们无效。
     */
    public static final DeferredItem<BlockItem> HARDENED_FLUID_TANK_ITEM =
            ITEMS.register("hardened_fluid_tank",
                    () -> new HardenedFluidTankItem(HARDENED_FLUID_TANK.get(), new Item.Properties()));

    /**
     * **硬化流体泵**（2026-09-27 起，用户拍板"直接采用原版机制"）——
     * 整套继承原版（{@link PumpBlock}），只换贴图、方块实体类型、应力与"每 tick 搬运量"。
     * 属性照抄原版方块（{@code ofFullCopy}），万一 Create 注册表还没绑定就退回手写属性。
     *
     * <p>⚠️ **2026-09-27：硬化流体管道已整套删除**（用户决定）。理由是原版管道本来就
     * **不产生量、也不设上限**（它只把收到的 {@code FluidStack} 原样交给下一格），
     * 没必要做专用管道；"双倍"改为在泵这一侧把压力翻倍 —— 见
     * {@code dev.createhardener.mixin.PumpPressureBoostMixin}。
     */
    public static final DeferredBlock<HardenedFluidPumpBlock> HARDENED_FLUID_PUMP =
            BLOCKS.register("hardened_fluid_pump",
                    () -> new HardenedFluidPumpBlock(copyProps(AllBlocks.MECHANICAL_PUMP.get(),
                            BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                                    .strength(1.5F, 6.0F).noOcclusion())));

    /** 泵的物品形态。 */
    public static final DeferredItem<BlockItem> HARDENED_FLUID_PUMP_ITEM =
            ITEMS.register("hardened_fluid_pump",
                    () -> new BlockItem(HARDENED_FLUID_PUMP.get(), new Item.Properties()));

    /**
     * **冲爆引擎**（2026-09-27 新增，用户给的模型 {@code blast_turbine_engine}）—— 一种新的**应力来源**：
     * 挨爆炸 → 攒应力、提转速，之后自然衰减。
     *
     * <p>模型是"东面接收、西面输出（露出半根传动轴）"，本方块把 {@code FACING} 定义成**输出面**，
     * 接收面 = {@code FACING} 的反面。见 {@link BlastTurbineEngineBlock} 的类注释。
     *
     * <p>抗性取**黑曜石级 1200**（用户要求"肯定不能让它被炸掉"）；真正保证不被炸掉的是
     * {@code mixin.ExplosionChargeMixin} 在破坏判定里返回 false —— 原因见那里的注释。
     */
    public static final DeferredBlock<BlastTurbineEngineBlock> BLAST_TURBINE_ENGINE =
            BLOCKS.register("blast_turbine_engine",
                    () -> new BlastTurbineEngineBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.5F, 1200.0F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            .requiresCorrectToolForDrops()));

    /** 冲爆引擎的物品形态。 */
    public static final DeferredItem<BlockItem> BLAST_TURBINE_ENGINE_ITEM =
            ITEMS.register("blast_turbine_engine",
                    () -> new BlockItem(BLAST_TURBINE_ENGINE.get(), new Item.Properties()));

    /**
     * 储罐的方块实体类型 —— **必须是我们自己的**：否则会创建出原版储罐的方块实体，
     * 而且管道的能力查询也认不出我们的方块。
     */
    public static final net.neoforged.neoforge.registries.DeferredHolder<
            net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<HardenedFluidTankBlockEntity>>
            HARDENED_FLUID_TANK_BE = BLOCK_ENTITY_TYPES.register("hardened_fluid_tank",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .<HardenedFluidTankBlockEntity>of(HardenedFluidTankBlockEntity::new,
                                    HARDENED_FLUID_TANK.get())
                            .build(null));

    /** 硬化流体泵的方块实体类型。 */
    public static final net.neoforged.neoforge.registries.DeferredHolder<
            net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<HardenedFluidPumpBlockEntity>>
            HARDENED_FLUID_PUMP_BE = BLOCK_ENTITY_TYPES.register("hardened_fluid_pump",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .<HardenedFluidPumpBlockEntity>of(HardenedFluidPumpBlockEntity::new,
                                    HARDENED_FLUID_PUMP.get())
                            .build(null));

    /** 冲爆引擎的方块实体类型（**必须是我们自己的**，否则会创建出别的方块实体）。 */
    public static final net.neoforged.neoforge.registries.DeferredHolder<
            net.minecraft.world.level.block.entity.BlockEntityType<?>,
            net.minecraft.world.level.block.entity.BlockEntityType<BlastTurbineEngineBlockEntity>>
            BLAST_TURBINE_ENGINE_BE = BLOCK_ENTITY_TYPES.register("blast_turbine_engine",
                    () -> net.minecraft.world.level.block.entity.BlockEntityType.Builder
                            .<BlastTurbineEngineBlockEntity>of(BlastTurbineEngineBlockEntity::new,
                                    BLAST_TURBINE_ENGINE.get())
                            .build(null));

    /** 照抄原版方块属性，失败则退回手写值（宁可数值略有差异，也不要抛 unbound 把模组崩掉）。 */
    private static BlockBehaviour.Properties copyProps(net.minecraft.world.level.block.Block source,
                                                       BlockBehaviour.Properties fallback) {
        try {
            return BlockBehaviour.Properties.ofFullCopy(source);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * 储罐属性：**整份照抄原版储罐**（{@code ofFullCopy}），外面包一层 try/catch —— 万一 Create 注册表
     * 还没绑定（注册顺序变化），退回一份手写属性；宁可数值略有差异，也不要抛 unbound 把模组崩掉。
     */
    private static BlockBehaviour.Properties tankProperties() {
        try {
            return BlockBehaviour.Properties.ofFullCopy(AllBlocks.FLUID_TANK.get());
        } catch (Throwable t) {
            return BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .sound(FluidTankBlock.SILENCED_METAL);
        }
    }

    /** Create 的黑曜石粉：由 data/create/recipe/crushing/obsidian.json 粉碎黑曜石产出 */
    public static final TagKey<Item> OBSIDIAN_POWDER =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "dusts/obsidian"));

    // ---------------------------------------------------------------- 材料链（2026-09-28 用户要求）

    /**
     * **硬化锭** —— 由**烧红的硬化块台阶冲压**得到（一次 2 个，见 `data/.../recipe/pressing/`），
     * 冲压硬化锭得到 {@link #HARDENED_PLATE}。
     */
    public static final DeferredItem<Item> HARDENED_INGOT =
            ITEMS.register("hardened_ingot", () -> new Item(new Item.Properties()));

    /** **硬化板** —— 硬化锭冲压一次得到；是储罐/泵/塑封剂/系列组装的主材料。 */
    public static final DeferredItem<Item> HARDENED_PLATE =
            ITEMS.register("hardened_plate", () -> new Item(new Item.Properties()));

    /** **硬化构件** —— 硬化板经**系列组装**（小齿轮 → 大齿轮 → 金粒，循环 3 次）得到的产物。 */
    public static final DeferredItem<Item> HARDENED_COMPONENT =
            ITEMS.register("hardened_component", () -> new Item(new Item.Properties()));

    /**
     * 系列组装的**过渡件**（Create 惯例：每个系列组装都必须有一个 `incomplete_*` 中间件，
     * 装配过程中手上/传送带上显示的就是它）。
     * ⚠️ 它不是"废料"，而是装配中途的物品形态 —— 少了它 `create:sequenced_assembly` 无法解析。
     */
    public static final DeferredItem<Item> INCOMPLETE_HARDENED_COMPONENT =
            ITEMS.register("incomplete_hardened_component", () -> new Item(new Item.Properties()));

    /** 不允许被塑封的方块（基岩、屏障、命令方块…）。数据包标签，可继续往里加。 */
    public static final TagKey<Block> SEAL_BLACKLIST =
            TagKey.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(MODID, "seal_blacklist"));

    /** 塑封剂耐久：每塑封 1 格体积消耗 1 点。 */
    public static final int SEALANT_DURABILITY = 256;

    /**
     * **烧红块族的爆炸抗性**（2026-09-28 用户要求："把烧红的硬化块以及其变体的爆炸抗性提高一些，
     * 提到和黑曜石差不多的程度"）。
     *
     * <p>取 **1200 = 黑曜石 / 硬化块第 0 档**（四档 `HardeningStages.BLAST` = 1200/600/90/15）。
     * 整砖 / 半砖 / 楼梯三族**共用这一个常量**，以后只调这一处。
     *
     * <p>⚠️ 只动**爆炸抗性**：硬度仍是 3.5（"刚出炉"的软形态，好挖）—— 用户这次只要抗性。
     */
    public static final float HOT_BLAST_RESISTANCE = 1200.0F;

    /**
     * **烧红的硬化块**（2026-09-26 新增）：硬化块刚出炉的形态。
     * 发光、站上去会烫伤、放下 1200 刻后自然冷却成硬化块、碰水立刻变硬化块。
     * 见 {@link HotHardenedBlock}。
     */
    public static final DeferredBlock<HotHardenedBlock> HOT_HARDENED_BLOCK =
            BLOCKS.register("hot_hardened_block",
                    () -> new HotHardenedBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties.of()
                            .mapColor(net.minecraft.world.level.material.MapColor.COLOR_ORANGE)
                            .strength(3.5F, HOT_BLAST_RESISTANCE)
                            .lightLevel(s -> 12)          // 发光（比岩浆 15 弱一档）
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
                            .randomTicks()));

    /**
     * **烧红的硬化块·半砖 / 楼梯**（2026-09-28 新增）：由烧红整砖**切割**得到；
     * 行为与整砖完全一致（共用 {@link HotCuring}），冷却出口也一样：
     * 注水→普通硬化块、方块遇水→微裂、遇冰→风化、自然冷却→普通硬化块。
     */
    public static final DeferredBlock<HotHardenedSlabBlock> HOT_HARDENED_BLOCK_SLAB =
            BLOCKS.register("hot_hardened_block_slab",
                    () -> new HotHardenedSlabBlock(hotProperties()));

    public static final DeferredBlock<HotHardenedStairBlock> HOT_HARDENED_BLOCK_STAIRS =
            BLOCKS.register("hot_hardened_block_stairs",
                    () -> HotHardenedStairBlock.create(hotProperties()));

    /** 烧红方块那一套属性（发光 12 / 抗性 {@link #HOT_BLAST_RESISTANCE} / 随机刻）—— 与上面整砖那份保持一致。 */
    private static BlockBehaviour.Properties hotProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_ORANGE)
                .strength(3.5F, HOT_BLAST_RESISTANCE)
                .lightLevel(s -> 12)
                .sound(SoundType.STONE)
                .requiresCorrectToolForDrops()
                .randomTicks();
    }

    // ⚠️ 烧红的硬化块**与 12 个硬化块一起放进 BLOCK_ITEMS**，所以它不该有第二个物品字段：
    //    物品只注册一次（静态块里那份就是 HotHardenedBlockItem）。若在这里再 ITEMS.register("hot_hardened_block", ...)，
    //    NeoForge 会抛 "Duplicate registration hot_hardened_block" 导致整个模组加载失败（beta58 的崩因）。

    /** 12 个方块 + 烧红块的物品（4 档 × 整砖/半砖/楼梯，外加 hot_hardened_block）
     *  —— 元素类型写成 {@code ? extends BlockItem}，因为烧红块用的是 {@link HotHardenedBlockItem} 子类。 */
    public static final List<DeferredItem<? extends BlockItem>> BLOCK_ITEMS = new ArrayList<>();

    /**
     * **谐振感磁块**（2026-09-25 新增）：类侦测器摆放 —— 正面 = 侦测端、背面 = 禁止端，
     * 上/下/左/右四个面按继承到的红石强度（0~15）换 16 张贴图。
     *
     * <p>机制不是侦测器：它把**侦测端前方那格的信号原样继承**到自己身上，
     * **不向外充能**，但用比较器读时输出同强度。实现见 {@link ResonantMagnetosensitiveBlock}。
     */
    public static final DeferredBlock<ResonantMagnetosensitiveBlock> RESONANT_BLOCK =
            BLOCKS.register("resonant_magnetosensitive_block",
                    () -> new ResonantMagnetosensitiveBlock(BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            // 基础值取"无信号档"（power=0）：硬度 2 / 抗性 10。
                            // 实际生效值由 ResonantMagnetosensitiveBlock 按 power 覆写决定。
                            .strength(2.0F, 10.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    /** 谐振感磁块的物品形态。 */
    public static final DeferredItem<BlockItem> RESONANT_BLOCK_ITEM =
            ITEMS.registerSimpleBlockItem("resonant_magnetosensitive_block", RESONANT_BLOCK);

    /** 黑曜石塑封剂：框选一片区域，让区域内方块能扛一次爆炸 / 挖起来像黑曜石。 */
    public static final DeferredItem<ObsidianSealantItem> OBSIDIAN_SEALANT =
            ITEMS.register("obsidian_sealant",
                    () -> new ObsidianSealantItem(new Item.Properties()
                            .stacksTo(1)
                            .durability(SEALANT_DURABILITY)
                            .rarity(Rarity.UNCOMMON)));

    /**
     * 烧红的硬化块的物品形态（**背包里会烫伤玩家**，见 {@link HotHardenedBlockItem}）。
     * 在静态块里注册（那里能安全写多行），并加入 {@link #BLOCK_ITEMS} 以便出现在创造页。
     */
    public static final DeferredItem<HotHardenedBlockItem> HOT_HARDENED_BLOCK_ITEM;

    /** 烧红半砖 / 楼梯的物品形态（**同样会烫手**，与整砖共用 {@link HotHardenedBlockItem}）。 */
    public static final DeferredItem<HotHardenedBlockItem> HOT_HARDENED_BLOCK_SLAB_ITEM;
    public static final DeferredItem<HotHardenedBlockItem> HOT_HARDENED_BLOCK_STAIRS_ITEM;

    static {
        // 只登记物品，不在此解析方块（静态期方块未绑定）
        for (DeferredBlock<? extends Block> block : HardeningStages.ALL) {
            BLOCK_ITEMS.add(registerBlockItem(block));
        }

        // 烧红的硬化块**不属于 12 个硬化块**，物品类也不同（背包烫伤），所以单独注册这一次 ——
        // ⚠️ 只注册一次！再单独 register 一次会抛 "Duplicate registration hot_hardened_block"，
        //    直接导致整个模组加载失败（beta58 崩因）。
        HOT_HARDENED_BLOCK_ITEM = ITEMS.register("hot_hardened_block",
                () -> new HotHardenedBlockItem(HOT_HARDENED_BLOCK.get(), new Item.Properties()));
        BLOCK_ITEMS.add(HOT_HARDENED_BLOCK_ITEM);

        // 烧红的半砖 / 楼梯（2026-09-28）：物品类相同（背包里同样烫手）。
        // ⚠️ 它们**不在** HardeningStages.ALL 里，上面的循环不会替它们注册物品 —— 这里各注册**一次**就够，
        //    千万别再补第二处（重复注册会让 NeoForge 抛 Duplicate registration，整个模组加载失败）。
        HOT_HARDENED_BLOCK_SLAB_ITEM = ITEMS.register("hot_hardened_block_slab",
                () -> new HotHardenedBlockItem(HOT_HARDENED_BLOCK_SLAB.get(), new Item.Properties()));
        BLOCK_ITEMS.add(HOT_HARDENED_BLOCK_SLAB_ITEM);

        HOT_HARDENED_BLOCK_STAIRS_ITEM = ITEMS.register("hot_hardened_block_stairs",
                () -> new HotHardenedBlockItem(HOT_HARDENED_BLOCK_STAIRS.get(), new Item.Properties()));
        BLOCK_ITEMS.add(HOT_HARDENED_BLOCK_STAIRS_ITEM);
    }

    /**
     * 必须走 Supplier 重载：若把 DeferredBlock 当 Holder 传进去，NeoForge 会在静态初始化期
     * 就解析它，而此时方块尚未绑定，会抛 "Trying to access unbound value"。
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static DeferredItem<BlockItem> registerBlockItem(DeferredBlock<? extends Block> block) {
        String id = block.getKey().location().getPath();
        return ITEMS.registerSimpleBlockItem(id, (java.util.function.Supplier) block);
    }

    public CreateHardener(IEventBus modEventBus) {
        HardeningStages.BLOCKS.register(modEventBus);
        SealShellBlocks.BLOCKS.register(modEventBus);
        // 流体：液体方块 + 流体类型 + 流体本身（注册顺序不敏感，都在 RegisterEvent 期统一绑定）
        BLOCKS.register(modEventBus);
        HardenedEmulsionFluids.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        HardeningTab.TABS.register(modEventBus);

        // 塑封区域的网络同步
        modEventBus.addListener(this::registerPayloads);
        // 储罐的流体能力：**必须给自己的方块实体类型注册**，否则管道接不上
        modEventBus.addListener(this::registerCapabilities);
        // 塑封的保护逻辑（挖掘速度 / 挖掉 / 爆炸）挂在游戏事件总线
        NeoForge.EVENT_BUS.register(SealZoneEvents.class);
        // 描边同步的"每 tick 合并发送"（见 SealZoneSync.onServerTick）
        NeoForge.EVENT_BUS.register(SealZoneSync.class);
        // 爆炸把硬化块变烧红（NeoForge 官方 Detonate 事件，**不写 mixin**）
        NeoForge.EVENT_BUS.register(ScorchingEvents.class);
        // 注液器"浇在已放置的烧红方块上 → 普通硬化块"（Create 官方 BlockSpoutingBehaviour）
        modEventBus.addListener(this::registerSpouting);
    }

    /** 注水消耗量（与`烧红块 + 100mB 水 → 硬化块`那条注液配方保持一致）。 */
    private static final int SPOUT_WATER_AMOUNT = 100;

    /**
     * **注液器浇在已放置的烧红方块上 → 普通硬化块**（用户 2026-09-28："也可以添加一个注水已放置方块的内容"）。
     *
     * <p>用 Create 6 **官方 api**：`BlockSpoutingBehaviour.BY_BLOCK` 注册表 +
     * `StateChangingBehavior.setTo(消耗量, 流体判定, 目标方块状态)` —— 原版就是用它做"浇水把混凝土粉末变混凝土"
     * 那一类行为的（`AllBlockSpoutingBehaviours`），我们**照抄**，不自己写 `fillBlock`。
     *
     * <p>⚠️ 必须在 `FMLCommonSetupEvent` 里注册（那时方块才绑定）；构造期直接 `.get()` 会抛 unbound。
     */
    private void registerSpouting(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            java.util.function.Predicate<net.minecraft.world.level.material.Fluid> water =
                    fluid -> fluid.is(net.minecraft.tags.FluidTags.WATER);
            registerSpout(HOT_HARDENED_BLOCK.get(), water, HardeningStages.Family.FULL);
            registerSpout(HOT_HARDENED_BLOCK_SLAB.get(), water, HardeningStages.Family.SLAB);
            registerSpout(HOT_HARDENED_BLOCK_STAIRS.get(), water, HardeningStages.Family.STAIR);
        });
    }

    /** 给一个烧红方块挂上"浇 100mB 水 → 同形状的第 0 档硬化块"。 */
    private static void registerSpout(Block hotBlock,
                                      java.util.function.Predicate<net.minecraft.world.level.material.Fluid> water,
                                      HardeningStages.Family family) {
        com.simibubi.create.api.behaviour.spouting.BlockSpoutingBehaviour.BY_BLOCK.register(hotBlock,
                com.simibubi.create.api.behaviour.spouting.StateChangingBehavior.setTo(
                        SPOUT_WATER_AMOUNT, water,
                        HardeningStages.of(family, 0).get().defaultBlockState()));
    }

    /**
     * 储罐的流体能力注册。
     *
     * <p>Create 的 {@code FluidTankBlockEntity.registerCapabilities} 只给**它自己**的
     * {@code AllBlockEntityTypes.FLUID_TANK} 注册了 {@code Capabilities.FluidHandler.BLOCK}，
     * 所以我们的类型要自己来一次 —— 否则管道/水桶从罐子身上读不到流体能力（这是储罐的核心功能）。
     */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, HARDENED_FLUID_TANK_BE.get(),
                (be, side) -> be.fluidHandler());
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(
                SealZoneSync.TYPE,
                SealZoneSync.STREAM_CODEC,
                SealZoneSync::handle);
        // 客户端第二次右键时，把框选的两个角**一次性**提交给服务端（Create 超级胶水的模型）。
        // 两次点击之间服务端不持有任何状态，所以不存在前后手不同步的问题。
        registrar.playToServer(
                dev.createhardener.sealant.SealSelectionPacket.TYPE,
                dev.createhardener.sealant.SealSelectionPacket.STREAM_CODEC,
                dev.createhardener.sealant.SealSelectionPacket::handle);
    }
}
