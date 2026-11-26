# 更新记录

## 1.0.0 — 2026-11-26 正式版

### 新增

- **正式版号 1.0.0**：`mods.toml` 版本升到 1.0.0，存档格式自本版起冻结；同一大版本内的小版本沿用同一套道路数据。
- **模组图标**：jar 根目录 `icon.png`（128×128，道路清单书主体）通过 `[[mods]] logoFile` 显示在启动器与模组列表；商店头像使用同图的 512×512 版本。
- **发布说明**：整理 `RELEASE-1.0.0.md`（随发布单独分发，不入库），汇总玩法、依赖、安装与已知限制。

### 修复

- 文档与实装对齐：README 中英的版本号、网络协议（19）、分层净空（路面差 5 / 通行 3）更新到当前口径；「仅供预览」「存档仍不稳定」改为正式版表述。
- `CONTRIBUTING.md` 不再承担版本移植手册：重写为仓库说明（定位、目录、构建、测试、提交、文档地图）。

### 文档

- `docs/代码结构与机制.md` 新增附录 A「版本移植检查清单」与附录 B「修改机制时保持的边界」，承接原贡献文档的移植条目，并补上 Mixin 4 处与 `Accessor` 用法；删除过时的第 6 节（0.1.7 快照），其中仍有效的维护约定并入第 5 节，其余条目已由第 2/3 节或后续实现取代。
- 设计文档改名 `docs/阡陌纵横-设计文档-1.0.0.md`；进度、结构、README、AGENTS 的版本与链接同步。
- `docs/description-*.md`（商店与百科文案）不再纳入版本库，由发布流程单独维护。
- 验证：构建通过（`rp_crossroads-1.0.0.jar`，877,558 B／sha256 `A638CD5F6DC3…`）；`tests/run.ps1` 32 个程序 3772 条断言 + 单元导出校验全绿；两端与 dist 三处同哈希。相对 0.3.6 没有玩法改动，0.3.6 的 T46–T69 验收结论继续有效。

## 0.3.6 — 2026-11-26 交叉口形状变更

### 新增

- **建成道路改宽度或镜像改为「先拆后建」**（`road/RoadRebuild`）：判据对照世界真实持有的路面（`RoadBlueprintStore.appliedPlan`）而不是计划字段——新形状的 `width`、`walkRange` 或 `widthSide` 与已建成路面不一致就拆建，与有没有交叉无关。没有任何交叉的建成道路同样走这条路径，旧侧路面因此终于会被清掉（建造工单只写自己蓝图范围内的格，从不顺手清范围外）。同系列换档、同宽度换风格仍是普通 Repair。
- **拆建前先证明新形状站得住**：候选形状用 `RoadPlanBuilder` 重建、`RoadReplanning.inherit` 保留身份与端点，规划前**解锁全部锁定行**（旧平台钉住的高度不再约束新路），然后在交叉关系记录仍然存在的情况下跑一次自动规划，再逐邻路 `RoadCrossings.validate`：同层比平台高度，分层比「路面差 ≥5 与上下关系」。任一不过即拒绝并给原因，计划与世界都不变。
- **拆除半程带重建载荷**：新形状、已算好的行表与交叉关系意图（`layered/order/platform`）存进 `RoadRemovalSession.rebuild`，随拆卸存盘、重启可恢复。拆除完成后 `RoadRebuild.finish` 清掉 `applied` 快照、把形状与行表装回计划、按新足迹重算 `rect` 还原交叉关系、重算锁定行，然后派 BUILD 工单；离线或派单被拒时停在 `PLANNED` 并发布提案，等玩家自己点「修复」。

### 修复

