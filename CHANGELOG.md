# 更新记录

## 0.3.2 — 2026-11-22 视觉统一与风格选择窗

### 新增

- **`client/UiStyle`**：全部窗口共用的视觉常量与工具——8 px 安全区、20 px 行高、标题 0.9 / 正文 0.8 / 次要 0.7 三档字号、`disable(Button)` 与 `selectable(Button)`、纸底与按钮档位常量。窗口类里 16 处手写 `setColors(...)` 全部换成本类。
- **风格选择窗正式形态**：420×240、`OVERSIZED_VANILLA` + `lightbox`、无底图白字；双列 200×100 封面卡片（封面 80×80、名称 1.2、描述 0.6、作者 0.8、选择按钮 86×17），顶部「取消 + 搜索框（25 字符上限）」，系列级为单列 200×28 紧凑行并显示等级数；「取消」在系列级回风格级、风格级关闭窗口。
- **风格自描述**：新增可选 `units/<风格>/style.json`（`name`/`desc`/`authors`/`cover`，全部可选），名称缺省回退语言键再回退目录名，封面缺省回退默认图标；`CustomRoadPack` 在种子里复制 `style.json`（缺失才写）与 `cover.png`（缺失或字节不同即覆盖）。随包附带 `original/style.json`（仅声明封面）与 `original/cover.png`（400×400，源自 MineColonies 原版风格包封面，同 GPL-3.0）。
- **协议 15 → 16**：`UnitStyleListPayload` 扩展为携带风格名称/描述/作者/封面与每个系列的最高等级；面板与等级窗的「系列 N/M」改从该清单读取，第三方风格不再显示为 0 级。

### 修复

