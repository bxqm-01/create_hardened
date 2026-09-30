package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import com.simibubi.create.content.fluids.tank.FluidTankBlock;
import com.simibubi.create.content.fluids.tank.FluidTankBlock.Shape;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

/**
 * 硬化流体储罐的方块实体 —— **直接继承原版** {@link FluidTankBlockEntity}。
 *
 * <p>所以"多方块拼装（宽×高）、流体库存、锅炉数据、护目镜信息、比较器输出"这些**原版功能全都自动带上**，
 * 我们一行逻辑都不用写 —— 这正是用户要的"先实现原本已有的功能"。
 *
 * <p>之所以还需要这个类，是因为 NeoForge 的 {@code BlockEntityType.Builder} 要求
 * {@code (pos, state) -> be} 的**两参**构造器，而原版构造器是三参（父类要把 {@code type} 交给
 * {@code SmartBlockEntity}）。这里补一个两参版本，类型从 {@link CreateHardener#HARDENED_FLUID_TANK_BE} 取
 * —— 方块实体只会在注册**完成之后**被构造，所以那时 {@code .get()} 一定已绑定。
 *
 * <h2>7×7 扩展（2026-09-26，用户拍板"B 原版 3×3 味儿"）—— 只覆写两个方法，渲染层一行没动</h2>
 * <ol>
 *   <li>{@link #getMaxWidth()} 由 {@code 3} 改成 {@code 7}。父类这个方法体就是 {@code return 3}，
 *       而 {@code ConnectivityHandler} 判上限走的是接口 {@code IMultiBlockEntityContainer.getMaxWidth()}
 *       （{@code invokeinterface}），{@code getMaxLength(axis,width)} 内部也是 {@code invokevirtual getMaxWidth()}
 *       → 覆写一处即全局生效。</li>
 *   <li>{@link #setWindows(boolean)} 换掉父类那套按 {@code width==1/2/3} 写死的窗口图案
 *       （原版宽度 ≥ 4 时全给 PLAIN，扳机调窗彻底失效）。</li>
 * </ol>
 * <p><b>顺带查证（免得白改）</b>：私有的 {@code MAX_SIZE} 常量在父类里**零引用**；
 * {@code applyFluidTankSize(int)} 只是 {@code 容量 = 格数 × getCapacityMultiplier()}、不夹上限；
 * 整个罐类里硬编码的 {@code 3} 只剩 {@code getMaxSize()}/{@code getMaxWidth()} 两个 return 和 setWindows 里那句
 * {@code width==3} → 没有别的隐藏上限。底面永远是正方形（BE 只有一个 {@code width} 字段，x/z 两个方向都用它）。
 *
 * <h2>为什么"不和原版罐子连通"不需要写代码（用户要求，2026-09-26 查证）</h2>
 * {@code ConnectivityHandler.formMulti(BE)} 把 {@code be.getType()} 交给内部搜索，而搜索里查邻居走的是
 * {@code partAt(type, …)}，那里是 {@code be.getType() == type} 的**引用比较**（{@code if_acmpne}）
 * → 连通只认方块实体类型。我们注册的是自己的 {@code HARDENED_FLUID_TANK_BE}，与原版类型不同，
 * **天然拼不进同一个多方块**（同理，模型层的接缝剔除走 {@code isConnected}，判的是"是否同一个控制器"，
 * 也不会把原版罐误判成接缝）。
 */
public class HardenedFluidTankBlockEntity extends FluidTankBlockEntity {

    /** 罐子底面最大边长（原版 {@code getMaxWidth()} 是 3）。底面永远正方形，所以 7 就是 7×7。 */
    public static final int MAX_WIDTH = 7;

    public HardenedFluidTankBlockEntity(BlockPos pos, BlockState state) {
        super(CreateHardener.HARDENED_FLUID_TANK_BE.get(), pos, state);
    }

    /**
     * 暴露父类的流体能力（父类字段是 {@code protected}）。走一个公开方法而不是让外部 lambda
     * 直接读字段，可以完全绕开 Java 的 protected 跨包访问规则。
     */
    public IFluidHandler fluidHandler() {
        return this.fluidCapability;
    }

    @Override
    public int getMaxWidth() {
        return MAX_WIDTH;
    }