- **路口高度覆盖报告成功却没清掉手改行**：确认覆盖时求解只在副本上进行，落地走 `copyProposalRows` → `setAutoType`，而它按设计拒绝对手改行动手，于是那几行原样留在活计划里，下次调高度又被判「与 N 行手动修改冲突」。现在确认覆盖后先按**已验证的那一级跨度**清掉活计划（含可编辑的邻路）的手改行，再复制求解结果；日志里的 `cleared N` 与实际丢弃行数一致。
- **同层改上跨后建造报「与该格原生施工目标冲突」**：分层路口的两张目标图不做同层那种逐格裁决，合并时「上层桥面的地基（实心填充）」撞上下层的空气就抛异常。地基是主路 1–2 级沙砾桥面的唯一支撑，必须赢过空气；`structuralAt` 也把「甲板正下方那一格」算作结构，下层不会再把它清掉。异常消息现在带双方方块状态，便于定位。
- **行等级提示显示在错误的位置**：上一版加到了动作栏消息里（快捷栏上方），玩家要的是**准星下方**那条自绘提示。已移到 `RoadAim` / `onRenderAimLabel`：`第 N 行 · 类型 → 下一档（自动/手改/路口固定）`。
- **手改行取消不掉，覆盖等于没覆盖**：两处「自动标回手改」的逻辑把唯一的取消入口堵死了——覆盖成功后会把「求解结果与手改一致」的行恢复成 `MANUAL`（手改通常就是把坡改平，自动解也平，于是几乎全被恢复，下次改高度又得确认一次）；`initialize` 在 `sampleKey` 为空时又把「提案与已建的类型差」逐行 `editRow`，按地形重绘清完立刻复活。两处都已删除：手改行只由玩家手改产生，程序自动解算一律不再标回。
- **上跨路口锁死，修复只报「内部错误」**：净空从 3 提到 5 之后，旧几何过不了预检，而修复只验不算、没有任何入口能抬升；玩家剩下的唯一出路是把整条路拆了重铺，而关系记录还在，重建仍会撞同一道检查。新增 `road/RoadClearanceHeal`：派单前若分层邻路的实际路面差不足，就在副本上清掉残留的显式高度并强制重解，够到 5 格才替换活计划并发布（必要时清掉挡路的手改行并说明），够不到就原样放弃。同时 `RoadReasons` 里两条净空原因由精确匹配改为前缀（文案已带数字，精确匹配永远不中，提示因此降级成「内部错误」）。
- **上跨桥面会整段塌落，工单永远收不了尾**：装饰器为了让净空成立，把上层桥面以下的自身填充全部清成空气，而主路 1–2 级的路面是**沙砾**（重力方块）——桥面下没有支撑就塌，建造/Repair 也就永远完成不了。现在紧贴桥面下方的那一格保留地基，其余填充照旧清空；同时把分层净空从「路面差 3」（实际只有 2 格能走）提到「路面差 5」（**3 格通行净空** + 桥面地基 + 桥面），规划、校验、无权严格避让四处的常量统一为 `RoadCrossings.LAYERED_SEPARATION / WALKING_CLEARANCE`。已建成的旧 3 格路口会在预检里报净空不足，需要重新规划高度——那正是它塌落的原因。
- **幽灵「不更新」其实是每秒被重建两次**：客户端把 `planning.revision` 放进了预览 key，而 revision 会因 planner、issue、refreshNeeded 这类**看不见的书签**变化而增加；预览路径又按 revision 差异给该玩家补发一次道路同步——于是同步 → key 变 → 请求 → 重建 → 再同步，实测 14:40:57–58 之间 revision 连续 132→142、幽灵被清掉重建十余次。现在签名只取形状（行类型 + 逐行路面高度 + 端点/朝向/镜像/宽度/灯距 + 交叉关系投影），预览路径只在**面板文字**（issue / crossingIssue）变化时才同步。
- **交叉口的铺装换成了「谁都没用过」的材质**：同层路口的地面格由两条路共同写入、又不属于任何一条路自己的路面单元，铺装只能从主导路的单元里取一格。原来只取了 `BlockState`，而 Domum 的方块把材质存在**方块实体**里（`textureData`），于是路口格成了「同一个方块、没有材质数据」，游戏按默认色渲染——存档里松涛路与望江路路口的 `domum_ornamentum:dark_brick` 就是这样变成了红陶瓦砖，而它不在任何一级单元里。现在 `junctionPavement` 返回状态 + 该单元的方块实体，用 `writeCell` 整格写入；`sharedCell` 在「同一方块、只有一侧带实体」时保留带实体的一侧，合并次序不能再决定材质。
- **幽灵刷新增加了可诊断性**：客户端每次请求打一行 `Road preview request`（含 revision 与 key 摘要），被丢弃的回包打 `Road preview dropped`（含序期望/实际），服务端发布后打 `Planning action … published` / `Junction height … published`。此前"幽灵没变"与"根本没请求"在日志里长得一样。
- **合并面板漏掉了「按地形重绘」**：0.3.1 删掉绘制器面板时，服务端的「清手工行 + 按地形重采样」（规划 action 1）留下了，入口只剩交叉口窗那一个按钮，控制器面板上没有。现在面板底部补一整宽行「按地形重绘」（`REDRAW`，协议 19），端点取自计划自身，不要求手上有绘制器或当前选中 A/B。同一句文案也替换了交叉口窗里原来的「清除手工锁并重算」，两个入口说的是同一件事。
- **被手工行挡住的切换，现在真的能覆盖**：原来的覆盖只统计**本路**跨度内的手改行，挡住的是邻路手改时计数为 0，于是连「再点一次覆盖」的提示都不给，直接报「其余两种关系均无法实现」。现在两侧都计（邻路需可编辑），并把一次确认的覆盖做成两级跨度：先清路口两侧最近锁定行之间的手改行，不够再清全路；**真解通的那一级**才写进 60 秒的确认记录，第二次按同一级重做，所以丢的行不会比第一次解释的多。高度 ± 走同一套阶梯。
- **「清空手改仍不可行」不再冒充手改的错**：两级都清空还解不通时，求解器那句「在现有行数和手动改动下无法连通固定高度」会把玩家引去删根本没挡路的手改行。此时另发一条「已清除手工改动后仍无法实现：<真实原因>」，指向长度、等级或路口位置。
- **交叉口窗丢包不再静默**：无 A/B 时直接 `return`，表现得像按钮坏了；现在给动作栏提示。
- **交叉口处换系列不再一律被拒**：`Network.handleSetRoadStyle` 删掉无条件的 `style.crossing` 拦截。足迹不变的切换（换档、同宽度换风格）恢复走 Repair，共享格由 `sharedCell` 逐格协调；足迹变化的切换转拆建。
- **镜像来回切换会让存盘蓝图的基准逐次漂移**：`BlueprintAnchor.bind` 决定是否平移帧时读的是**副本**上记录的 primary，而修复目标、联合模型、重建候选都可能是锚点移动之前拍下的副本；同一锚点移动因此被算两次，两份蓝图文件的 `primary_offset` 被多推一格，之后所有解读（幽灵、拆除、施工）整体平移一格。现在 `bind` 先与管理者持有的实时计划对齐再决定。另加 `RoadBlueprintStore.resyncFrame`：用「蓝图尺寸必须等于 applied 模型的足迹」作独立校验，不成立就整份平移校正，在世界载入与每次拆除前各跑一次，已经漂移的存档会自愈并留一条 WARN。
- **拆除完成后收不了尾**：拆除蓝图交给工单时没有带包身份，原生 `AbstractWorkOrder.onCompleted` 会把「包名」写进图里装饰控制器的方块实体，`packName` 为 null 时在 `COMPLETE_BUILD` 抛 `NullPointerException`，建筑工被暂停重试、工单永远完不成，计划一直停在 `REMOVING`。现在拆除前会把主格掩成占位（顺带清掉那个方块实体，拆除不再把自身路径写进幸存的控制器），并统一走 `RoadBlueprintIdentity.stamp` 补上包名/目录/文件名；构建与拆除共用同一个助手。
- **拆除工单曾经整体平移，把道路拆错位置**：拆除蓝图掩码写格会 `addBlockState` → 清掉 primary 偏移缓存，而蓝图文件不存这个值，原生流程「工单位置 − 偏移」失去依据后回退到**蓝图中心**，整次拆除按 `偏移 − 中心` 平移（实测一条 23×9×4 的街道偏了 4 格、向上 3 格）。现在掩码内所有写格统一走 `RoadCrossings.mask`，整次掩码最后重设 `setCachePrimaryOffset`；重建半程也不再掩码控制器与 primary 格（控制器本身就是 `IBuilderUndestroyable`，且它是图里唯一能推出偏移的方块）。掩码的帧统一取实时计划的 `blueprintAnchor`，不再用镜像后会过期的 applied 快照。
- **共享格保护先落盘、再释放交叉关系**：`RoadNativeRemoval.begin` 在带重建载荷时用 `removalMask(…, keepReserved)` 额外保护本路控制器、锚点与 primary 三格，等拆除蓝图写盘并进包之后才 `RoadJunctions.removeRoad`。反过来的话，邻路在共享格里的路面会被当成本路的路面一起拆掉。释放只删记录、不重排邻路提案。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.6.md`：§2.6 与 §3.8 的「有交叉一律拒绝」改为按足迹分流，新增 §3.9「建成道路的形状变更：先拆后建」。
- `docs/代码结构与机制.md`：新增 `road/RoadRebuild` 条目与 §2.15 拆建调用链，§2.10 补拆除半程与完成分流，逐文件条目更新 `RoadNativeRemoval`、`RoadCrossings`、`RoadBlueprintStore`、`RoadRemovalSession`、`RoadRepair`、`RoadReasons`、`RoadPlanningActions`、`WindowRoadController`、`RoadControllerActionPayload`；回归表增至 32 程序 3772 条。
- 设计文档 §3.3 面板按钮清单补「按地形重绘」与 248×169 新尺寸，§2.5「手改行挡住路口」改为两级跨度口径，并写明「清空手改仍失败」时的独立提示。
- README 中英：补「改宽度或镜像会先拆除再重建」一段与当前限制。
- `output/P4/交叉口切换/开发报告-0.3.6.md` 重写为一份完整报告：功能本体（判定口径、两段工单、预检）、验收期 8 轮修复（每轮现象/根因/修法）、验证与部署、T46–T69 验收清单、取舍与复盘；不再按轮次追加。
- 验证：构建通过（产物 `877,699 B` / sha256 `824C307E3972…` / md5 `94a162863d0b06c0ef2cad9ec340b22b`，协议 19）；`tests/run.ps1` 32 个程序 3772 条断言 + `export_road_units.py --verify`（21 份单元）全绿；`check_lang.py` 295/295；三处同哈希部署。游戏内 T46–T69 待人工验收。

## 0.3.5 — 2026-11-24 道路名称库

### 新增

- **`road/NameBook`**：道路名词表从代码里搬到数据。jar 内 `assets/rp_crossroads/roadnames/default.json` 提供中英各 5 类共 60 个专名、通名权重、方位与序号；服务端把 `config/rp_crossroads/road_names.json` 放好即**整份替换**（不做追加合并），文件缺失用随包词表，校验失败整份作废、回退随包并只 `WARN` 一次。两种语言必须同时通过校验，半份词表不会被接受。
- **组合规则**：专名类别等概率抽取（不跨类拼接），形态为「专名+通名 70% / 方位+专名+通名 20% / 序号+通名 10%」；中文通名只保留路 55 / 街 30 / 大道 15，英文 Street 30 / Avenue 20 / Road 20 / Lane 10 / Drive 8 / Court 4 / Place 4 / Way 4。权重为 0 合法、全 0 退等权；缺方位或序号表时该形态自动退化。
- **服务端启动加载**：`NameBook` 在 `ServerStartedEvent` 重读一次；改词表需重启（`/reload` 不重载）。

### 修复

- 名称生成不再依赖两张硬编码表：去重 32 次后改用编号形态（中文 `N号街`、英文 `Street N`），与词表里的 `第三街` 语义区分；生成的 base 钳到中文 12 字 / 英文 40 字符，再走既有的「去数字 + 4 位字母 id」文件名兜底。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.5.md`：§6.2 短名规则改为 `<道路名>-<4位字母><档位>`，新增 §6.4「名称库」（结构与覆盖、权重、形态、去重、长度、加载时机、旧存档）。
- `docs/代码结构与机制.md`：新增 `NameBook` 条目、`RoadNames` 职责改写、`roadnames/default.json` 资源行、回归表（29 程序 3646 条）与基线。
- README 中英：补「道路名由可覆盖的词表生成」一段。
- 一并说明：曾实现的「寻路标签数据包」在验收期整套回退（现有台阶方块本就在 `#minecraft:stairs`，整方块台阶吃跳跃惩罚是原版预期），未进入本版本；参与过该实验的存档可删除 `<存档>/datapacks/rp_crossroads_paths/`。
- 验证：构建通过（产物 `859,891 B` / sha256 `71EB6BC826C7…`）；`tests/run.ps1` 29 个程序 3646 条断言 + `export_road_units.py --verify`（21 份单元）全绿；三处同哈希部署。游戏内 T41–T45 全部通过（2026-11-25）。

