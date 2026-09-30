package dev.createhardener;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import dev.createhardener.sealant.SealShellHelper;

/**
 * 塑封外壳方块 `createhardener:sealed_shell`
 * —— **自己实现的完整方块伪装方块**（原理同 Create 的 copycat，但不依赖它）。
 *
 * <p>要点：
 * <ul>
 *   <li>**完整方块**（硬度 25 / 爆炸抗性 600，强度是外壳自己的，不继承被套方块）；</li>
 *   <li>带方块实体 {@link SealShellBlockEntity}，记录"被套住的方块状态"，客户端据此**渲染成那个方块**；</li>
 *   <li>客户端 {@link dev.createhardener.client.SealShellRenderer} 额外画一层**涂层贴图**，只在手持塑封剂时显示；</li>
 *   <li>实现 Sable 的 {@link dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener}
 *       —— **必须在方块上实现**（Sable 的判据是 `state.getBlock() instanceof ...`），用来支持"方案 A1"。</li>
 * </ul>
 */
public final class SealShellBlocks {

    /** 塑封外壳方块（`sealed_shell`）。 */
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CreateHardener.MODID);

    /** 清扫脏壳的间隔（tick）：不是每 tick 都要查，20 tick 一次足够快也足够省。 */
    private static final int SCRUB_INTERVAL = 20;

    /**
     * 外壳硬度（2026-09-23 用户要求"挖掘难度缩减一半"）。
     * 原值是黑曜石的 50，现在 **25** —— 套壳后一格大约挖 7.5 秒，手误涂错也不至于让人崩溃。
     */
    public static final float HARDNESS = 25.0F;
    /**
     * 外壳爆炸抗性。
     *
     * <p>沿革：1200（=黑曜石，2026-09-23 定为 600，2026-09-24 用户实测后要求再降）→ 现取
     * **15 = 脆化硬化块的档位**（四档抗性 1200/600/90/15 里最低那档）。
     *
     * <p>意味着一件事变了：TNT 现在**一次就能把外壳炸碎**。所以"第一下只震碎壳、第二下才伤到方块"
     * 这条从"防住一整次爆炸"变成"**外壳只吸收掉这一次爆炸**" —— 因为
     * {@link dev.createhardener.sealant.SealZoneEvents#onExplosionDetonate} 会把有外壳的格子
     * 从爆炸列表里移出（方块本体保住）并震碎外壳，那一瞬方块本身就已经安全了。
     */
    public static final float BLAST_RESISTANCE = 15.0F;

    public static final DeferredBlock<SealShellBlock> SEALED_SHELL =
            BLOCKS.register("sealed_shell", () -> new SealShellBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(HARDNESS, BLAST_RESISTANCE)
                            .sound(SoundType.DEEPSLATE)
                            .requiresCorrectToolForDrops()
                            .pushReaction(PushReaction.BLOCK)
                            .noOcclusion()));

    private SealShellBlocks() {
    }

    /** 外壳方块：完整立方体碰撞，外观由方块实体里的"材质"决定。 */
    public static class SealShellBlock
            extends net.minecraft.world.level.block.Block
            implements EntityBlock, dev.ryanhcode.sable.api.block.BlockSubLevelAssemblyListener {

        private static final VoxelShape FULL_CUBE = box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

        public SealShellBlock(Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new SealShellBlockEntity(pos, state);
        }

        /**
         * 方块实体 ticker：**只做"脏壳自毁"**，每 {@value #SCRUB_INTERVAL} tick 查一次。
         *
         * <p>比随机刻（平均约 47 秒才轮到一次）快得多，且比"每 tick 查"省得多
         * —— 一整片被塑封的地板有几千个外壳时，这个差别很实在。
         */
        @Override
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
                Level level, BlockState state, BlockEntityType<T> type) {
            if (level.isClientSide) {
                return null;
            }
            return (tickLevel, pos, tickState, be) -> {
                // ⚠️ 用「游戏刻 + 该格坐标」取模错峰，**不要用一个计数器字段**（2026-09-26 修）：
                //    方块是注册单例、ticker 又捕获了 this，原先那个 `private int scrubTimer`
                //    其实是**所有外壳共用**的（注释却写"各外壳自己数 tick"）→ 恒定只有约 1/20
                //    的固定子集会被扫到，其余约 95% 的壳**永不**"脏壳自毁"。
                //    现在每个外壳每 SCRUB_INTERVAL 刻都会轮到一次，且彼此错峰、不会同刻扎堆。
                if (Math.floorMod(tickLevel.getGameTime() + pos.asLong(), SCRUB_INTERVAL) != 0) {
                    return;
                }
                if (tickLevel instanceof ServerLevel serverLevel) {
                    SealShellHelper.scrubStale(serverLevel, pos);
                }
            };
        }

        // ------------------------------------------------------------ 航空学（Sable）：方案 A1

        /**
         * **方案 A1**：航空学把方块搬进子世界**之前**，我们只做一件事 ——
         * **把这一格的塑封记录解绑**（删记录、**不改世界**）。
         *
         * <p>为什么必需：搬运走的是 {@code setBlock}，**不触发 NeoForge 的破坏钩子**，所以
         * {@link dev.createhardener.sealant.SealShellData} 里会留下一条永远清不掉的"幽灵记录"，
         * 表现就是"框选不消失"、以及之后随便一次爆炸把记录里的原方块
         * **塞回空气位置**（"用 TNT 把方块找回来 / 出现两个状态"）。
         *
         * <p>为什么只删记录、不还原方块：航空学搬的就是**当前位置这个方块**，
         * 记录先没了，它搬走的就是**干干净净的原方块**，不需要我们再去 {@code setBlock} 插一手
         * （那样会导致每次物理化都强制退壳 + 幽灵记录照样产生）。
         *
         * <p>搬走之后留在子世界的那个外壳，会因为"这一格没有记录"被上面的 ticker
         * 判为脏壳而自行消失（**方案 B**）。
         *
         * <p>⚠️ 这个接口**必须实现在方块上**：Sable 的判据是
         * {@code state.getBlock() instanceof BlockSubLevelAssemblyListener}。
         * （曾错误地实现在方块实体上 → 本回调一次都没被调用过。）
         */
        @Override
        public void beforeMove(ServerLevel source, ServerLevel destination,
                               BlockState state, BlockPos sourcePos, BlockPos destinationPos) {
            SealShellHelper.unbindRecord(source, sourcePos);
        }

        /**
         * **方案 B 的可靠落点**：航空学把方块放到目标位置**之后**，直接把那一格的塑封外壳删掉。
         *
         * <p>为什么必须有这一步（2026-09-24 用户实测发现）：只靠 {@link #beforeMove} 解绑记录
         * 还不够稳 —— 移动过的外壳如果在目标位置仍然是"有记录的合法外壳"，ticker 就不会清它，
         * 于是会出现用户看到的怪象：**同一格既有草方块 ID 又有塑封外壳 ID**，
         * 两个物体共面 → 换个角度就变个样子。
         *
         * <p>这里的动作与记录无关、与维度是否隔离无关、与 `beforeMove` 有没有触发无关：
         * **人已经被搬过来了，就把这层壳撕掉**，让底下的真方块露出来。
         */
        @Override
        public void afterMove(ServerLevel source, ServerLevel destination,
                              BlockState state, BlockPos sourcePos, BlockPos destinationPos) {
            SealShellHelper.removeShellAt(destination, destinationPos);
        }

        // ------------------------------------------------------------ 形状

        /** 碰撞盒固定为完整立方体：外壳就是一层"壳"，不随里面方块的形状变化。 */
        @Override
        protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FULL_CUBE;
        }

        @Override
        protected VoxelShape getCollisionShape(
                BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
            return FULL_CUBE;
        }
    }
}