- **按钮不再被压扁**：改为只换原生贴图档位（large/medium/medium_small/small/quite_small/very_small/mini），高度保持 17/16/15，消除边框横向拉伸。
- **横版窗口背景换成 `builder_paper_wide2.png`（400×244）**：竖版 `builder_paper_sketch` 不再用于横版窗口，控件全部退到 8 px 安全区内，不再压纸边。
- **W6 拆除方式与 Tier 选等级补纸质底**：原为无底图白字，现为纸底黑字，按钮换原生档位。
- **W7 道路清单 / W8 工单清单统一行模板**：行高 20、勾选 `mini` 14×15、主标题 0.8 + 副标题 0.7（状态/长度/坐标，或类型/优先级/等级/坐标并为一行），底部「全选/清除/关闭 + 整宽主操作」；空列表在副标题写原因（未绑定 / 无可显示项）。
- 控制器面板收为 248×150：三行信息 + 四行按钮 + 灯距/镜像行 + 冲突红字；W5 交叉口 336×228（关系行 60 高）。
- 第三方风格「列得出、切不了」：切换时的宽度/可走列/等级上限改为优先读存档包内 `units.json`，只有包内缺失才回退 jar 清单。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.2.md`：新增 §7.3 视觉规范（贴图档位、字号、安全区、窗口尺寸表、W7/W8 行模板），改写 §2.6 风格自描述与种子、§3.8 清单读取、§7 窗口清单。
- `docs/代码结构与机制.md`：新增 `UiStyle` 与风格资产两个资源条目、`UnitStyleListPayload` 新字段、清单请求链、`RoadUnitLibrary.styleMeta` 与按包清单读取、回归表（26 程序 3371 条）与基线 JAR。
- README 中英：风格选择窗的卡片/搜索/`style.json` 说明。
- 验证：构建通过（产物 841,040 B / sha256 `B6554A0A65D8…`）；`tests/run.ps1` 26 个程序 3371 条断言 + `export_road_units.py --verify`（21 份单元、80 个非空气调色板项）全绿；三处同哈希部署。游戏内验收待人工执行。

## 0.3.1 — 2026-11-21 交互收敛与合并面板

### 新增

- **控制器面板承接全部管理**：`WindowRoadController` 与绘制器面板合并为唯一入口——路名/状态/施工者三行、「建造 / 升级」一个按钮（`DRAFT` 按建造、已规划按升级到 N、施工中显示「施工中」并禁用、到顶显示已满级）、修复、修改蓝图、交叉口、拆除、风格与系列、灯距 ±1、镜像；面板不显示绘制器绑定状态，也不呈现地形模式。
- **交叉口入口迁到面板**：`WindowRoadCrossings(roadId, controllerPos)`，「返回」回到控制器面板；内容与门禁不变。
- **风格 / 系列功能版选择窗** `WindowRoadStyleList`（纸质底两列按钮）：左列风格、右列该风格的系列，默认落在该路当前值；确认按 1 级发 `SetRoadStylePayload`。清单由服务端扫描存档结构包得出（新增 `UnitStyleListPayload` / `RequestUnitStylesPayload`），`RoadUnitLibrary.styles(server)` 与 `seriesIds(server, style)` 改为扫 `units/` 一级目录与 `units/<风格>/<系列>/units.json`，与 jar 清单并集去重排序，第三方风格文件夹放进目录即可出现。
- **镜像即时下发**：面板「镜像」翻转后立刻发 `ConfigureRoadPayload(CONFIGURE)`，`plan.mirror` 当场落库，幽灵同时重画；灯距同样即时生效。
- **拆除由服务端判定**：`/mcrcross dev on` 时服务端回新增的 `OpenRemovalChoicePayload`，客户端才弹「建筑工拆除 / 原生快速拆除」，选完带 `confirmed` 回发；正常游玩直接派单。单条拆除与清单「拆除所选」共用这套判定。
- 协议 14 → 15：新增 `UnitStyleListPayload`、`RequestUnitStylesPayload`、`OpenRemovalChoicePayload`，`RoadControllerActionPayload` 与 `RoadBulkActionPayload` 增加 `confirmed` 字段。

### 修复

- **绘制器改为纯手势**：删除 `WindowRoadWand` 与 `windowroadwand.xml`。右键非控制器方块切该行类型；Shift + 右键循环「道路 → 挖掘 → 填充」并清空 A/B；地形模式下右键方块或空气直接下发 `TerrainOrderPayload`；控制器上的右键仍是开面板/绑定。`ItemRoadWand.use` 不再开窗。
- **删除「清除」**：面板与绘制器窗口的清除按钮一并移除，`RoadPlanningActions` 里 `action=4` 的整段清除逻辑删除，并对 4 显式拒绝，避免落到规划分支；重新点 A/B 即覆盖选择。
- `WindowRoadRemovalChoice` 不再按游戏模式自行判断是否弹窗（原「生存直接执行、创造弹窗」），改由服务端决定。
- Shift + 右键**空气**现在与右键方块一样循环模式：原实现把循环只挂在方块路径上，空气路径遇到 Shift 直接返回，准星不在方块上时切不了道路/挖掘/填充。
- 控制器面板的建造/灯距/镜像在本地先校验 A/B：缺端点时提示先选端点，避免服务端为未开工道路按 `length` 生成一条桩路。
- **面板动作不再要求绘制器绑定**：`Network.handleConfigure` 与 `RoadPlanningActions` 去掉「背包里有绑定到该控制器的绘制器」的门禁——控制器坐标本身就是作用对象，绑定的意义只剩「绘制器手势编辑哪条路」。门禁改为管理权 + 状态（`canCreate`/`canEdit`/忙碌）。原实现于此会静默丢弃从面板发出的交叉口动作。
- 回归随改动同步：`UiLocalizationRegression` 摘掉绘制器窗口的字面量条目、改为断言旧窗口类与布局已删除、新风格窗存在（290 条）；`NetworkChannelsRegression` 客户端通道数 7 → 9。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.1.md`：重写 §3.2 右键手势、§3.3 合并面板、§3.7 拆除判定、§3.8 换单元（1 级落地 + 目录扫描）、§7 窗口清单、§9 地形入口，并清理「清除」相关旧句。
- `docs/代码结构与机制.md`：协议 15、13 类 C→S / 9 类 S→C、新增三条消息与两个窗口的文件职责、2.10 拆除判定分支、2.14/2.15 入口改写、回归断言口径（25 套 3114 条 + 导出校验）与基线 JAR 信息。
- README 中英：三件工具表、首次规划步骤、编辑与拆除手势、地形模式入口、风格/系列选择窗与拆除/清除的新规则。
- 验证：构建通过（产物 827,118 B / sha256 `3DA53A4349DE…`）；`tests/run.ps1` 25 个程序 3114 条断言 + `export_road_units.py --verify`（21 份单元、80 个非空气调色板项）全绿；三处同哈希部署。游戏内验收 T1–T9 全部通过（含 T2b「Shift + 右键空气循环模式」，2026-11-21）。