## 0.3.4 — 2026-11-24 原生等级列表

### 新增

- **建造等级改由原生工具选择**：每档生成一份蓝图，文件名 `<base><档位>.blueprint`；主格（`blueprintAnchor`）写 `minecolonies:decorationcontroller` 与其方块实体 `blueprintDataProvider.schematicName = <base><档位>`。原生工具读该方块实体的数字得到等级、按「文件名去掉该数字」分组，于是列表里只有一个条目，展开就是 `Level: 1..N`（街道 3 级、主路 4 级）。
- **`base` 规则**：路名去掉所有数字与非法字符（≤32，空则 `road`）后接 4 位**字母** id 标签（hex 0–9→a–j、a–f→k–p，唯一性与旧 `<短名>-<id4>` 等价）。数字必须只剩档位一个：`getLevel` 会把名字里的所有数字拼起来解析，分组也靠删掉那个数字。档位上限 9，超出直接拒绝。
- **确认按所选档落地**：`RoadRepair.confirm` 从 `payload.path()` 解析 `base + 档位`，校验 `base` 属于本会话、档位在 1..该系列上限、路径在本路 `generated/` 目录内；通过后写 `plan.unitLevel`、按该档重新准备（成对授权回执绑的是该档的共享格）、保存待建快照并删掉其余档位文件。
- **协议 17 → 18**：`OpenBuildToolPayload` 增加 `tiers`（路径 → 每档蓝图 NBT），客户端一次写完全部档位再开窗；预选与本地校验按「同一 base」匹配，玩家选的档位原样回传。

