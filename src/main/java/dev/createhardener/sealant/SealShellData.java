package dev.createhardener.sealant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 塑封外壳表（逐格独立）。
 *
 * <p>每格记录"被外壳包住的原始方块"：**方块状态**（必存）+ **方块实体 NBT**（若有）+ **所在维度**。
 * 外壳被破坏时用它把方块原样放回。
 *
 * <p><b>为什么要记维度（2026-09-23 修）</b>：这张表是**全局一张**（存在主世界的 SavedData 里），
 * 而坐标只在同一个维度内唯一 —— 主世界和下界在同一个 x/y/z 上有塑封块时，
 * 查表会互相命中，可能"挖 A 把 B 换回来"。带上维度后，查表只认自己这个维度。
 */
public class SealShellData extends SavedData {

    public static final String DATA_NAME = "createhardener_seal_shells";

    /** 一格外壳记录的原始方块信息。 */
    public record ShellEntry(ResourceKey<Level> dimension, BlockState state, CompoundTag blockEntityNbt) {
    }

    private final Map<BlockPos, ShellEntry> shells = new HashMap<>();

    public static SealShellData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<SealShellData>(SealShellData::new, SealShellData::load, null), DATA_NAME);
    }

    /** 该格在**这个维度**里是否登记了外壳。 */
    public boolean isShell(ServerLevel level, BlockPos pos) {
        return entry(level, pos) != null;
    }

    /** 取该格的记录；维度不符视为没有（未登记 / 别的维度 都返回 null）。 */
    public ShellEntry entry(ServerLevel level, BlockPos pos) {
        ShellEntry entry = shells.get(pos);
        if (entry == null || !entry.dimension().equals(level.dimension())) {
            return null;
        }
        return entry;
    }

    public int size() {
        return shells.size();
    }

    public List<BlockPos> positions() {
        return new ArrayList<>(shells.keySet());
    }

    /** 直接给扫描用的视图（不复制，调用方不要改）。 */
    public java.util.Set<BlockPos> positionSet() {
        return shells.keySet();
    }

    public boolean hasAny() {
        return !shells.isEmpty();
    }

    /** 登记一格外壳（记住它属于哪个维度）。 */
    public void put(ServerLevel level, BlockPos pos, BlockState state, CompoundTag beNbt) {
        shells.put(pos.immutable(), new ShellEntry(level.dimension(), state, beNbt));
        setDirty();
    }

    /**
     * 移除一格外壳并返回它的原始方块信息。
     *
     * @param level 传 null 表示**强制移除、不看维度**（给 SavedData 内部与全局清理用）
     */
    public ShellEntry remove(ServerLevel level, BlockPos pos) {
        ShellEntry existing = shells.get(pos);
        if (existing == null) {
            return null;
        }
        if (level != null && !existing.dimension().equals(level.dimension())) {
            return null;                    // 是别的维度的记录，不动它
        }
        shells.remove(pos);
        setDirty();
        return existing;
    }

    // ------------------------------------------------------------ 序列化

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, ShellEntry> e : shells.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("pos", e.getKey().asLong());
            entry.putString("dim", e.getValue().dimension().location().toString());
            entry.put("state", NbtUtils.writeBlockState(e.getValue().state()));
            if (e.getValue().blockEntityNbt() != null) {
                entry.put("be", e.getValue().blockEntityNbt());
            }
            list.add(entry);
        }
        tag.put("shells", list);
        return tag;
    }

    public static SealShellData load(CompoundTag tag, HolderLookup.Provider registries) {
        SealShellData data = new SealShellData();
        ListTag list = tag.getList("shells", Tag.TAG_COMPOUND);
        var blockLookup = registries.lookupOrThrow(Registries.BLOCK);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong("pos"));
            // 旧存档没有 dim 字段 → 按主世界处理（那时也只有主世界在用）
            ResourceLocation dimId = entry.contains("dim")
                    ? ResourceLocation.tryParse(entry.getString("dim"))
                    : Level.OVERWORLD.location();
            if (dimId == null) {
                dimId = Level.OVERWORLD.location();
            }
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
            BlockState state = NbtUtils.readBlockState(blockLookup, entry.getCompound("state"));
            CompoundTag be = entry.contains("be") ? entry.getCompound("be") : null;
            data.shells.put(pos, new ShellEntry(dimension, state, be));
        }
        return data;
    }
}