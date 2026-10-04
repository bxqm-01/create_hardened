package dev.createhardener.client.ponder;

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import dev.createhardener.BlastTurbineEngineBlock;
import dev.createhardener.BlastTurbineEngineBlockEntity;
import dev.createhardener.CreateHardener;
import dev.createhardener.HardenedFluidTankBlockEntity;
import dev.createhardener.HardeningStages;
import dev.createhardener.ResonantMagnetosensitiveBlock;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 九个思索场景的剧本。
 *
 * <p>已写好的四个：{@code rmb01}（感磁块·继承与比较器）、{@code rmb02}（感磁块·物理化整体随信号倾斜）、
 * {@code hfp01}（泵·双倍应力双倍运速）、{@code hft01}（储罐·7×7 且不做锅炉）。
 * 其余五个（硬化块 hb01/hb02/hb03、烧红块 hhb01、冲爆引擎 bte01）仍是**只显示结构的占位骨架**。
 *
 * <p><b>文案一律走语言键</b>（{@code createhardener.ponder.<场景id>.header / .text_N}），
 * 与军火工艺那两个场景同一套约定 —— Ponder 是拿字符串当**翻译键**显示的，直接塞中文虽然
 * 也能靠"查不到就用原串"侥幸显示，但标题会露出场景 id，所以规则语言文件里都补齐。
 *
 * <p>坐标都是**结构 nbt 的局部坐标**（结构导出时最小角被归一成 0,0,0），
 * 用户改了结构，这里的所有坐标都要跟着改。
 */
public final class HardenerPonderScenes {

    /** 场景标题 id（同时是文案键里的那一段）：{@code createhardener.ponder.<id>.header}。 */
    private static final String RMB_1 = "rmb01";
    private static final String RMB_2 = "rmb02";
    private static final String PUMP = "hfp01";
    private static final String TANK = "hft01";
    private static final String BTE = "bte01";
    private static final String HB_1 = "hb01";
    private static final String HB_2 = "hb02";
    private static final String HB_3 = "hb03";
    private static final String HOT = "hhb01";

    private HardenerPonderScenes() {
    }

    // ------------------------------------------------------------------ 谐振感磁块（一）：继承 + 比较器读取

    /**
     * 结构 rmb01：模拟拉杆 (4,2,2) → 感磁块 (4,2,3)facing=north → (4,2,4)facing=north → (3,2,4)facing=east，
     * 链尾旁边是**比较器** (2,2,4)facing=east；地板上的脚手架只是垫高。
     */
    public static void resonantMagnet1(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos lever = util.grid().at(4, 2, 2);
        BlockPos magnetA = util.grid().at(4, 2, 3);
        BlockPos magnetB = util.grid().at(4, 2, 4);
        BlockPos magnetC = util.grid().at(3, 2, 4);
        BlockPos comparator = util.grid().at(2, 2, 4);
        Selection chain = util.select().fromTo(3, 2, 3, 4, 2, 4);

        scene.title(RMB_1, "谐振感磁块（一）");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(15);

        // ① 把模拟拉杆拉到 15（拉杆的档位在方块实体 NBT 的 "State" 里，不在方块状态）
        scene.overlay().showOutline(PonderPalette.RED, lever, util.select().position(lever), 40);
        scene.world().modifyBlockEntityNBT(util.select().position(lever), AnalogLeverBlockEntity.class,
                nbt -> nbt.putInt("State", 15), false);
        scene.idle(25);

        // ② 三块感磁块依次把前方强度继承到身上（Ponder 里没有红石模拟，逐块显式改 POWER）
        hardener$setMagnetoPower(scene, magnetA, 15);
        scene.idle(12);
        hardener$setMagnetoPower(scene, magnetB, 15);
        scene.idle(12);
        hardener$setMagnetoPower(scene, magnetC, 15);
        scene.idle(20);

        // ③ 链尾那个比较器被点亮（它读的是感磁块"继承到的同强度"）
        scene.overlay().showOutline(PonderPalette.OUTPUT, comparator, util.select().position(comparator), 40);
        hardener$setComparatorPowered(scene, comparator, true);
        scene.idle(25);

        // ④ 标记整条链 + 说明
        scene.overlay().showOutlineWithText(chain, 150)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(RMB_1, "text_1"));
        scene.idle(170);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 谐振感磁块（二）：物理化整体随信号倾斜

