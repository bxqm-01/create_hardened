# libs/ —— 编译期依赖（**不入库**）

这里放的 jar 只用于编译，**不会**被打进产物，也不需要提交到 git（见根目录 `.gitignore`）。
克隆仓库后，先按下面的表把 5 个 jar 放进来，再跑 `./gradlew build`。

| 放到 `libs/` 里的文件 | 从哪来 |
| --- | --- |
| `create-1.21.1-6.0.10.jar` | CurseForge / Modrinth 下载 Create（1.21.1）；或直接复制整合包 `mods/` 里那个 |
| `flywheel-neoforge-1.21.1-1.0.6.jar` | Create 的 jar 里 `META-INF/jarjar/` 内；或 maven.createmod.net / Modrinth |
| `ponder-neoforge-1.0.82+mc1.21.1.jar` | 同上（**它内含 `net.createmod.catnip`，本模组编译要用**） |
| `Registrate-MC1.21-1.3.0+67.jar` | 同上（本模组用它的 `BlockEntry` / `ItemEntry` 做注册） |
| `sable-neoforge-1.21.1-<版本>.jar` | Modrinth / CurseForge 的 Sable；或复制整合包 `mods/` 里那个 |

最省事的做法：把整合包 `mods/` 里的 **Create** 与 **Sable** 两个 jar 复制进来，
再用 7-Zip / 压缩软件打开 Create 的 jar，把 `META-INF/jarjar/` 里的
**flywheel**、**ponder**、**Registrate** 三个 jar 也解出来放进来。

> 为什么不明写 maven 坐标自动拉？
> Create 有官方 maven（`https://maven.createmod.net`）：
> `com.simibubi.create:create-1.21.1:<版本>:slim`、`dev.engine-room.flywheel:flywheel-neoforge-1.21.1:<版本>`（已核实可用）。
> 但 Sable、Catnip、Registrate 的 maven 坐标没有逐一核实过，所以默认走"手动放 jar"这条一定能跑通的路。
> 想改成 maven 的人可以自行替换 `build.gradle` 里的 `dependencies { }`。
