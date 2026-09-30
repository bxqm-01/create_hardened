package dev.createhardener;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 全部硬化块注册：4 个风化档 × 3 种形状 = **12** 个方块（墙整族已删，见 {@link #Family}）。
 *
 * <p>风化关系**不在静态初始化里登记**：各族方块自身覆写 {@code getNext(BlockState)}，用本类
 * 的阶段表解析下一档（原版 {@code WeatheringCopper.NEXT_BY_BLOCK} 是 ImmutableBiMap，第三方无法 put；
 * 而静态初始化期解析 DeferredBlock 会抛 unbound value）。
 *
 * <p>硬度 / 爆炸抗性（保持原值；塑封外壳的数值另见 {@link SealShellBlocks}）：
 * <pre>
 *   stage 0  硬化块   50 / 1200
 *   stage 1  微裂     38 /  600
 *   stage 2  风化     24 /   90
 *   stage 3  脆化     10 /   15
 * </pre>
 */
public final class HardeningStages {

    private HardeningStages() {
    }

    public static final int MAX_STAGE = 3;

    /** 形状族（WALL 已废弃：墙的 multipart 连接始终不生效，2026-09-22 按用户要求整族删除） */
    public enum Family { FULL, SLAB, STAIR }

    private static final String[] NAMES = {
            "hardened_block", "microcracked_hardened_block",
            "weathered_hardened_block", "embrittled_hardened_block"
    };

    /**
     * 挖掘硬度，**与黑曜石同档的阶梯，保持原值不动**。
     * <p>⚠️ 2026-09-23 更正：用户说的"挖掘难度缩减一半"**只针对黑曜石塑封剂套出来的外壳**
     * （见 {@link SealShellBlocks#HARDNESS}），**不含**硬化块这一族 —— 我曾误把整族砍半，已恢复。
     * <pre>
     *   硬化块 50（等同黑曜石）
     *   微裂   38
     *   风化   24
     *   脆化   10
     * </pre>
     */
    private static final float[] HARDNESS = { 50.0F, 38.0F, 24.0F, 10.0F };

    /**
     * 爆炸抗性。注意 TNT 只能炸掉抗性约 **100 以下**的方块，旧值 1200/912/576/240
     * 对 TNT 而言"四档完全一样"（都炸不掉）。改成让后两档真正可被炸掉：
     * <pre>
     *   硬化块 1200（等同黑曜石，TNT 炸不掉）
     *   微裂    600（TNT 仍炸不掉，但已明显下降）
     *   风化     90（TNT 可炸掉）
     *   脆化     15（TNT 一炸就碎）
     * </pre>
     */
    private static final float[] BLAST = { 1200.0F, 600.0F, 90.0F, 15.0F };

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CreateHardener.MODID);

    public static final List<DeferredBlock<HardeningFullBlock>> FULL = new ArrayList<>();
    public static final List<DeferredBlock<HardeningSlabBlock>> SLABS = new ArrayList<>();
    public static final List<DeferredBlock<HardeningStairBlock>> STAIRS = new ArrayList<>();
    /** 全部方块，创造标签页按此顺序展示（4 档 × 3 形状 = 12 个） */
    public static final List<DeferredBlock<? extends Block>> ALL = new ArrayList<>();

    static {
        for (int stage = 0; stage < NAMES.length; stage++) {
            final int s = stage;

            DeferredBlock<HardeningFullBlock> full = BLOCKS.register(NAMES[s],
                    () -> new HardeningFullBlock(props(s), s));
            FULL.add(full);
            ALL.add(full);

            DeferredBlock<HardeningSlabBlock> slab = BLOCKS.register(NAMES[s] + "_slab",
                    () -> new HardeningSlabBlock(props(s), s));
            SLABS.add(slab);
            ALL.add(slab);

            DeferredBlock<HardeningStairBlock> stairs = BLOCKS.register(NAMES[s] + "_stairs",
                    () -> HardeningStairBlock.create(props(s), s));
            STAIRS.add(stairs);
            ALL.add(stairs);
        }

        // 注意：静态初始化里绝对不能解析 DeferredBlock（.get()）——方块此时尚未注册（bind 发生在
        // RegisterEvent 期间），解析会抛 "Trying to access unbound value"。
        // 而风化关系本身也不再需要在此登记：各族方块各自覆写 getNext()，用自己的阶段表解析下一档
        // （原版 WeatheringCopper.NEXT_BY_BLOCK 是 ImmutableBiMap，第三方无法 put）。
    }

    /** 按族与档位取方块（档位越界自动收敛到 0..3）。 */
    @SuppressWarnings("unchecked")
    public static <T extends Block> DeferredBlock<T> of(Family family, int stage) {
        int s = Math.max(0, Math.min(MAX_STAGE, stage));
        return switch (family) {
            case FULL -> (DeferredBlock<T>) FULL.get(s);
            case SLAB -> (DeferredBlock<T>) SLABS.get(s);
            case STAIR -> (DeferredBlock<T>) STAIRS.get(s);
        };
    }

    /**
     * 反查一个方块属于哪一族（**不是我方硬化块返回 null**）。
     *
     * <p>用途：爆炸把硬化块变烧红时要知道该换成同形状的哪一个（见 {@link ScorchingEvents}）。
     * 只在爆炸时调用、12 次比较，不值得建表。
     */
    public static Family familyOf(Block block) {
        for (Family family : Family.values()) {
            for (int stage = 0; stage <= MAX_STAGE; stage++) {
                if (of(family, stage).get() == block) {
                    return family;
                }
            }
        }
        return null;
    }

    private static BlockBehaviour.Properties props(int stage) {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.STONE)
                .strength(HARDNESS[stage], BLAST[stage])
                // 手感摩擦：用原版默认 0.6，四档一样。真实物理差异走航空学的
                // data/sable/physics_block_properties/*.json（sable:friction 1.0/1.5/2.0/2.5），
                // 那里才是唯一生效处 —— 别在这里改，改了也不影响物理体。
                .sound(SoundType.STONE)
                // ⚠️ 必须显式开启随机刻！方块类的 isRandomlyTicking() 读的就是这个字段，
                //    漏了它就永远不会调用 randomTick → 自然风化彻底不生效（2026-09-23 用户实测发现）。
                .randomTicks()
                .requiresCorrectToolForDrops()
                .pushReaction(PushReaction.BLOCK);
    }
}