    /**
     * 结构 rmb02：一根横梁（x=2..6、z=4、y=2 的深板岩砖 + 两端各一块感磁块）压在中间的铁栏杆上，
     * 梁顶两端各有一个普通拉杆 (3,3,4) / (5,3,4)。
     *
     * <p>按用户 2026-10-04 的要求：**只激活西端（左）那个拉杆**（东端不动）⇒ 西端感磁块继承到 15、
     * 东端仍是 0；然后把这个独立段**向左（西）倾斜**。
     */
    public static void resonantMagnet2(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos leverWest = util.grid().at(3, 3, 4);
        BlockPos magnetWest = util.grid().at(2, 2, 4);
        Selection beam = util.select().fromTo(2, 2, 4, 6, 3, 4);

        scene.title(RMB_2, "谐振感磁块（二）");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(15);

        // ① 把横梁（两端感磁块 + 中间砖 + 顶上两个拉杆）抽成一个可以整体运动的独立段
        ElementLink<WorldSectionElement> contraption = scene.world().makeSectionIndependent(beam);
        scene.idle(20);

        // ② 只激活西端（左）那个拉杆 → 它脚下的砖带电 → 西端感磁块继承到 15（东端保持 0）
        scene.overlay().showOutline(PonderPalette.RED, leverWest, util.select().position(leverWest), 40);
        scene.world().modifyBlockEntityNBT(util.select().position(leverWest), AnalogLeverBlockEntity.class,
                nbt -> nbt.putInt("State", 15), false);
        scene.idle(20);
        hardener$setMagnetoPower(scene, magnetWest, 15);
        scene.idle(25);

        // ③ 整体向被激活的那一端（西 / -X）倾斜：绕 Z 轴转，正角度 = 西端下沉
        scene.world().configureCenterOfRotation(contraption,
                util.vector().centerOf(4, 2, 4).add(0.0D, -0.5D, 0.0D));
        scene.world().rotateSection(contraption, 0.0D, 0.0D, 15.0D, 40);
        scene.idle(50);

        // ④ 标记整体 + 说明
        scene.overlay().showOutlineWithText(beam, 150)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(RMB_2, "text_1"));
        scene.idle(170);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 硬化流体泵：双倍应力、双倍运速

    /**
     * 结构 hfp01：泵 (4,1,3)facing=west，管线 (3,1,3)/(3,1,4) 与 (5,1,3)/(5,1,4) 接两侧的 1×1×2 小储罐
     * （西 (2,1,4)/(2,2,4)、东 (6,1,4)/(6,2,4)），南边一排齿轮 (4,1,4..7) + (4,0,7) 供能。
     *
     * <p>按用户 2026-10-04 的要求：**先把东罐灌满，再以一定速率一段段抽到西罐** ——
     * 左边减水、右边同步加水（抽多少就灌多少，走的是同一份 FluidStack，天然守恒）；
     * 齿轮与泵**一直转着**（用户要求），水已经在另一边，终态保持"东空西满"。
     */
    public static void hardenedFluidPump(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos pump = util.grid().at(4, 1, 3);
        BlockPos westTank = util.grid().at(2, 1, 4);
        BlockPos eastTank = util.grid().at(6, 1, 4);
        Selection kinetics = util.select().fromTo(4, 0, 4, 4, 1, 7);

        scene.title(PUMP, "硬化流体泵");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(15);

        // ① 齿轮与泵先停着 —— 免得"水还没灌好就被搬走"
        hardener$setKineticSpeed(scene, kinetics, 0.0F);
        hardener$setKineticSpeed(scene, util.select().position(pump), 0.0F);
        scene.idle(20);

        // ② 东侧储罐灌满水（给足量，罐子容量多少就装多少）
        scene.overlay().showOutline(PonderPalette.INPUT, eastTank, util.select().position(eastTank), 45);
        hardener$fillTank(scene, eastTank, 100000);
        scene.idle(30);

        // ③ 齿轮转起来（泵获得压力）→ 按泵的速率一点点抽到西罐：
        //    每 4 刻搬 100mB（20 段 = 2000mB，正好是 1×1×2 储罐的容量），左边减、右边同步加
        hardener$setKineticSpeed(scene, kinetics, 64.0F);
        hardener$setKineticSpeed(scene, util.select().position(pump), 64.0F);
        scene.idle(15);
        for (int i = 0; i < 20; i++) {
            hardener$transferTank(scene, eastTank, westTank, 100);
            hardener$propagatePipeChange(scene, pump);
            scene.idle(4);
        }

        // ④ 齿轮与泵**继续转着**（用户要求齿轮一直旋转）—— 水已经搬完，东罐空、西罐满，终态保持不变
        scene.idle(40);

        // ⑤ 标记泵 + 说明
        scene.overlay().showOutlineWithText(util.select().position(pump), 150)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(PUMP, "text_1"));
        scene.idle(170);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 硬化流体储罐：7×7、不做锅炉