### 修复

- **主格不再写占位符**：生成图在该格写装饰控制器本身（等级锚），`RoadProtectedIterator`（建筑工）与 `RoadProtectedStructurePlacer`（创造队列）的跳过判定加上装饰控制器；`blocksolidsubstitution`（地形填充）不受影响，仍照常放置。建成后控制器照旧迁入该格。
- **面板不再询问等级**：`WindowRoadTier` 窗口与其布局、两个文案键删除；面板「建造」直接 `ConfigureRoadPayload(PLAN)`。`ConfigureRoadPayload.tier` 字段保留但恒 -1，服务端不再用它决定等级。
- **工单匹配按路而非按档**：`RoadWorkOrders.matches` 与客户端授权名单改用「同一条路」的口径，升级/重选后旧工单仍能对应到本路。
- **建成后改镜像，红框与蓝图不再错位**：`BlueprintAnchor.decide` 去掉「非 DRAFT 冻结」——primary 改为恒等于**当前几何**的预留格，记录值不同就按 delta 平移已存快照并重绑。原先镜像把路体翻到锚点另一侧后，primary 留在旧格：生成图的控制器格越界被静默跳过，联合工作体的包围盒被拉到旧控制器那一列、整片填成 `blockSubstitution`，完成校验与拆除范围也跟着错。`shiftFrame` 写不进时整次生成拒绝并提示 `rp_crossroads.reason.frame_move_failed`，不再产出坏图。
- **升级建成的道路导致建筑工被暂停**：`RoadBuilderDispatch` 交给工单的是内存工作体，`packName` 为空，而原生 `AbstractWorkOrder.onCompleted`（`AbstractWorkOrder.java:821`）会把蓝图的包名写进主格的装饰控制器方块实体——升级时该格已有控制器，于是每次 `COMPLETE_BUILD` 都抛 NPE、市民暂停 100 秒后重试、工单收不了尾。现在补上 `packName` / `filePath`（`generated`）/ `fileName`。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.4.md`：§4 建造流程改为按档发布/下发，新增 §4.1「建造等级在原生工具里选」（文件名、主格方块实体、上限、确认与清理）；删除 `WindowRoadTier` 相关条目。
- `docs/代码结构与机制.md`：`RoadNames`/`RoadTemporaryPack`/`RoadBlueprintGenerator`/`RoadRepair`/`RoadRepairSelection`/`RoadWorkOrders`/`RoadProtectedIterator`/`RoadProtectedStructurePlacer`/`RoadBlueprintAnchor`/`RoadBuilderDispatch`/`OpenBuildToolPayload`/`ClientRoadPackWriter`/`WindowRoadRepairBuildTool` 职责与协议号、回归表（28 程序 3396 条）与基线。
- README 中英：建造等级改为在工具里选 `Level: 1..N`。
- 验证：构建通过（产物 `850,266 B` / sha256 `7FADC16C0577…`）；`tests/run.ps1` 28 个程序 3396 条断言 + `export_road_units.py --verify`（21 份单元）全绿；三处同哈希部署。游戏内 T31–T40 全部通过（2026-11-24）。

## 0.3.3 — 2026-11-23 蓝图锚点归属

### 新增

- **`RoadPlan.blueprintAnchor`**（可空，NBT 键 `blueprintAnchor`）：蓝图 primary 格的世界坐标。缺省时 primary 仍取 `anchorPos`，旧存档与红框位置逐字不变；新计划在首次生成蓝图时写入控制器预留格，红框随之内移一格。读取口径 `BlueprintCells.blueprintAnchor(plan)` 与 `reservedCell(plan,pos)`（锚点 / 控制器 / primary 三格）各只有一处实现。
- **`road/BlueprintAnchor`**：primary 的认领与惰性迁移。首次绑定按 `新预留格 − anchorPos` 平移已存蓝图偏移；DRAFT 期间随几何重算（镜像换边），离开 DRAFT 后冻结；重写失败整体放弃。生成跑在会话副本上（`RoadRepairSession.target()` 每次返回新副本），绑定结果同步写回同 id 的活跃计划。
- **协议 16 → 17**：`OpenBuildToolPayload.anchorPos` 的含义由「计划锚点」改为「蓝图 primary 格」，字段不变。

### 修复

- **红框落在锚点方块上**：生成图与全部派生图（联合图、交叉口图、拆除图、幽灵投影）的 `primaryOffset` 改为 `blueprintAnchor − 蓝图最小角`，红框落到控制器预留格；工单/创造放置/拆除工单的锚点、预览位置、确认校验同步改用该值。旧路在第一次重新生成时惰性迁移，世界里的方块不动。
- **基准框搬家只搬锚点**：`RoadPlan.reanchorTo` 现在同时移动 `anchorPos`、`blueprintAnchor` 与 `appliedGeometry` 里的两个锚点；`RoadBlueprintStore.shiftFrame` 改为先读齐再写、任一份失败回滚，失败时迁移把基准框搬回原位并放弃本次搬迁。
- **完成校验与清理口径**：`isBuilt` 与 `clearTargets` 统一跳过 `reservedCell` 三格，占位格不再参与“是否建成”的比对。
- **建成后看不见幽灵、灯距/镜像无反应**：控制器搬家（建成收尾）复用了 `BindRoadWandPayload`，客户端把它当成用户重绑，连同 A/B 与镜像一起清掉；`bindTo` 只在道路切换时播种，于是同一条路再也出不了幽灵，面板的灯距/镜像也停在「请先选端点」。现在绑定回包只重绑物品 NBT、离开地形模式，再用 `RoadSelectionClient.reloadFromRoad` 从该道路重载 A/B；切模式也按同一口径——进挖/填清空，切回道路模式从道路重载。
- **地形模式下可能冒出道路幽灵**：`ClientRoadPreview.plan()` 补 `TerrainClientState.isTerrain()` 门限，挖/填的两个角不再被当成 A/B 发预览请求。

### 文档

- 设计文档随补丁改名 `docs/阡陌纵横-设计文档-0.3.3.md`：§2.4 补「预留格即 primary」、§3.4 补字段、§3.5 改为四件套搬迁、§5 补 origin 口径与惰性迁移规则、通道版本号更正为 17。
- `docs/代码结构与机制.md`：新增 `BlueprintAnchor` 文件条目，`BlueprintCells`/`RoadBlueprintStore`/`RoadGenerator`/`RoadControllerMigration`/`RoadPlan`/`RoadRepair`/`RoadNativeBuildTool`/`RoadBuilderDispatch`/`RoadNativeRemoval` 职责与 §2.3/§2.8/§2.9 调用链同步；回归表增 `AnchorOwnershipRegression`（31 条），总数 27 程序 3416 条，基线 JAR 与协议更新。
- README 中英：说明红框位置（新路在控制器预留格，旧路首次 Repair/升级时内移一格）。
- 验证：构建通过（产物 846,023 / sha256 `AC62167DA070…`）；`tests/run.ps1` 27 个程序 3416 条断言 + `export_road_units.py --verify`（21 份单元）全绿；三处同哈希部署。游戏内 T25–T31（含红框位置、旧路迁移、交叉口、拆除与建成后的幽灵/面板）全部通过（2026-11-23）。

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