    /**
     * **空实现：我们的罐只做储罐，永不进入锅炉状态**（用户 2026-09-27 要求）。
     *
     * <p>原版这个方法是锅炉状态机的唯一入口 —— {@code boiler.evaluate(this)} 在整份字节码里**只有这里调用**，
     * {@code boiler.isActive} 也只由它决定。而它被调用的两条路都是虚分派，所以空实现就能整体关掉：
     * <pre>
     *   FluidTankBlock.updateBoilerState(静态)   →  controller.updateBoilerState()   ← invokevirtual
     *   FluidTankBlockEntity.notifyMultiUpdated() →  this.updateBoilerState()         ← invokevirtual
     * </pre>
     * <p>为什么必须关掉：蒸汽引擎那侧找罐子用的是**原版的方块实体类型**，而我们的罐用了自己的类型
     * （和"不和原版罐子连通"是同一条机制），引擎永远认不到它 → 锅炉**只会有外观、永远出不了蒸汽**。
     * 与其留一个半死不活的状态（原版锅炉激活时还会顺带调 {@code setWindows(false)} 把观察窗全关上），
     * 不如按用户的意思彻底不做锅炉。
     */
    @Override
    public void updateBoilerState() {
        // 有意留空
    }

    /**
     * 与原版 {@code FluidTankBlockEntity.setWindows(boolean)} 逐指令对应的一份克隆，**只换掉"给哪个 shape"那一段**：
     * <pre>
     *   原版：width==1 → 全 WINDOW；width==2 → 四角 NW/SW/NE/SE；width==3 → |x|-|z|==±1 的边中块给 WINDOW；其余 PLAIN
     *   现在：1×1 / 2×2 与原版**完全一致**（这两档不能动：1×1 和 2×2 的每一格都算"角"，若按"只有边中块开窗"
     *         处理，开窗后一格窗都没有、看不见液体）；
     *         width ≥ 3 统一成"**恰好只有一个暴露面**的格开窗"——这条在 width==3 时与原版逐格等价
     *         （|x|-|z|==±1 恰好就是那 4 个边中块），继续往上就是用户要的"原版 3×3 味儿"：
     *         7×7 时每面中段那 5 格开窗，四角与内部保持金属。
     * </pre>
     * 其余全部照抄原版：循环口径（y 最外、x 中、z 内，{@code origin.offset(x,y,z)}）、
     * {@code setBlock} 的 flag {@code 22}、{@code checkBlock} 补光、{@code isTank} 跳过非罐格。
     * 只额外加了两处**不改行为**的加固：{@code level} 为 null 时早退、shape 没变就跳过 setBlock
     * （后者省掉结构变动时的大量重复方块更新）。
     */
    @Override
    public void setWindows(boolean window) {
        this.window = window;
        Level lvl = getLevel();
        if (lvl == null) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        BlockPos origin = getBlockPos();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < w; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    BlockState state = lvl.getBlockState(pos);
                    if (!FluidTankBlock.isTank(state)) {
                        continue;
                    }
                    Shape target = shapeFor(window, w, x, z);
                    if (state.getValue(FluidTankBlock.SHAPE) == target) {
                        continue;
                    }
                    lvl.setBlock(pos, state.setValue(FluidTankBlock.SHAPE, target), 22);
                    lvl.getChunkSource().getLightEngine().checkBlock(pos);
                }
            }
        }
    }

    /** 窗口图案的唯一决策点（纯坐标，不依赖运行期连通状态 —— 与原版同口径，结构中途也不会算错）。 */
    private static Shape shapeFor(boolean window, int w, int x, int z) {
        if (!window) {
            return Shape.PLAIN;
        }
        if (w == 1) {
            return Shape.WINDOW;
        }
        if (w == 2) {
            if (x == 0) {
                return z == 0 ? Shape.WINDOW_NW : Shape.WINDOW_SW;
            }
            return z == 0 ? Shape.WINDOW_NE : Shape.WINDOW_SE;
        }
        return exposedSides(w, x, z) == 1 ? Shape.WINDOW : Shape.PLAIN;
    }

    /** 该格在 width×width 底面里缺几个水平邻居（只看底面范围内，与原版那套坐标口径一致）。 */
    private static int exposedSides(int w, int x, int z) {
        int exposed = 0;
        if (x == 0) {
            exposed++;
        }
        if (x == w - 1) {
            exposed++;
        }
        if (z == 0) {
            exposed++;
        }
        if (z == w - 1) {
            exposed++;
        }
        return exposed;
    }
}