## 0.3.0 — 2026-11-19 地形工单、幽灵距离与 P3 阶段收尾

### 新增

- **地形改造工单**：「挖掘 / 填充选区」把绘制器的目标从道路换成地形选区（面板「已绑定」行显示目标，绑定另一条道路即切回）；选点时客户端按 A/B 实时绘制 AABB 框（挖=黄色、填=绿色），且跳过道路模式的轴线限制，点面板「建造地形选区」才生成蓝图并打开原生 `WindowExtendedBuildTool`（`selectionCallback=null`），确认后由 Structurize 的放置消息创建装饰工单；蓝图原点与锚点是同一点（选区最小角）；显式写入 `primary_offset = A − 最小角`，避开 Structurize 在无锚点方块时默认取 `(sizeX/2, 0, sizeZ/2)` 造成的半箱体错位。挖掘 = 全 air 蓝图，填充 = 全 `structurize:blocksolidsubstitution` 占位符，由建筑工按 `getSolidSubstitution()` 解析成建造工小屋设置里的「填充方块」，材料从殖民地仓库出。
- `Config.maxTerrainOrderVolume`（默认 32768）限制单次选区体积；单边上限 256、越界高度直接拒绝。
- `Config.ghostRenderDistance`（16–256，默认 64）：幽灵蓝图渲染距离由 `RoadRenderRange` 读配置，不再硬编码。
- `road/common/TrustedOrderDispatch`：工单入队（`addWorkOrder` + `onAdded`）的单一实现；当前只服务道路工单（传 `readingFromNbt=true`，蓝图已按殖民地校验过）。地形工单不再走它——由原版放置消息创建。

### 修复

- 地形临时蓝图（`generated/terrain_<uuid>.blueprint`）：`TerrainOrders.sweep` 每 100 tick 删除已有工单引用的文件（工单自带蓝图副本）；取消不产生工单，因此取消保留文件。另在生成新选区、道路蓝图应用、重新绑定时清理，只删本人生成的。
- **单元清单哈希改为内容哈希**：`units.json` 的 `sha256` 覆盖解压后的 NBT，不再取 gzip 容器字节——deflate 输出依赖 zlib 版本，换解释器导出会让 `export_road_units.py --verify` 全项失配。`CustomRoadPack.matchesHash` 与 `RoadUnitDataRegression` 同步改为先解压再摘要。
- `Config.ghostRenderDistance()` 在配置未加载时回落到声明默认值（64），回归进程可直接调用 `RoadRenderRange.contains`，不再抛 `Cannot get config value before config is loaded`。
- 回归调用点随 0.2.2 的签名变更同步：`RoadUnitLibrary.supports` 增加风格参数、`UnitTemplate` 增加 `anchorColumn`、`RoadJointWork.sharedCell` 增加 `aWins`（原「两种铺装冲突即抛异常」的断言改为「由主导路裁决」）。

### 文档

- 新增 `output/P3/任务4/地形与体验项.md`：CLEAR 阶段依据、入口复用、填充方块来源、清理时机与测试项；P3 实施计划同步任务 4 的最终范围（Q1 / Q8 不做）。
- README 中英补地形工单与幽灵距离配置；结构文档补 `TerrainOrders`、`TrustedOrderDispatch`、`TerrainOrderPayload` 与绘制器面板新按钮。
- 设计文档随中版本改名 `docs/阡陌纵横-设计文档-0.3.0.md`：新增「系列、等级与风格包」「升级与换单元」「地形挖填」三章，修正单元存放形态、控制器面板 Upgrade、共享格裁决、幽灵显示距离四处旧结论。
- `docs/代码结构与机制.md`：补 `WindowRoadStyle`、`RoadDevMode` 两个源文件与新资源清单，新增风格切换与地形派单两条调用链，更新回归断言口径（25 套 3153 条 + 导出校验）。
- README 中英：版本号、系列/等级支持范围、与交叉关系相关的切换限制、`roadAnchorBlock` 默认值（`minecraft:dirt`）与两项配置。
- 协议版本 13 → 14（`ConfigureRoadPayload.tier`、`SetRoadStylePayload.level`、新增 `TerrainOrderPayload`）。
- 验证：构建通过（产物 545,208 B / sha256 `18D9A99C0646…`）；`tests/run.ps1` 25 个程序 3153 条断言 + `export_road_units.py --verify`（21 份单元、80 个非空气调色板项）全绿；游戏内验收覆盖地形模式切换、实时选区框、挖/填工单、锚点对齐、占位符填充与临时蓝图清理。

