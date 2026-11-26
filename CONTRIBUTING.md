# 贡献与仓库说明

> 面向想自己编译、提问题或提交改动的人。机制细节不在这里，见文末「文档地图」。

## 这个仓库是什么

MineColonies 的附属模组：把「没有自身建筑方块」的道路与简单地形改造，做成可规划、由原生建筑工具施工、可长期管理的对象。

- 版本与依赖以 `README.md` 为准；当前是 1.21.1 / NeoForge 21.1.259，不能独立运行。
- 许可 GPL-3.0（`LICENSE`）。保留原作者署名，修改版需说明改动。
- 构建不依赖 Gradle：手写 `build.ps1` + 未入库的 `cp.txt` 描述 classpath。

## 目录速览

| 路径 | 内容 |
|---|---|
| `src/main/java/com/YLFXM/rp/crossroads/` | 代码。`road/` 服务端机制，`client/` 窗口与渲染，`network/` 消息，`mixin/` 注入 |
| `src/main/resources/assets/rp_crossroads/` | 窗口 XML、语言、物品贴图、预切单元蓝图（`blueprints/units/`）与道路名默认词表 |
| `tests/` | 独立 `main` 回归程序，`run.ps1` 逐个起进程 |
| `tools/` | `export_road_units.py`（单元导出与校验）、`check_lang.py`（中英键集比对） |
| `docs/` | 设计、实现进度、代码结构与机制 |

## 构建

前置：JDK 21；`cp.txt`（本机依赖 JAR 列表，不入库）；MixinExtras 需在编译期可见——从 NeoForge 的 `universal.jar` 解出 `META-INF/jarjar/mixinextras-neoforge-*.jar` 放到 `libs/`，并在 `cp.txt` 末尾追加其绝对路径。运行期不需要这一步（NeoForge 已 jarjar 加载）。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

产物：`dist/rp_crossroads-<版本>.jar`。版本号来自 `src/main/resources/META-INF/neoforge.mods.toml`。

一次干净的构建前先删 `build/classes`、`build/test-classes` 与旧 jar；脚本里的删除守卫在部分环境（如被安全工具接管）会中断。

## 测试

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/run.ps1      # 必须在仓库根执行
python tools/export_road_units.py --verify                                  # 单元清单与蓝图一致性
python tools/check_lang.py                                                  # 中英文键集
```

- `tests/run.ps1` 按相对路径读单元清单，换目录会失败。
- 回归需要原版客户端 JAR，可用 `MCRECONSTRUCTION_TEST_CLIENT_JAR` 指定。
- 回归与单元校验不能替代游戏内验收：原生施工、权限、取消/重启、多人交互都要实机验证。
- 自动化代理不启动游戏。

## 提交改动

1. 一次只改一件事，不做顺手重构；行为变化必须同步四份文档（设计、结构、进度、README）。
2. 提交前构建 + 全量回归通过；报告里如实区分「通过 / 待测 / 未验证」。
3. 提交标题 `功能说明（版本号）` 或 `修复范围（版本号）`；正文按 `## 新增 / ## 修复 / ## 文档` 分节，只写相对上次提交的净变更。
4. 任务提交不推送，阶段收尾的中版本提交时推送一次。

报告问题时请附：游戏 / NeoForge / MineColonies 版本，单人还是专用服务器，复现步骤与截图，`logs/latest.log`（崩溃再附 `crash-reports`），以及客户端与服务端 JAR 是否同一份。

## 文档地图

| 文档 | 读者 | 内容 |
|---|---|---|
| [`README.md`](README.md) | 玩家 | 安装、玩法、配置、已知限制 |
| [`docs/阡陌纵横-设计文档-1.0.0.md`](docs/阡陌纵横-设计文档-1.0.0.md) | 所有人 | 当前有效规则与边界（随版本改名） |
| [`docs/代码结构与机制.md`](docs/代码结构与机制.md) | 改代码的人 | 函数级调用链（权威）、逐文件职责、**附录 A 版本移植检查**、**附录 B 修改机制的边界** |
| [`docs/阡陌纵横-实现进度.md`](docs/阡陌纵横-实现进度.md) | 所有人 | 阶段完成度与验收状态 |
| [`CHANGELOG.md`](CHANGELOG.md) | 所有人 | 每个补丁的净变更 |
| [`AGENTS.md`](AGENTS.md) | 自动化代理 | 协作与交付规则（压缩恢复、方案先行、验收流程） |

移植到新版本时，先读结构文档附录 A；改机制前先读附录 B。
