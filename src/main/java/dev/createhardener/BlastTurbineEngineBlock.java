package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;

/**
 * **冲爆引擎**（2026-09-27 新增）—— 一种新的**应力来源**：挨爆炸 → 攒应力、提转速，然后自然衰减。
 *
 * <h2>它复用了什么</h2>
 * 继承原版 {@link DirectionalKineticBlock}（和**创造马达**同一套骨架），所以
 * 接轴/传动/超应力/护目镜/扳手/物理化搬运**全部照旧**，我们只加两件事：
 * <b>接收爆炸充能</b>（见 {@link BlastTurbineEngineCharge} 与 {@code mixin.ExplosionChargeMixin}）
 * 与**动态的转速/容量**（见 {@link BlastTurbineEngineBlockEntity}）。
 *
 * <h2>朝向约定（与用户给的模型对齐）</h2>
 * 模型 {@code blast_turbine_engine} 是**东面接收爆炸、西面输出应力**，输出面要露出半根传动轴。
 * 因此本方块把 {@code FACING} 定义成**输出面**：
 * <ul>
 *   <li><b>{@code FACING} 那一侧 = 输出面</b> —— 传动轴在这边（{@link #hasShaftTowards} 只对它有反应），
 *       客户端 {@code BlastTurbineEngineRenderer} 用 {@code partialFacing(SHAFT_HALF, state)} 把半轴画在这里；</li>
 *   <li><b>{@code FACING} 的反面 = 接收面</b> —— 只有从这一侧来的爆炸才算数（{@code dot(接收面法线, 爆炸中心-方块中心) ≥ 0}）。</li>
 * </ul>
 * 模型本身按 {@code FACING} 旋转（见 assets 里 blockstate 的四个变体），所以"接收面朝哪边"由玩家摆放决定。
 *
 * <p>⚠️ 抗性取**黑曜石级**（1200，见 {@link CreateHardener#BLAST_TURBINE_ENGINE} 的注册）：
 * 它不能真的被炸掉。但"不被炸掉"最终是由 mixin 在 {@code shouldBlockExplode} 里返回 false 保证的
 * —— 见 {@code ExplosionChargeMixin} 的注释（抗性太高会让原版判定根本走不到那一步，我们就收不到爆炸强度）。
 */
public class BlastTurbineEngineBlock extends DirectionalKineticBlock implements IBE<BlastTurbineEngineBlockEntity> {

    /**
     * **损伤阶段**（用户 2026-09-27 定）：0 完好 / 1 轻损 / 2 重损 / 3 损毁。
     *
     * <p>⚠️ 自定义属性**必须**在 {@link #createBlockStateDefinition} 里注册 —— 历史上硬化块就栽在
     * "声明了属性、却没加进状态定义"，注册表冻结时直接 NPE；而且必须**先调 super**（父类要加
     * {@code FACING}，漏掉会让六个朝向的方块状态全崩）。
     */
    public static final IntegerProperty STAGE = IntegerProperty.create("stage", 0, BlastTurbineEngineBlockEntity.MAX_STAGE);

    public BlastTurbineEngineBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(STAGE);
    }

    // 放置：**直接用原版 DirectionalKineticBlock 的实现**（挑相邻传动轴 / 朝玩家 / 潜行与否都它管），
    // 我们**不再限制水平朝向** —— 2026-09-27 用户要求"向所有方向都可以摆放"。
    //
    // ⚠️ 历史坑：beta88 时我在这里调了 `getPreferredFacing(context)`，而它在"周围没有可对齐的传动轴"时
    // **返回 null** → `preferred.getAxis()` 空指针 → 放下方块必崩。所以**不要自己先手去碰它的返回值**。
    //
    // ⚠️ 竖直朝向的模型是**离线烘焙**出来的（`blast_turbine_engine_vertical`，把绕 Z 轴 -90° 的旋转
    // 算进了几何体坐标与 uv，输出面朝上，文件里**不含元素旋转字段**）。
    // 为什么不直接写元素旋转：MC 1.21.1 的 BlockElement.Deserializer.getAngle 只接受 -45/-22.5/0/22.5/45，
    // 写 90 会让**整个模型解析失败** —— 表现是方块变成一整块紫黑（日志里才有 JsonParseException）。
    // 烘焙脚本：build/tools/bake_model_rotation.js；离线校验：build/tools/check_models.js（已接进 构建.ps1）。
    // 另外 vanilla 的 x/y 旋转也**动不了模型的长轴**（y 只在水平面转，x 绕 X 轴、而模型长轴就在 X 上），
    // 所以竖直朝向只能这么来 —— 详见 blockstate 里的说明。

    /** 只有输出面（= {@code FACING}）能接传动轴 —— 六个方向都适用。 */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction side) {
        return side == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** 它是"产生应力"的一方，没有自己的应力消耗，所以护目镜里不显示 Impact 那一行。 */
    @Override
    public boolean hideStressImpact() {
        return true;
    }

    @Override
    public Class<BlastTurbineEngineBlockEntity> getBlockEntityClass() {
        return BlastTurbineEngineBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends BlastTurbineEngineBlockEntity> getBlockEntityType() {
        return CreateHardener.BLAST_TURBINE_ENGINE_BE.get();
    }
}