## 0.2.2 — 2026-11-19 多系列与升级

### 新增

- **单元资产**：`units/original/{streets,main}/` 共 21 份单元蓝图（road / stair / lamp × 街道 3 级 / 主路 4 级），清单升 `schemaVersion: 2`（`series.<系列>.units.<角色>.<等级>`），新增 `sha256`、`anchorColumn`、`surfaceY` 与 `canonicalFacing` 字段。`tools/export_road_units.py` 重写：直接解析源包 NBT（每 int 打包两个 palette 索引，按 y→z→x 展开），完成切割/转置/镜像/派生台阶/重建栅栏连接，`--verify` 保证导出幂等。
- **单元风格维度**：`RoadPlan.unitStyle`（NBT `unitStyle`，旧档读 `original`），路径参数化为 `units/<style>/<series>/`，`RoadUnitLibrary` 的 `get` / `supports` / `seriesWidth` / `seriesWalkRange` / `seriesIds` / `maxLevel` 全部带 style；为后续多风格包留出入口。
- **控制器面板「风格包 / 系列 / 等级」**：`WindowRoadStyle` 三列选择（等级列 4 槽，超出系列实有等级隐藏），按钮文案显示当前「风格 · 系列 · 等级 N/M」；草案切换即时重发提案刷新幽灵，已规划/已建成改走派单重建；宽度变化且有交叉时拒绝并说明原因。
- **创造模式建造前选等级**：`WindowRoadTier` 在点「建造」时询问等级，结果随 `ConfigureRoadPayload.tier` 一次送达服务端。
- **修复与升级改派单**：`RoadRepair.beginSurvival(level, player, plan, targetLevel[, originLevel])`，不再要求背包持有建筑工具、不做「一人只开一个会话」检查；工单经 `RoadBuilderDispatch` 直接进殖民地。升级 = 目标等级 +1 的同一操作，按钮按 `RoadUnitLibrary.maxLevel` 门控。计划拆成 `prepareJoint` / `publish` / 打开工具三段，派单复用前两段。
- **等级回滚**：`RoadRepairSession.originLevel`（NBT `originLevel`）。面板切换会先把新等级写到 plan 上，取消或工单被拒时据此恢复；`commitRows` 才把目标等级写回。
- **开发者模式**：`/mcrcross dev [on|off]`（权限 2，仅内存），开启后「修复」回到「冻结 + 建筑工具自行放置」的旧流程。

### 修复

- 交叉口共享格冲突：`RoadJointWork.sharedCell(a, b, aWins)` 在规则用尽后由主导路裁决，`RoadCrossings.higher` 统一判定（等级高者胜，同级 `main` 胜 `streets`），`decorate` 的路面同源，不再抛 `Conflicting native work targets`。
- 灯具叠加改为按整个矩形遍历：有方块的格写入，空格不写（路面上方照常由建筑工清空），不再把灯伸出的列当挖掘目标。
- 主路路灯改为单边悬挑 2 宽（`anchorColumn=0`），不再需要把蓝图横向撑宽；灯柱落在路沿列。
- 主路路灯底座：单元 y0 空、y1 底座（1 `structurize:blocksolidsubstitution`、2 `minecraft:oak_planks`、3 `minecraft:stone_bricks`、4 取同级 `road` 的路沿材质），`surfaceY=1`、高 7，与街道灯同构。
- `domum_ornamentum` 方块必须连同方块实体复制（材质在 `textureData`），否则 3/4 级路灯渲染为空。
- `ensureUnitManifest` 读 jar 清单时漏了风格包前缀，存档 `units.json` 因而从未刷新，单元新增格（底座）不进入道路蓝图。
- 幽灵缓存键补 `unitStyle` / `unitLevel` / `width`，切换后不再复用旧预览。
- 建造工具的预览分类回归 `units/original/{streets,main}` 两组，旧扁平文件与旧清单在进入世界时清除。

### 文档