    /** 结构 hft01：完整的 7×7×3 储罐（y=1..3，四周是开窗变体）。 */
    public static void hardenedFluidTank(SceneBuilder scene, SceneBuildingUtil util) {
        Selection tank = util.select().fromTo(1, 1, 1, 7, 3, 7);

        scene.title(TANK, "硬化流体储罐");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutlineWithText(tank, 160)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(TANK, "text_1"));
        scene.idle(180);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 硬化块（一）(二)(三) 与 烧红硬化块

    /** 硬化块（一）：结构 hb01 —— 5×3 展示面（整砖/半砖/楼梯 × 硬化/微裂/风化/脆化/烧红），只讲"有多种形态与转化方式"。 */
    public static void hardenedBlock1(SceneBuilder scene, SceneBuildingUtil util) {
        Selection display = util.select().fromTo(2, 1, 3, 6, 1, 5);

        scene.title(HB_1, "硬化块（一）");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(20);

        scene.overlay().showOutlineWithText(display, 170)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HB_1, "text_1"));
        scene.idle(190);
        scene.markAsFinished();
    }

    /**
     * 硬化块（二）：结构 hb02 —— 水在 (0..2, 1, 4..7) 那一角，其余是两层铺开的硬化块（含几块已风化的）。
     *
     * <p>风化从水边往外扩散：三次"波"逐圈扩大选区，**每次把选区内所有整砖硬化块往上推一档** ——
     * 于是越靠水越旧（水边最终脆化、外圈才微裂），视觉上就是"集体缓慢风化的过程"。
     */
    public static void hardenedBlock2(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title(HB_2, "硬化块（二）");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(20);

        Selection[] waves = {
                util.select().fromTo(0, 0, 3, 3, 1, 7),
                util.select().fromTo(0, 0, 2, 4, 1, 7),
                util.select().fromTo(0, 0, 2, 5, 1, 7),
        };
        for (Selection wave : waves) {
            scene.world().modifyBlocks(wave, HardenerPonderScenes::hardener$weatherUp, false);
            scene.idle(35);
        }
        scene.idle(20);

        scene.overlay().showOutlineWithText(util.select().fromTo(0, 0, 2, 6, 1, 7), 190)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HB_2, "text_1"));
        scene.idle(210);
        scene.markAsFinished();
    }

    /**
     * 硬化块（三）：结构 hb03 —— 悬空的 2×2×2 共 8 块硬化块（y=3..4），下面就是地板。
     *
     * <p>"物理化状态下受到冲击"：把 8 块抽成一个整体砸到地面上（`moveSection` 快速下坠），
     * 落地的下半层 4 块被撞出微裂。
     */
    public static void hardenedBlock3(SceneBuilder scene, SceneBuildingUtil util) {
        Selection cube = util.select().fromTo(3, 3, 3, 4, 4, 4);
        Selection landed = util.select().fromTo(3, 1, 3, 4, 2, 4);
        BlockPos[] bottom = {
                util.grid().at(3, 3, 3), util.grid().at(3, 3, 4),
                util.grid().at(4, 3, 3), util.grid().at(4, 3, 4),
        };

        scene.title(HB_3, "硬化块（三）");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(20);

        // ① 上方的 8 块视为一个整体，快速冲击地面（下落 2 格、12 刻）
        ElementLink<WorldSectionElement> falling = scene.world().makeSectionIndependent(cube);
        scene.idle(10);
        scene.world().moveSection(falling, new Vec3(0.0D, -2.0D, 0.0D), 12);
        scene.idle(20);

        // ② 落地的下半层 4 块被撞出微裂
        for (BlockPos p : bottom) {
            scene.world().modifyBlock(p, HardenerPonderScenes::hardener$weatherUp, false);
        }
        scene.idle(25);

        scene.overlay().showOutlineWithText(landed, 150)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HB_3, "text_1"));
        scene.idle(170);
        scene.markAsFinished();
    }

    /**
     * 烧红硬化块：结构 hhb01 —— 地板 (1..6,0,1..6) 是 reinforced_deepslate / deepslate_bricks 棋盘，
     * 其中 **(5,0,3) 是水**、**(5,0,5) 是蓝冰**；注液器在 (3,3,5)，管路 (3,0..2,7)+(3,3,6)(3,3,7)。
     *
     * <p>结构里**没有烧红块**，所以由剧本自己摆四块，位置按用户 2026-10-04 的更正：
     * **水的正上方 (5,1,3)** → 微裂、**冰的正上方 (5,1,5)** → 风化、
     * **原来那个对角 (3,0,3) 的上面一格 (3,1,3)** → 自然冷却、
     * **注液器正下方再下一格 (3,1,5)**（浇水）→ 普通硬化块。
     *
     * <p>用户要求**一块一块地来**：每块先单独放出来、演示完它的转化，再放下一个（不要四块一起出现）。
     */
    public static void hotHardenedBlock(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos onWater = util.grid().at(5, 1, 3);
        BlockPos onIce = util.grid().at(5, 1, 5);
        BlockPos onBrick = util.grid().at(3, 1, 3);
        BlockPos underSpout = util.grid().at(3, 1, 5);
        BlockPos spout = util.grid().at(3, 3, 5);

        scene.title(HOT, "烧红硬化块");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(20);

        // ① 水的正上方：放一块烧红块 → 淬成微裂
        hardener$setBlockType(scene, onWater, CreateHardener.HOT_HARDENED_BLOCK.get());
        scene.idle(15);
        scene.overlay().showOutlineWithText(util.select().position(onWater), 130)
                .colored(PonderPalette.INPUT)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HOT, "text_1"));
        hardener$setBlockType(scene, onWater, hardener$stageBlock(1));
        scene.idle(150);

        // ② 冰的正上方：放一块 → 淬成风化
        hardener$setBlockType(scene, onIce, CreateHardener.HOT_HARDENED_BLOCK.get());
        scene.idle(15);
        scene.overlay().showOutlineWithText(util.select().position(onIce), 130)
                .colored(PonderPalette.INPUT)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HOT, "text_2"));
        hardener$setBlockType(scene, onIce, hardener$stageBlock(2));
        scene.idle(150);

        // ③ 注液器正下方再下一格：放一块 → 浇上水直接变回普通硬化块
        hardener$setBlockType(scene, underSpout, CreateHardener.HOT_HARDENED_BLOCK.get());
        scene.idle(15);
        scene.overlay().showOutlineWithText(util.select().position(spout), 130)
                .colored(PonderPalette.OUTPUT)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HOT, "text_3"));
        hardener$propagatePipeChange(scene, spout);
        scene.effects().emitParticles(
                new Vec3(underSpout.getX() + 0.5D, underSpout.getY() + 1.5D, underSpout.getZ() + 0.5D),
                scene.effects().simpleParticleEmitter(ParticleTypes.SPLASH, Vec3.ZERO), 6.0F, 10);
        scene.idle(30);
        hardener$setBlockType(scene, underSpout, hardener$stageBlock(0));
        scene.idle(130);

        // ④ 原来那个对角的上面一格：放一块 → 没人管它，自然冷却（真要 60 秒，这里只演示一下）
        hardener$setBlockType(scene, onBrick, CreateHardener.HOT_HARDENED_BLOCK.get());
        scene.idle(15);
        scene.overlay().showOutlineWithText(util.select().position(onBrick), 140)
                .colored(PonderPalette.MEDIUM)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(HOT, "text_4"));
        scene.idle(160);
        hardener$setBlockType(scene, onBrick, hardener$stageBlock(0));
        scene.idle(60);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 冲爆引擎：爆炸 → 应力 / 过热损伤

    /**
     * 结构 bte01：硬化块地板 (2..5,1,2..5) + L 形挡墙（z=5 那排与 x=5 那排，y=2..3），
     * 两台引擎嵌在墙上：**(3,2,5) 朝南**（输出面朝南、接收面朝北）、**(5,2,3) 朝东**（输出朝东、接收朝西）。
     *
     * <p>按用户 2026-10-04 的剧本：接收面外侧丢 TNT 演爆炸 → 输出端那半根传动轴转起来 → 转视角看后方 →
     * 标记第一台并提示"可以根据爆炸转化为应力"；再连丢 3 个 TNT 把**另一侧**那台一路打到"损毁"
     * （阶段 1 → 2 → 3，方块仍在）→ 爆炸把附近几格硬化块烧红 → 转视角观察 → 转回来在被打坏的那台上
     * 出第二段提示（过热损伤与散热手段）。
     *
     * <p>⚠️ **Ponder 里不真炸**：真爆炸会炸掉场景方块、且结果不可复现。这里用"生成一个引信极长的 TNT
     * 实体 + 粒子演爆炸 + 剧本决定后果"的方式。
     */
    public static void blastTurbineEngine(SceneBuilder scene, SceneBuildingUtil util) {
        BlockPos engineA = util.grid().at(3, 2, 5);
        BlockPos tntA = util.grid().at(3, 2, 4);
        BlockPos engineB = util.grid().at(5, 2, 3);
        BlockPos tntB = util.grid().at(4, 2, 3);
        Selection engineASel = util.select().position(engineA);
        Selection engineBSel = util.select().position(engineB);

        scene.title(BTE, "冲爆引擎");
        scene.configureBasePlate(0, 0, 8);
        scene.idle(10);
        scene.world().showSection(util.select().everywhere(), Direction.DOWN);
        scene.idle(15);

        // ① 在接收面外侧丢一个 TNT，演一次爆炸
        scene.overlay().showOutline(PonderPalette.INPUT, tntA, util.select().position(tntA), 40);
        hardener$spawnTnt(scene, util, tntA);
        scene.idle(15);
        hardener$explode(scene, util, tntA);
        scene.idle(10);

        // ② 引擎出力：输出端那半根传动轴转起来
        hardener$setEngineSpeed(scene, engineASel, 32.0F);
        scene.idle(20);

        // ③ 转视角露后方（输出端），标记这台引擎 + 第一段提示
        scene.rotateCameraY(-90.0F);
        scene.idle(30);
        scene.overlay().showOutlineWithText(engineASel, 140)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(BTE, "text_1"));
        scene.idle(150);
        scene.rotateCameraY(90.0F);
        scene.idle(20);

        // ④ 再连丢 3 个 TNT，把另一侧那台打到"损毁"（阶段 1 → 2 → 3）
        for (int stage = 1; stage <= 3; stage++) {
            scene.overlay().showOutline(PonderPalette.INPUT, tntB, util.select().position(tntB), 30);
            hardener$spawnTnt(scene, util, tntB);
            scene.idle(10);
            hardener$explode(scene, util, tntB);
            hardener$setEngineStage(scene, engineB, stage);
            scene.idle(20);
        }

        // ⑤ 爆炸波及的硬化块变烧红（固定挑 4 格，保证每次播放一致）
        hardener$scorch(scene, util.grid().at(3, 1, 4));
        hardener$scorch(scene, util.grid().at(4, 1, 3));
        hardener$scorch(scene, util.grid().at(2, 2, 5));
        hardener$scorch(scene, util.grid().at(5, 2, 4));

        // ⑥ 转视角让玩家看清这些烧红块
        scene.rotateCameraY(-90.0F);
        scene.idle(50);
        scene.rotateCameraY(90.0F);
        scene.idle(20);

        // ⑦ 回到被打坏的那台引擎，出第二段提示
        scene.overlay().showOutlineWithText(engineBSel, 170)
                .colored(PonderPalette.WHITE)
                .placeNearTarget()
                .attachKeyFrame()
                .text(lang(BTE, "text_2"));
        scene.idle(190);
        scene.markAsFinished();
    }

    // ------------------------------------------------------------------ 小工具

    /** 文案键：{@code createhardener.ponder.<场景id>.<后缀>}（Ponder 是拿字符串当翻译键显示的）。 */
    private static String lang(String sceneId, String suffix) {
        return CreateHardener.MODID + ".ponder." + sceneId + "." + suffix;
    }

    /** 改感磁块继承到的强度（0-15）：直接写方块状态 {@code POWER}，四个侧面 16 档贴图跟着变。 */
    private static void hardener$setMagnetoPower(SceneBuilder scene, BlockPos pos, int power) {
        scene.world().modifyBlock(pos, state -> state.hasProperty(ResonantMagnetosensitiveBlock.POWER)
                ? state.setValue(ResonantMagnetosensitiveBlock.POWER, power)
                : state, false);
    }

    /**
     * 点亮比较器。
     *
     * <p>⚠️ **原版比较器没有 {@code power} 属性**（它的输出强度是运行时算出来的，方块状态里只有
     * {@code facing / mode / powered}）—— 2026-10-04 在这里写成 {@code setValue(POWER, 15)} 直接
     * 把客户端崩了（`IllegalArgumentException: Cannot set property ... as it does not exist in
     * Block{minecraft:comparator}`）。这里改成只点 {@code POWERED}，并且**留 {@code hasProperty} 兜底**：
     * Ponder 场景里抛异常 = 整局崩溃，改方块状态前一律先确认属性存在。
     */
    private static void hardener$setComparatorPowered(SceneBuilder scene, BlockPos pos, boolean powered) {
        scene.world().modifyBlock(pos, state -> state.hasProperty(BlockStateProperties.POWERED)
                ? state.setValue(BlockStateProperties.POWERED, powered)
                : state, false);
    }

    /** 给动能方块（齿轮 / 泵）设置转速：Create 的扩展，Ponder 原 API 没有。 */
    private static void hardener$setKineticSpeed(SceneBuilder scene, Selection selection, float speed) {
        try {
            new CreateSceneBuilder(scene).world().setKineticSpeed(selection, speed);
        } catch (Throwable t) {
            scene.world().modifyBlockEntityNBT(selection,
                    com.simibubi.create.content.kinetics.base.KineticBlockEntity.class,
                    nbt -> nbt.putFloat("Speed", speed), false);
        }
    }

    /** 让管路做一次流体流动提示（泵那一路）。 */
    private static void hardener$propagatePipeChange(SceneBuilder scene, BlockPos pump) {
        try {
            new CreateSceneBuilder(scene).world().propagatePipeChange(pump);
        } catch (Throwable ignored) {
            // 没有管路时忽略即可
        }
    }

    /** 往储罐里灌水（给足量就是"灌满"；走方块自己的流体能力，多方块罐问控制器拿 handler）。 */
    private static void hardener$fillTank(SceneBuilder scene, BlockPos pos, int amount) {
        scene.world().modifyBlockEntity(pos, HardenedFluidTankBlockEntity.class, be -> {
            IFluidHandler handler = hardener$tankHandler(be);
            if (handler != null) {
                handler.fill(new FluidStack(Fluids.WATER, amount), IFluidHandler.FluidAction.EXECUTE);
            }
        });
    }

    /**
     * 把水从 {@code from} 罐抽 {@code amount} 灌进 {@code to} 罐 —— **两个方块实体在同一个 lambda 里改**：
     * 先 drain 出真实抽到的量，再把这同一份 {@link FluidStack} 灌进去，所以"左边减多少、右边就加多少"
     * 精确守恒（抽不到就什么都不做，不会凭空造水）。
     */
    private static void hardener$transferTank(SceneBuilder scene, BlockPos from, BlockPos to, int amount) {
        scene.world().modifyBlockEntity(to, HardenedFluidTankBlockEntity.class, toBe -> {
            IFluidHandler toHandler = hardener$tankHandler(toBe);
            Level level = toBe.getLevel();
            if (toHandler == null || level == null) {
                return;
            }
            if (!(level.getBlockEntity(from) instanceof HardenedFluidTankBlockEntity fromBe)) {
                return;
            }
            IFluidHandler fromHandler = hardener$tankHandler(fromBe);
            if (fromHandler == null) {
                return;
            }
            FluidStack drained = fromHandler.drain(new FluidStack(Fluids.WATER, amount),
                    IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) {
                toHandler.fill(drained, IFluidHandler.FluidAction.EXECUTE);
            }
        });
    }

    /** 多方块储罐：真正的库存挂在控制器上，"零件"格上取不到 —— 统一问控制器要。 */
    private static IFluidHandler hardener$tankHandler(HardenedFluidTankBlockEntity be) {
        FluidTankBlockEntity controller = be.getControllerBE();
        if (controller instanceof HardenedFluidTankBlockEntity hardened) {
            return hardened.fluidHandler();
        }
        return be.fluidHandler();
    }

    // ------------------------------------------------------------------ 冲爆引擎场景用的小工具

    /** 生成一个**引信极长、永远不会自己炸**的 TNT 实体（Ponder 里真炸会破坏场景、且不可复现）。 */
    private static void hardener$spawnTnt(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        scene.world().createEntity(level -> {
            PrimedTnt tnt = new PrimedTnt(level, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, null);
            tnt.setFuse(100000);
            return tnt;
        });
    }

    /** 用粒子"演"一次爆炸，然后把这个 TNT 实体清掉（不然它会一直立在那儿）。 */
    private static void hardener$explode(SceneBuilder scene, SceneBuildingUtil util, BlockPos pos) {
        Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        scene.effects().emitParticles(center,
                scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION_EMITTER, Vec3.ZERO), 1.0F, 1);
        scene.effects().emitParticles(center,
                scene.effects().simpleParticleEmitter(ParticleTypes.EXPLOSION, Vec3.ZERO), 3.0F, 1);
        scene.world().modifyEntitiesInside(PrimedTnt.class, util.select().position(pos), PrimedTnt::discard);
    }

    /** 引擎出力：[转速] 与 [生成转速] 都写一份 —— Ponder 里没有真实动力网，这只是让渲染端把那半根轴转起来。 */
    private static void hardener$setEngineSpeed(SceneBuilder scene, Selection engine, float speed) {
        hardener$setKineticSpeed(scene, engine, speed);
        scene.world().modifyBlockEntityNBT(engine, BlastTurbineEngineBlockEntity.class, nbt -> {
            nbt.putFloat("Speed", speed);
            nbt.putFloat("GeneratedSpeed", speed);
        }, false);
    }

    /** 改引擎的损伤阶段（方块状态 {@code STAGE}，0 完好 → 3 损毁）。 */
    private static void hardener$setEngineStage(SceneBuilder scene, BlockPos engine, int stage) {
        scene.world().modifyBlock(engine, state -> state.hasProperty(BlastTurbineEngineBlock.STAGE)
                ? state.setValue(BlastTurbineEngineBlock.STAGE, Math.min(stage, 3))
                : state, false);
    }

    /** 把一格硬化块换成**烧红**变体（爆炸波及的样子）。 */
    private static void hardener$scorch(SceneBuilder scene, BlockPos pos) {
        scene.world().setBlock(pos, CreateHardener.HOT_HARDENED_BLOCK.get().defaultBlockState(), false);
    }

    /** 第 n 档的**整砖**硬化块（0 普通 / 1 微裂 / 2 风化 / 3 脆化）。 */
    private static Block hardener$stageBlock(int stage) {
        return HardeningStages.of(HardeningStages.Family.FULL, Math.max(0, Math.min(3, stage))).get();
    }

    /** 这个方块是"第几档整砖硬化块"（不是硬化块就返回 -1）。 */
    private static int hardener$stageOf(Block block) {
        for (int stage = 0; stage <= 3; stage++) {
            if (block == hardener$stageBlock(stage)) {
                return stage;
            }
        }
        return -1;
    }

    /** 把整砖硬化块往上推一档（风化推进用）；不是硬化块、或已到最后一档就原样返回。 */
    private static BlockState hardener$weatherUp(BlockState state) {
        int stage = hardener$stageOf(state.getBlock());
        return stage >= 0 && stage < 3 ? hardener$stageBlock(stage + 1).defaultBlockState() : state;
    }

    /** 直接把一格换成指定方块。 */
    private static void hardener$setBlockType(SceneBuilder scene, BlockPos pos, Block block) {
        scene.world().setBlock(pos, block.defaultBlockState(), false);
    }
}
