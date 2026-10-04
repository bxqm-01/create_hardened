# 机械动力：硬化剂 / Create Hardened

> 给 Create 加一种"会像铜一样风化"的硬化块，以及围着它长出来的一整套硬核工业化内容。

- Minecraft **1.21.1** · NeoForge **21.1.249+** · Create **6.0.10+** · **Sable 1.2.2+**（硬依赖）
- 当前版本：**1.0.0-beta**
- 授权：**MIT**
- 仓库：<https://github.com/bxqm-01/create_hardened>

## 内容

**硬化块**：初始硬度与爆炸抗性等同黑曜石，随随机刻依次风化为 **微裂 → 风化 → 脆化**，每一档硬度与爆炸抗性都会下降；
用**黑曜石粉**右键可以让它恢复一个阶段（消耗一个）。装上《航空学》(Sable) 后：硬化块在物理化结构里的质量是黑曜石的四分之一，
摩擦随风化程度升高（1.0 / 1.5 / 2.0 / 2.5）。

围绕它还有：

| 内容 | 说明 |
| --- | --- |
| **黑曜石塑封剂 + 伪装外壳** | 框选一格方块套上外壳，外观完全不变；要挖两次才掉本体，爆炸先震碎外壳、第二次才炸本体 |
| **硬化乳浊液** | 新流体（含桶、遗骸沉积岩、远古合金碎屑） |
| **谐振感磁块** | 力学状态下按速度线性改变受力，可当刹车或智能配重 |
| **硬化流体储罐（7×7）+ 硬化流体泵** | 继承原版储罐与泵；泵在同一转速下合法搬运两倍液体（守恒，不刷液体） |
| **冲爆引擎** | 以爆炸为燃料的应力来源：挨炸攒应力、提转速，之后自然衰减；每挨一次爆炸 50% 概率进入下一损伤阶段，第 3 阶段再挨一次即损毁 |
| **烧红机制** | 硬化块被爆炸概率烧红；烧红块有注水 / 遇水 / 遇冰 / 自然冷却四条不同的冷却出口；再由切割与冲压接出硬化锭 / 板 / 构件材料链 |
| **Ponder 思索** | 9 个场景：硬化块 ×3、烧红块、谐振感磁块 ×2、冲爆引擎、硬化流体储罐、硬化流体泵 —— 手持对应方块按思索键即可打开 |

## 安装

1. Minecraft **1.21.1** + NeoForge **21.1.249+**
2. `mods/` 里放好 **Create 6.0.10+** 与 **Sable 1.2.2+**
3. 从 [Releases](https://github.com/bxqm-01/create_hardened/releases) 下载 `createhardener-<版本>.jar`，放进 `mods/`

> ⚠️ **Sable 是硬依赖**：塑封外壳与硬化块的物理回调直接用到了它的 API，没装会让模组加载失败。

## 构建

需要 **JDK 21**。

```bash
./gradlew build          # Windows: gradlew.bat build
```

产物在 `build/libs/createhardener-<版本>.jar`。

⚠️ Create / Flywheel / Ponder(Catnip) / Registrate / Sable **都不随本仓库分发**，编译前先把对应的 jar 放进 `libs/`，见 [libs/README.md](libs/README.md)。

版本号只有一个来源：`src/main/resources/META-INF/neoforge.mods.toml` 里的 `version="…"`，构建脚本会读取它来给产物命名。

## 目录结构

```
src/main/java/dev/createhardener/          主代码（硬化块族 / 塑封 / 流体 / 机器 / 引擎 / 客户端渲染）
src/main/java/dev/createhardener/sealant/  黑曜石塑封剂（框选状态机、外壳数据、同步包）
src/main/java/dev/createhardener/client/   客户端专用（渲染器、模型、描边）—— common 侧不得引用
src/main/java/dev/createhardener/mixin/    仅两处注入（泵压力翻倍、爆炸强度接收）
src/main/resources/assets/createhardener/  模型 / 贴图 / 语言文件
src/main/resources/data/                   配方 / 掉落表 / 标签 / Sable 物理属性
libs/                                      编译期依赖 jar（不入库，见其 README）
```

## 状态

**1.0.0-beta**：内容已齐、可正常游玩，仍在打磨。已知问题与未完成项见 [CHANGELOG.md](CHANGELOG.md)。

## AI 披露

本模组在开发过程中使用了 **AI 辅助（DeepSeek 系列模型，经由 DeepSeek Harness 的模组开发工作流）**：

- **AI 参与**：代码编写与整合、资源整理（模型 / 方块状态 / 配方 / 标签的生成与校验）、中英文文本、构建脚本、本 README 与发布元数据。
- **人工完成**：玩法设计、数值平衡（硬度 / 爆炸抗性 / 风化概率 / 引擎参数等全部由作者拍板）、贴图绘制、以及游戏内的实测与验收。
- 所有内容在上线前均经过人工检查与游戏内完整测试。

## 授权与致谢

本项目以 **MIT** 授权发布，见 [LICENSE](LICENSE)。

- [Create](https://github.com/Creators-of-Create/Create) —— 本模组完全建立在它的动能 / 流体 / 应力系统之上
- [Sable](https://github.com/ryanhcode/sable) —— 子世界（物理化结构）库，塑封与硬化块的物理行为依赖它
- [Flywheel](https://github.com/Engine-Room/Flywheel) / Ponder —— Create 自带的渲染与思索库
- 贴图：本模组全部贴图由作者绘制。方块模型：储罐与泵的模型参照 Create 原版改写，其余模型为自制

## 反馈

Bug 与建议请走 <https://github.com/bxqm-01/create_hardened/issues>；如果方便，附上 `logs/latest.log` 与复现步骤会快很多。