- 新增 `output/P3/任务2/多系列与升级.md`（切割规格与数据模型）、`交叉口与升级方案.md`、`全局等级与多风格包.md`（搁置）、`提示词-风格包支持判定.md`。
- 结构文档补 `beginSurvival` 派单链路、`RoadDevMode`、`WindowRoadTier`、`SetRoadStylePayload` 的等级字段与新增窗口。
- README 中英补修复/升级派单、面板等级显示、创造模式建造前选等级。
- 验证：构建通过；`RoadUnitDataRegression` 1638 条断言 + `export_road_units.py --verify` 全绿；人工验收覆盖方案切换、等级选择、修复/升级派单、dev 模式、交叉口材质与路灯底座。

## 0.2.1 — 2026-11-17 单元预览入包与开面板自愈

### 新增

- 单元预览分类：`<world>/rp_crossroads/structurepack/units/original/streets/` 下的 `road_1` / `stair_1` / `lamp_1` 三份蓝图，`CustomRoadPack.seedUnits` 启动时从 jar 落地并改写清单 `file` 字段；`RoadUnitLibrary` 先读包内、缺失时回退 jar。
- 内容版本哈希由「只算 `initial/`」扩为「`initial/` + `units/`」，单元资产变化能到达客户端。
- `pack.json` 新增 `revision` 字段：预览资产被修复时 +1 并据此改变版本号，让持有坏副本的客户端重新拉包。
- 自愈检查 `CustomRoadPack.ensureUnits`：每次开原生建造工具前校验单元与清单，用 `NbtIo` 真实解析，缺失/空/截断/乱码都从 jar 补齐。
- `WindowExtendedBuildToolNameMixin`（客户端桶）+ `client/RoadUnitNames`：列表显示剥掉 `_<等级>` 后缀，为多等级分组出原生 `Level:` 行做准备。

### 修复

- 单人存档里存在两份同名 `Crossroads` 包（存档包与 `blueprints/Crossroads` 副本），structurize 的 `packMetas` 按名覆盖，客户端启动先扫到副本就会顶掉存档包：单人不再写客户端副本，登录时删除残留目录。
- 构建工具里残留的空 `removal` 分类：登录时整棵删除客户端旧目录。
- 单元预览目录由 `units/原版/街道/` 改为 ASCII `units/original/streets/`，旧目录由 `migrateLegacyUnitTree` 移动文件后删除。

### 文档

- README 中英补 `units` 预览分类说明；结构文档补命名/自愈/修订号与 name mixin；进度清空任务 1 的第 4 节。

## 0.2.0 — 2026-11-16 P3 基础：Mixin 引入与体验批次

### 新增

- **Mixin 基础设施**：`neoforge.mods.toml` 声明 `[[mixins]]`，jar 根新增 `rp_crossroads.mixins.json`（`mixins` 通用桶 + `client` 客户端桶，`JAVA_21`，不使用 refmap）；编译期类路径补入从 NeoForge 包 jarjar 解出的 `mixinextras-neoforge-0.5.5.jar`，`@WrapOperation` 可直接使用，`build.ps1` 无需改动。
- **完成信号**：`AbstractWorkOrder.onCompleted` 末尾的 `removeWorkOrder` 被 `@WrapOperation` 包住，工单完成的同一刻交给 `road/RoadOrderSignal`，下一个 tick 立即跑 `RoadRepair` / `RoadNativeRemoval` / `RoadBuildWatcher` 的 `pollNow`；20 tick 周期扫描降为兜底。首次施工、跨重启 Repair、拆除三条流程实测在同一 tick 内收尾。
- **原生工具预选蓝图**：`WindowExtendedBuildToolAccessor`（`@Invoker("setBlueprint")`，`client` 桶）+ `ClientNativeBuildTracker` 按基名匹配，打开建造工具即选中本次推送的蓝图，玩家改选后不抢回。
- **准星整行高亮**：`client/RoadAim` 每帧解析命中的行，整行描边 + 准星下方显示「第 N 行 · 当前类型 → 切换后类型」，按行类型着色；行号是道路相对行号（`RoadPlan.rowNumber`，A 端起算），不是世界坐标。
- **结构包隐私过滤**：`ServerStructurePackLoaderMixin` 包住 structurize 打包时的 `Files.walkFileTree`，`road/RoadPackPrivacy` 对本存档道路包剔除 `generated/` 与 `removal/`；发起者的蓝图仍走 `OpenBuildToolPayload` 点对点下发，不受影响。
- **施工者可见**：`RoadPlan` 增加仅同步的 `builder`/`builderName`/`builderPhase`，控制器面板显示「施工中：X（准备中/建造中）」与「拆除中：X」；Repair 被拒时提示带发起者名字。
- 三个道具改为更贴合贴图的名称：**道路绘制器 / 道路清单 / 工单清单**（英文 Road Painter / Road Register / Work Order List）。

