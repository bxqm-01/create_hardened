package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * 谐振感磁块（resonant magnetosensitive block）—— 类侦测器摆放，但机制**不是**侦测器。
 *
 * <p><b>用户 2026-09-25 口述的规格</b>：
 * <ul>
 *   <li><b>侦测端</b> = {@link #FACING} 指向的那一面（前）；<b>禁止端</b> = 其背面（后）；</li>
 *   <li>显示信号的那些列在 <b>上 / 下 / 左 / 右四个面</b>，按 {@link #POWER} 0~15 换 16 张侧面贴图；</li>
 *   <li>直接检测**侦测端前方那一格**的红石信号，**继承到自己身上**（写进 {@link #POWER}）；</li>
 *   <li>**不向外充能** —— 所以本类刻意**不覆写** {@code getSignal} / {@code getDirectSignal}；</li>
 *   <li>玩家用**比较器**读取时输出**同强度**（{@link #getAnalogOutputSignal} = 继承到的 power）。</li>
 * </ul>
 *
 * <p><b>与原版侦测器的区别</b>（用户明确）：侦测器是"脉冲 + 固定 15"，
 * 这个是"**把前方的强度原样抄过来**"，没有脉冲、没有延迟逻辑。
 *
 * <p><b>后续尚未实现</b>：按继承到的强度改变自身的摩擦 / 质量 / 硬度 / 爆炸抗性
 * （质量与摩擦走 Sable 数据包 `data/sable/physics_block_properties/`，但那是**按方块**生效的，
 * 想按 `power` 分档要用 `overrides` 或每档一个方块/标签，等用户拍板方案）。
 */
public class ResonantMagnetosensitiveBlock extends Block {

    /** 侦测端朝向：正面 = 侦测端，背面 = 禁止端。 */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** 继承到的红石强度 0~15，直接驱动上/下/左/右四个面的贴图。 */
    public static final IntegerProperty POWER = BlockStateProperties.POWER;

    public ResonantMagnetosensitiveBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWER);
    }

    // ------------------------------------------------------------ 摆放：类侦测器

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getNearestLookingDirection().getOpposite())
                .setValue(POWER, 0);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            refreshPower(level, pos, state);
        }
    }

    // ------------------------------------------------------------ 核心：继承前方强度

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos,
                                   Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (!level.isClientSide) {
            refreshPower(level, pos, state);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos,
                           BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide && !state.is(oldState.getBlock())) {
            refreshPower(level, pos, state);
        }
    }

    /**
     * 读侦测端正前方那格的信号，写进自己的 {@link #POWER}。取值规则：
     *
     * <ol>
     *   <li><b>普通红石信号</b>：{@code level.getSignal(frontPos, detect)} —— 覆盖
     *       红石火把 / 拉杆 / 红石线 / 红石块这类"真的在向外给信号"的方块；</li>
     *   <li><b>同类方块的继承值</b>：若前方也是本方块，**直接读它的 {@link #POWER} 属性** ——
     *       这是必需的，因为本方块**刻意不向外充能**，所以 {@code getSignal} 从同类方块那里
     *       **永远只能读到 0**，光靠第 ① 条无法实现"一个传给下一个"。</li>
     * </ol>
     * 两者取较大值。写值后由 {@link #settleChain} 把整条同类链一起刷新（见其注释）。
     *
     * <p>强度没变就不写方块 —— 避免每次邻居变动都触发方块更新（性能 + 不给客户端刷无谓的包）。
     */
    private void refreshPower(Level level, BlockPos pos, BlockState state) {
        Direction detect = state.getValue(FACING);
        BlockPos front = pos.relative(detect);

        // ① 普通红石信号。原版约定的 dir 含义 = "**从查询方指向被查询方块**的方向"：
        //    依据 SignalGetter.getBestNeighborSignal —— 它对每个 d 调 getSignal(pos.relative(d), d)；
        //    用 RedstoneTorchBlock.getSignal 的 `side != UP` 反推也一致
        //    （地面火把要能点亮上方那格、且不能点亮自己所在的下方格）。
        //    故：front 就在 detect 方向上 → 传 detect。
        // ⚠️ 2026-09-26 修正：原先传 detect.getOpposite() 是错的 —— 那会去读"背后那格"，
        //    结果红石线端头/中继器/比较器指向本方块时一律读到 0，只有拉杆、红石块这类
        //    方向无关源才恰好正确。
        int power = level.getSignal(front, detect);

        // ② 前方若是同类方块，直接抄它的继承值（它不向外充能，getSignal 读不到）
        BlockState frontState = level.getBlockState(front);
        if (frontState.getBlock() instanceof ResonantMagnetosensitiveBlock) {
            power = Math.max(power, frontState.getValue(POWER));
        }

        if (power != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_ALL);
            settleChain(level, pos, detect, power);
        }
    }

    /**
     * 链式传播上限：从源往两侧**各**最多走这么多格。
     *
     * <p>⚠️ 这个数不能大（2026-09-26 从 256 降到 32）：每次 {@code setBlock(..., UPDATE_ALL)}
     * 会产生约 6 次"链式邻居更新"，而原版 {@code MinecraftServer.getMaxChainedNeighborUpdates()}
     * 默认上限是 **512**（超过就**静默不再入队**，只在日志里打一条
     * "Too many chained neighbor updates. Skipping the rest."）。
     * 256 格 × 2 方向 × 6 ≈ 3000 次的量级早就越限了 —— 也就是说超出约 85 格之后，
     * 那些方块的邻居通知**本来就被丢掉了**（比较器/侦测器收不到更新，且伴随日志噪音）。
     * 现在 32 × 2 × 6 ≈ 384 < 512，落在安全区内，行为可预期。
     */
    private static final int MAX_CHAIN = 32;

    /**
     * 把强度 {@code power} 沿"本方块串成的链"双向刷新。
     *
     * <p><b>为什么必须专门做这一步</b>：本方块**不向外充能**，每一格只能靠读前方那格的
     * {@link #POWER} 属性拿值。若只依赖逐级的 {@code neighborChanged}，源从高降到低时，
     * 下游可能先读到旧值、再被旧值顶住，导致**降不下来**。一次性把整条链刷成同一个值最稳。
     *
     * <p>方向规则：
     * <ul>
     *   <li><b>向前</b>：沿源自己的 {@code forward}（侦测方向）走 —— 下一格如果也是本方块，
     *       它的侦测方向必然指向源（面对面），所以继续沿同一个 {@code forward} 走；</li>
     *   <li><b>向后</b>：沿 {@code forward.getOpposite()} 走 —— 上一格如果也是本方块，
     *       它的侦测方向指向源，也就是沿 {@code forward} 的**反方向**。</li>
     * </ul>
     * 每步只在"是同类方块且强度与目标不同"时才写，天然终止；再叠加 {@link #MAX_CHAIN} 兜底。
     */
    private static void settleChain(Level level, BlockPos sourcePos, Direction forward, int power) {
        // 向前：下一格的**侦测端**必须朝着源（面对面）才认 —— 只认朝向，避免顺手覆盖
        // "只是碰巧排在同一条直线上、但朝向别处"的同类方块（那会来回写、贴图乱闪）。
        BlockPos cursor = sourcePos;
        for (int i = 0; i < MAX_CHAIN; i++) {
            cursor = cursor.relative(forward);
            BlockState s = level.getBlockState(cursor);
            if (!isChainNeighbour(s, forward.getOpposite()) || s.getValue(POWER) == power) {
                break;
            }
            level.setBlock(cursor, s.setValue(POWER, power), Block.UPDATE_ALL);
        }

        // 向后：上一格的侦测端朝着源 = 朝着 forward 方向
        cursor = sourcePos;
        for (int i = 0; i < MAX_CHAIN; i++) {
            cursor = cursor.relative(forward.getOpposite());
            BlockState s = level.getBlockState(cursor);
            if (!isChainNeighbour(s, forward) || s.getValue(POWER) == power) {
                break;
            }
            level.setBlock(cursor, s.setValue(POWER, power), Block.UPDATE_ALL);
        }
    }

    /** 该状态是不是"侦测端朝向 {@code expected}"的同类方块。 */
    private static boolean isChainNeighbour(BlockState state, Direction expected) {
        return state.getBlock() instanceof ResonantMagnetosensitiveBlock && state.getValue(FACING) == expected;
    }

    // ------------------------------------------------------------ 不向外充能，但比较器可读

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    /**
     * 比较器读取时输出"继承到的同强度信号"。
     *
     * <p>这是本方块**唯一**对外输出的通道 —— 因为它不覆写 {@code getSignal}/{@code getDirectSignal}，
     * 所以红石线/中继器从它身上取不到强度，符合"不向外充能"。
     */
    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(POWER);
    }

    // ------------------------------------------------------------ 按信号强度改力学/强度数值

    /**
     * 按 {@link #POWER} 0~15 分档的**硬度**（挖掘难度），等差、每档都不同。
     *
     * <p>⚠️ 1.21.1 **没有** `getDestroySpeed(state, level, pos)`（那是 1.21.2+ 才加入的），
     * `BlockState.getDestroySpeed` 也不可覆写，所以这里只能配合 {@link #getDestroyProgress} ——
     * 覆写挖掘进度计算、把其中的"硬度"换成这张表查出来的值（公式照抄原版，见该方法）。
     */
    private static final float[] HARDNESS = {
            2.0F, 4.0F, 6.0F, 8.0F, 10.0F, 12.0F, 14.0F, 16.0F,
            18.0F, 20.0F, 22.0F, 24.0F, 26.0F, 28.0F, 30.0F, 32.0F
    };

    /** 按 {@link #POWER} 0~15 分档的**爆炸抗性**，与硬度同步递增。 */
    private static final float[] BLAST = {
            10.0F, 18.0F, 25.0F, 33.0F, 40.0F, 48.0F, 55.0F, 63.0F,
            70.0F, 78.0F, 85.0F, 93.0F, 100.0F, 108.0F, 115.0F, 123.0F
    };

    /** 取该档硬度（越界自动收敛）。**质量与摩擦不在这里** —— 那两项走 Sable 数据包的 `overrides`。 */
    public static float hardnessOf(int power) {
        return HARDNESS[Math.max(0, Math.min(15, power))];
    }

    /** 取该档爆炸抗性（越界自动收敛）。 */
    public static float blastOf(int power) {
        return BLAST[Math.max(0, Math.min(15, power))];
    }

    /**
     * 按信号强度改变**挖掘速度**。
     *
     * <p>覆写原因见 {@link #HARDNESS} 的注释。**公式逐行照抄原版**
     * {@code BlockBehaviour.getDestroyProgress}（字节码核对过），只把第一步的
     * {@code state.getDestroySpeed(level, pos)} 换成"按 POWER 查表"：
     * <pre>
     *   float speed = 查表(POWER);
     *   if (speed == -1.0F) return 0.0F;                       // -1 = 不可破坏
     *   int divisor = player.hasCorrectToolForDrops(state) ? 30 : 100;
     *   return player.getDestroySpeed(state) / speed / divisor;
     * </pre>
     */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float speed = hardnessOf(state.getValue(POWER));
        if (speed == -1.0F) {
            return 0.0F;
        }
        int divisor = player.hasCorrectToolForDrops(state) ? 30 : 100;
        return player.getDestroySpeed(state) / speed / divisor;
    }

    /** 按信号强度改变**爆炸抗性**（NeoForge 的带 state 版本，`IBlockExtension` 提供）。 */
    @Override
    public float getExplosionResistance(BlockState state, BlockGetter level, BlockPos pos, Explosion explosion) {
        return blastOf(state.getValue(POWER));
    }

    // ------------------------------------------------------------ 旋转 / 镜像（与侦测器一致）

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