### 修复

- 生存模式 Repair 建成后被判「施工已中断」、状态回 `PLANNED`、控制器不迁移：收尾曾要求所有清空目标变空气，而装饰控制器是 `IBuilderUndestroyable`、建筑工永不拆，该格恒无法变空气。现改为 `warnUnclearedCells` 诊断日志，收尾仍由全量方块校验决定。
- 结构包在「切换包」列表沉底：注册用的 owner 改为 `CustomRoadPack.PACK_OWNER`，分类 `TreeMap` 排到首行。
- 面板在 `PREPARED` 阶段只显示「修复进行中」而没有名字：面板数据取自 `OpenRoadPlanPayload` 的快照，而施工者字段只在索引广播里追加。现两条路径共用 `Network.writeBuilder`。
- 准星与 Shift+右键回执显示的行号是 along 坐标（世界坐标）：新增 `RoadPlan.rowNumber`，两处统一为相对行号。
- 构建工具里出现空白 `removal` 分类：客户端登录时删除旧版本残留的 `blueprints/Crossroads/removal/`。
- 锚点方块默认由 `minecraft:stone_bricks` 改为 `minecraft:dirt`（`Config.roadAnchorBlock`）。

### 文档

- 设计文档随版本改名 `docs/阡陌纵横-设计文档-0.2.0.md`，同步 README（中英）、AGENTS、结构文档与进度文档中的路径与版本头；CHANGELOG 增加本版条目。
- 新增 `output/P3/P3-实施计划.md`：P3 四个任务的范围、依赖、版本与裁决记录。
- 新增 `output/P3/mixin引入/`：可行性分析与逐项裁决（C1–C8 实测结论、24 项候选清单）、批 1–4 详细设计与各轮修复记录。
- `AGENTS.md` 补 Mixin 分端规则与进度文档清理口径；`CONTRIBUTING.md` 补 MixinExtras 离线解包步骤；`.gitignore` 忽略本机解出的 `libs/`。
- 验证：构建通过（产物 487,651 B / sha256 `2ace3aee0102…`）；25 个 Java 测试程序 1475 条断言 + `export_road_units.py --verify` 25 条全部通过；人工验收覆盖客户端与专用服务器启动、生存建路/Repair/拆除、取消、跨重启、切换包置顶、准星高亮、预选蓝图、施工者可见与结构包过滤。

## 0.2.0-beta.1 — 2026-11-06 P2 收尾：整合优化与命名重构

### 新增

- 命名重构：modId `rp_crossroads`、包 `com.YLFXM.rp.crossroads`、资源命名空间 `assets|data/rp_crossroads`、语言键前缀 `rp_crossroads.`、主类 `Crossroads`、存档数据 `rp_crossroads_roads.dat`、结构包 `Crossroads`；显示名改为「模拟殖民地：阡陌纵横 / MineColonies: Crossroads」。安装后等于新模组，旧存档的道路数据不再读取。
- 交叉关系表：每维度一张，一条记录代表一对道路，`rect` 是路口权威判据，同层/分层关系与双方授权都依附记录；面板改为读表，关系变化只改一处两边同时生效。
- 行状态单表 `rows`（`type`/`level`/`lockY`/`lamp`/`base`）：原六张分表合一，`pending` 与丢失量由与 `applied` 的差派生；锁定行由 `RoadLockReconcile` 统一维护，关系解除即解锁。
- 发布流水线收成单点 `road/common/RoadProposal`：提交/丢弃提案、保存应用图、换出结构包副本、刷新索引与同步四步固定成一条，`replace < 0` 这类门禁也收在此处，失败即整体放弃，不留半套状态。
- 玩家提示统一走 `RoadNotices`（聊天栏 10 秒去重、动作栏不去重），中英语言文件 70 → 245 条；失败原因经 `road/RoadReasons` 映射为 52 条原因键，异常原文只进日志。
- 面板文案全部走翻译键：GUI XML 的 `label` 只写 `$(键)`，窗口类不再用 `Component.literal` 拼句子，`RoadState` 自带 `display()`；跨网络的行内容改传组件 JSON 与枚举名，专用服务器上的中文客户端也不会看到英文。
- 专用服务器启用：客户端处理器整类迁到 `client/ClientPayloadHandlers`，`Network` 只保留服务端接收端与一处 `Dist.CLIENT` 守卫；服务端空实现补声明 7 个客户端通道。

### 修复

- 删除道路或释放计划只在真实相邻范围内重建：只调度交叉关系表登记或全宽足迹实际相交的邻路，一批只报一条「有 N 条道路蓝图重建」，不再全维度重算与逐条刷屏。
- 结构包版本只哈希 `initial/`：`generated/` 的临时蓝图不再参与版本，避免每次建路都改版本、Structurize 客户端因版本不一致把 `Crossroads` 包摘掉；版本未变时 `install()` 不重写 `pack.json`、不重复注册。
- 分层穿越的高度求解与施工准备改用同一授权判据：自己的路口不再被当成外来路，不再额外空出一截路灯高度。
- 已确认道路的 Repair 中断后可续跑，取消与失权不再留下半套状态；拆除会话独立于施工会话，取消保留拆除表。

### 文档与验证

- 设计文档与进度文档迁入 `docs/` 并随仓库跟踪，逐文件职责见 `docs/代码结构与机制.md`；本轮逐项设计记录留在 `docs/道路系统/整合优化/`（本地）。
- 构建与回归：25 个 Java 测试程序 1471 条断言 + 25 条单元导出校验，全部通过；`tools/check_lang.py` 校验中英键集合与 GUI 标签；网络协议 10 → 13，两端与所有联机玩家必须同版本。

## 0.1.7 — 2026-10-30 阶段7：交叉口与分层穿越

- 同层路口：以两路走行区实际交集判定并严格平路等高；共同平台高度可增可减，自动高度只作初始值，不可实现时回退并提示；一条路的多个路口各自独立高度。
- 分层穿越：逐格保证至少 3 格路面差与实际 2 格通行净空；上层交会跨度无地基、无桥墩；下层完整路灯避让并以占位保护其结构。
- 关系与切换：同层/上跨/下穿按道路对保存；切换先试下一种、再试剩余一种，均不可行则提示并保留原关系；每次从基础曲线重算，不叠加历史抬升。
- 邻路协商：无权邻路可显式请求形成路口，2 秒防抖、120 秒超时默认同意、按最高权限在线者处理；批准后自动切同层并重算。授权为成对双向，绑定对称边界摘要，交叉位置、范围或归属变化即失效。
- 可行性判定不再探测未参与道路：只看参与道路自身的锁定行（锚点行与已记录同层路口行及其高度）与每行一级台阶的坡度预算；行级锁定由新增的 `RoadLockReconcile` 统一维护，关系解除立即解锁。
- 路口角落与路灯：角落不再双方互相让位，共享格两侧都有内容时取同一目标；生成蓝图时逐行记录该行是否有灯，角落灯统一由东西向道路提供，其相邻原生灯避让。
- 控制器迁移仍先放置回读再删旧；锚点行的灯被路口避让时，迁到最近的、确实有灯的行旁，落在路面之上并朝向该灯柱格，找不到则保留原位置。
- 施工边界：工单允许邻路局部改造但不顺带建整条未建邻路；无权邻路的结构始终整体保护。拆除同层按邻路实际全宽整列保护、分层按三维结构保护，缺实际快照时保守保留。
- 已建路口在关系、交集与局部高度不变时可凭冻结证明免重复批准；新结构、灯、高度或范围变化仍需授权。
- 手改行挡住路口时先归因再询问：关系切换与高度调整都可确认覆盖重算，只丢弃真正需要改变的行；高度调整改为提示并等待 60 秒，超时保留自动高度。
- 提示与诊断：预览不再重复播报，提示按玩家与道路节流；缺实际几何的道路降级为按未建成保守处理，不再阻塞 Repair。
- 协议号 8 → 10；新增 `planning.locks`／`grants`／`lamps` 等持久字段，旧档缺字段按无路口/无锁定/无授权/无灯表加载。
- 验证：构建与 17 个测试类（含 25 项单元格校验）通过；人工验收阶段7 全部通过。C8 等封存项保持封存，已知延后项见进度文档。

## 0.1.6 — 2026-10-24

### 新增

- 普通路面、路灯和单级DOM泥土台阶改为随包提供的独立预切蓝图，运行时缓存加载，附离线导出/核对工具与稀疏掩码。
- 按A→B逐行累计上下坡高度，锚点行固定平路；镜像、灯柱、幽灵和范围共用高度模型。
- 原生施工按最新路面填充下方、清空上方非合法路灯格；已应用快照限定清理高度与拆除依据，当前A/B外旧路保留。
- 完成检查覆盖楼梯朝向/半部、DOM材质与旧高处清空；增加生成体积及世界高度限制。
- 魔棒幽灵的控制器上方显示固定指向A→B的金黄色直立实心箭头，尺寸为初版75%，不随玩家旋转。

### 修复

- 原生蓝图默认占位符被误当成空气，改为显式空气初始化，仅单独保留必要控制器预留格。
- 首次DRAFT缩路未刷新覆盖表计数，改为生成校验成功后同步过滤，越界未应用行直接丢弃且不提交已确认表。
- 行类型改变但edited数量相同时的预览去重、实际应用范围显示及清空坐标持久化。

### 文档与验证

- 更新版本、设计、README和方案状态；进度按项目/中版本/补丁/当前补丁bug维护，完成补丁即清理其bug与测试明细，规则写入AGENTS。
- 用户确认阶段6单机/联机及四项补修全部通过；无素材的旧数据测试子项删除，先前封存项目保持封存。
- 构建、590条Java断言与25个有效单元格导出校验通过；网络协议8，两端与其他联机玩家须同包。
- 阶段6完成，阶段7交叉口未开始；P2全部完成前不推送。

## 0.1.5 — 2026-10-24

### 新增

- Repair与首次建造共用受控原生建筑工具：精确校验本路蓝图、锚点和朝向，使用原生Complete/Pretty队列或Survival建筑工工单。
- 持久施工会话冻结目标，建成后才提交编辑和保存实际应用快照；保留取消、失权、断线及重启恢复语义。
- 原生建筑工拆除与创造快速拆除，创造模式经拆除按钮选择方式；野外向最近且有管理权限的殖民地派单，并限制建筑工距离。

### 修复

- Windows蓝图路径误拒绝、未授权控制器漏开空窗、DRAFT删除多余选项及取消工单缺少相关玩家通知。
- 草案换绑丢失A/B和幽灵；首次选择错误蓝图仍施工、完成状态与实际拆除依据不一致。
- 锚点错误与道路删除后旧施工窗口静默拒绝，补齐明确提示。

### 验证与范围

- 用户确认本轮R1–R5单机/联机复测全部通过；S8/S15/D10/N8按约定封存，未记为通过。
- 208条代码回归；最后失效窗口提示由用户明确免除游戏复测，建筑工重启消失仅检查并记录，不改公民恢复逻辑。
- 网络协议7，两端及所有联机玩家需同版；P2阶段5完成，台阶、交叉口仍未开始。不推送。

## 0.1.4-fix.1 — 2026-10-23

### 新增

- 共享状态准入、重规划继承、绑定查找、权限、工单识别、蓝图分类与建成收尾规则。
- 保存独立实际应用蓝图用于安全拆除；归档 71 条本地代码回归断言。
- 项目代理协作约定：方案确认、压缩恢复、人工验收与实时进度记录。

### 修复

- 首次调灯距/逐行编辑误推进状态、建成后灯距误锁、施工中接受编辑、空覆盖表越界过滤和重启误判。
- 控制器迁移影响其他道路魔杖，服务端规划/编辑门禁及工单匹配不一致。
- 预览残留、同位置新路继承旧预览、工具间过滤缓存污染和相邻规划仪切换不刷新。
- 未施工道路按待建蓝图误删地形；未知施工范围采用保守取消，已建道路按实际应用快照拆除。
- 幽灵、白框、索引 AABB 显示距离不一致，默认统一为 64 格，返回范围恢复缓存。

### 验证与范围

- 用户确认本轮修复全部验收通过；71 条代码回归通过。
- 多人专项仍按计划延后；P2 阶段 5（Repair 接原生工具）未开始。
- 网络协议 3；客户端和服务端须同版。显示距离可配置化仍为后续待办。
