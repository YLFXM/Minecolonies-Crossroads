# 模拟殖民地：重构计划 / MineColonies: Reconstruction Plan

> 当前版本0.1.6、网络协议8。阶段6预切单元、真实台阶及补修已通过用户单机/联机验收；P2完成6/7，阶段7交叉口未开始。客户端、服务器及其他联机玩家须同包。

> [!WARNING]
> **本项目仍在开发中（in development），尚未完成测试，请勿下载安装到正式存档或正式服务器。**
>
> 当前开发版本 `0.1.6`：原生施工、拆除及阶段6台阶已验收，交叉口尚未实现。
> 存档格式与内部数据结构**仍可能变动**。如果你不是打算参与开发或帮忙测试，请等正式发布。
>
> 仓库以 GPL-3.0 开源，**原作者可能不再维护**。愿意接手迁移到更新版本的开发者，
> 请看 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

MineColonies 的功能扩展 mod：用 Road Wand 划出**轴向直路**，生成**严格按道路宽度**的蓝图，
交给**原生建筑工具**建造，建成后把装饰控制器迁移到道路内侧，并持久化为可修复、可拆除的道路实体。

| 项 | 值 |
|---|---|
| Mod ID | `mcreconstruction` |
| 包名 | `com.YLFXM.mcreconstruction` |
| 版本 | `0.1.6`（开发中） |
| 定位 | MineColonies 附属扩展，**不是** Pause Research 的更新，不复用其 mod id / 包名 / 配置路径 / 合成 path 前缀 |

## 它解决什么问题

MineColonies 擅长造房子，但不擅长造**路**——而路恰恰是殖民地一切布局的骨架。这个 mod 补上这一块，
并且**不替换** MineColonies 已有的任何东西。

用 **Road Wand** 选两个点 A、B，mod 算出两点之间严格宽度的道路，生成一份真正的 Structurize 蓝图，
然后打开使用原生布局的建造工具（Complete / Pretty / Survival）。等道路真的在世界里立起来之后，
mod 把装饰控制器搬到车行道内侧，把道路绑定到殖民地，并从此持续看管它。

玩家接触到的每一个环节都是 MineColonies 的居民、MineColonies 的工单、原生的 Structurize 蓝图。
没有第二套建造系统，没有平行经济，没有假装自己是道路的自定义方块。

## 功能一览

| 物品 | ID | 用途 |
|---|---|---|
| Road Wand | `mcreconstruction:road_wand` | 选点 A/B、实时预览、生成蓝图、打开原生建造工具 |
| Road Surveyor | `mcreconstruction:road_surveyor` | 绑定市政厅后查看该殖民地道路，逐行权限判定，多选批量释放/拆除 |
| Work Order Scroll | `mcreconstruction:work_order_scroll` | 绑定市政厅后查看该殖民地工单，多选取消；道路工单显示道路名 |

**三种受控原生建造方式**：Complete / Pretty 由 Structurize 执行；**Survival** 会把道路
变成殖民地工作管理器里一张**真正的工单**，由你自己的建筑工承接，用的是和原版同一套
`WorkOrderDecoration` API 与同一套 `MANAGE_HUTS` 权限校验。

创造模式物品栏里有独立分页 **「模拟殖民地：重构计划」**（`itemGroup.mcreconstruction`），
方便测试时直接取用。

## 设计理念

**扩展，而不是替换。** 这个 mod 触碰的每一套机制都属于别人：建造工具是 Structurize 的，工单是
MineColonies 的，权限系统是 MineColonies 的，控制器就是原版装饰控制器。本 mod 贡献的是**道路本身**
——几何、蓝图、持久化与生命周期。基座 mod 改了行为，本 mod 会跟着改，而不是 fork 一份。

**原生，而不是另造一套。** MineColonies 玩家不该学第二套界面语言。建造流程是原版建造工具，
卷轴用起来像原版资源卷轴，控制器就是原版控制器，权限就是原版权限。

**权限要正确，也要安静。** 殖民地里的权限关系很敏感。本 mod 原样复用原版规则、不放宽，
每次操作服务端都复核，拒绝时用**置灰控件**表达，而不是对玩家大声嚷嚷。

**数据要经得起折腾。** 修一条路代价很高。蓝图保留到玩家明确删除为止；重新规划是原地覆盖而不是
到处堆文件；拆除是一次完整的操作，而不是留下一地半删状态。

**幂等性是硬约束。** 控制器迁移永远是**先放置并回读验证、再删旧的**；"是否已迁移"的判断
只认**首选**目标格。这两条任何一条放松，都会导致经典的"控制器原地不动"或"控制器凭空消失"。

## 许可与开源

本项目以 **GNU GPL v3.0** 开源，见 [`LICENSE`](LICENSE)。

选择 GPL-3.0 而不是 MIT，是因为上游依赖全部是 GPL-3.0（MineColonies / Structurize / BlockUI），
本项目是它们的衍生扩展，必须保持同一许可。所有 `.java` 源文件顶部都有 GPL 文件头。

修改版请保留本许可与原作者署名，并注明你的改动。

### 接手维护

本项目处于**开发中**，原作者可能不再维护。如果你愿意接手、或想把它迁移到更新的 Minecraft 版本，
请先读 [`CONTRIBUTING.md`](CONTRIBUTING.md)——里面列出了最容易在版本升级时静默失效的
几个点（结构包 immutable 标记、结构包只同步一次、原生工具预选的反射调用、
BlockUI 的 `Window` → `BOWindow` 改名、`AbstractTextElement` 的颜色行为、
原版权限的绕过规则、控制器迁移的幂等判断、全量完成判定），
以及十五条不可动摇的开发约定。

也可以直接在仓库开 issue，勾选 **「迁移 / 版本移植」** 模板说明你的目标版本。

### 致谢

- **MineColonies**（`ldtteam/minecolonies`）—— 本 mod 的基础，工单系统、权限系统、装饰控制器、
  居民与建筑工机制全部来自它。
- **Structurize**（`ldtteam/structurize`）—— 蓝图与建造工具，本 mod 生成的蓝图直接交给它处理。
- **BlockUI**（`ldtteam/blockui`）—— 四个窗口的 UI 框架。
- **Domum Ornamentum** —— 风格包中的方块。

本项目是站在这些项目肩膀上的扩展，没有复制它们的代码，但思路、约定与一部分踩坑经验来自它们。

## 环境要求

| 依赖 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.259 |
| Java | 21 |
| MineColonies | 1.1.1403-1.21.1 |
| Structurize | 1.0.835 |
| BlockUI | 1.0.212 |
| Domum Ornamentum | 1.0.236 |

以上全部为必需依赖，本 mod 是附属扩展，**不能独立运行**。

`RoadManager` 与 `RoadPlan` 都按维度分档，多维度理论上可用，但只在主世界做过烟测。

## 玩家手册

### 合成

三件物品都在工作台上合成，中间都要放一个 **Structurize 的建筑工具**（`structurize:sceptergold`）。

**Road Wand** —— 两种镜像摆法等价：

```
铁锭 ·  ·         ·  ·  铁锭
 ·  权杖  ·   /   ·  权杖  ·
 ·   ·  木棍      木棍 ·  ·
```

**Road Surveyor** —— 同原版剪贴板布局，材料用纸：

```
木棍  权杖  木棍
木棍   纸   木棍
木棍  木棍  木棍
```

**Colony Work Order Scroll** —— 同原版资源卷轴布局，材料用纸：

```
木棍  权杖  木棍
木棍   纸   木棍
木棍   纸   木棍
```

### 建路流程

1. 放置原生 `minecolonies:decorationcontroller`，让它的 `FACING` 贴着作为锚点的方块（默认 `minecraft:stone_bricks`）。
2. 右键控制器（不潜行）→ 打开控制器面板；此时 `DecorationControllerHandler` 已经为该控制器建立了 `RoadPlan`，并按 `<归属前缀>-<道路名>` 生成唯一名称。前缀取自控制器所在位置：落在殖民地境内是殖民地名，落在野外是 **`野外`**（英文客户端 `Wilderness`）。
3. 手持 Road Wand：
   - **左键**或 **Shift + 左键**选点，点在哪一端就记哪一端（按锚点分侧归位，见[端点归位](#端点归位点击顺序无关)）；
   - A/B 必须共享 x 或 z 之一；
   - **高度只由锚点决定**：路面 = 锚点方块下一格。A/B 的 Y 完全不参与计算。
4. 选点后出现**真实方块蓝图幽灵预览**，范围就是严格道路宽度；索引 AABB 让位给幽灵。
5. **右键空气** → 打开 Wand 面板：`Create Blueprint / Open Build Tool`、`Mirror`、`Lamp spacing -/+`、`Clear`。宽度不可编辑（来自风格包），Mirror 绕锚点列翻转路面并实时重算预览，灯距 1-8 点一下即生效。
   `Create Blueprint` **只在 `DRAFT` 可用**：确认过规划之后，重新应用形状的唯一途径是 Repair。
6. 点 `Create Blueprint` 后服务端：校验 A/B → 构造 `RoadPlan` → 生成严格宽度蓝图 → 写入结构包 → 切包并打开原生 `WindowExtendedBuildTool`（列表定位到 `generated/`，蓝图由玩家手动点选）。
7. 在原生工具里选 **Complete / Pretty / Survival**（或 **Paste**）。
8. `RoadBuildWatcher` 检测到全部道路方块就位后：迁移控制器到锚点内侧、状态置 `BUILT`、Wand 自动重绑、蓝图保留。

> 打开过原生工具却没有建造就关掉窗口时，路会退回 `DRAFT`，`Create Blueprint` 重新可用——
> 否则这条路唯一的一次确认机会就被一个空窗口用掉了。

### 逐格编辑与重规划（0.1.4）

建成或规划好的路可以逐行改单元类型（平地 / 上坡 / 下坡），改完用 Repair 应用：

1. 右键控制器 → 面板里打开 **`Edit Blueprint: ON`**（开关按道路保存，随存档持久化）；
2. **Shift + 右键路面**任意一格 → 该**行**在 `FLAT → STAIR_UP → STAIR_DOWN → FLAT` 之间循环，动作栏报 `Row <along>: <type>`；
   - 台阶作用在**整条可走宽度**上，所以点这一行的任何一列都等价；
3. 重新选 A/B（沿轴伸缩）时，面板第二行会显示 `N edited`（未应用的改动行数）与 `M dropped`（被缩掉的行数），
   聊天栏同步报 `Re-plan: N row(s) edited, M dropped. Repair applies it.`；
4. 点控制器面板的 **Repair** → 打开原生工具；确认施工并建成后，冻结改动才真正写进覆盖表；
   完成后开关自动关闭，面板回到 `0 edited`。

> 只缩不动：`0 edited · 1 dropped` 是正常结果——没有哪一行的类型变了，只是丢了几行。
> 0.1.4 的覆盖表只存不用（外观仍是平地），台阶外观属于 0.1.6。

### 四种建造方式

| 方式 | 谁执行 | 材料 | 控制器迁移 |
|---|---|---|---|
| Complete | Structurize 即时放置 | 无 | 完成检测后迁移 |
| Pretty | Structurize 先清体积再放置 | 无 | 完成检测后迁移 |
| Paste | Structurize 从剪贴板粘贴 | 无 | 完成检测后迁移，另有 20 s 守护补回 |
| Survival | 殖民地建筑工按工单建造 | 需要 | 工单期间持续监视，建成后迁移 |

创意模式下，本 mod 的面板也有自己的即时建造分支（`Config.creativeInstantBuild`），完成后直接迁移。

### 拆除 / 修复 / 批量

- **控制器的建筑工拆除**：按最近实际应用蓝图发原生 REMOVE 工单；建筑工逐块挖掘和回收，实际完成后才清控制器、锚点和道路记录。拆除过程中不能编辑或 Repair，取消保留记录与暂存编辑；普通替换方块也可能被原生建筑工拆掉。
- **创造快速拆除**：主面板只保留拆除按钮，创造玩家点击后在二级窗口选择建筑工或 Structurize 原生扫描工具删除队列；后者无掉落、分批执行。生存直接派建筑工工单；规划仪采用相同交互，取消选择不会拆除。
- **野外建筑工拆除**：派往同维度最近殖民地，需管理权限及已配居民的1级以上建筑工；小屋到全部目标的距离默认不超过256格，可用 wildernessWorkerMaxDistance 调整。无付费雇佣和瞬移；超限/无权限时保留道路。
- **控制器的 Repair（0.1.5，已验收）**：DRAFT 禁用；PLANNED/BUILT 无其他会话时，点击后准备冻结蓝图并打开原生建筑工具，需背包中有建筑工具，无需额外绑定 Wand。选择当前道路、原锚点和原朝向，按 Complete / Pretty / Survival 执行；成功后才提交覆盖表、清 edited/dropped、关闭编辑开关并更新实际应用快照。未确认关窗或失败保留未应用编辑；已确认放置在关窗后继续。施工中禁止编辑或重复 Repair。填充与材料交原生系统。
- **Surveyor**：和工单卷轴一样**必须绑定市政厅**——`Shift + 右键市政厅` 绑定（位置写进物品 NBT），普通右键（方块或空气）打开该殖民地的道路列表；未绑定则打开空列表并提示绑定。列表显示道路名、状态、长度、控制器位置，可多选后 `Release Plans`（清计划/控制器/锚点，保留方块）或 `Remove Roads`（连方块一起清）。**逐行判定权限**：无权管理的道路仍然列出，但勾选框显示 `[-]` 且不可点、行文字变灰；`Select All` 也只选有权限的条目。
- **WorkOrder Scroll**：仿原版资源卷轴，**必须绑定市政厅**：
  - `Shift + 右键市政厅` 绑定（位置写进物品 NBT，`stacksTo(1)`）；
  - 绑定后普通右键（方块或空气）打开该殖民地的工单列表，需要该殖民地的 **`ACCESS_HUTS`**（默认领主/官员/好友）；无权限时灰字提示且不开窗；
  - 未绑定 → 打开空列表并在标题栏提示 `Not bound to a town hall - shift + right-click one to bind`；
  - 支持 `Select All` / `Clear` / `Cancel Selected Orders`，取消需要该殖民地的 **manage-huts** 权限；取消后服务端回推新列表，窗口原地刷新。
  - 工单状态列用 `order.getStage()` 拼装，**未认领或尚未进入建造阶段的单子 stage 为 null**，必须判空；单条工单读取失败只跳过该条，不能让整张列表失败。
- **控制器面板 Upgrade**：按钮存在但禁用，道路还没有等级/升级语义。

### 权限

所有「会改变殖民地」的操作都走 `RoadPermissions.canManage`，**只有两条规则，完全复用原版判定**：

1. **位置不被任何殖民地认领** → 放行。判定用 `getColonyByPosFromWorld`，它查的是**区块归属**（`ColonyUtils.getOwningColony(chunk)`），所以野外建路不受限制。
2. **殖民地 `Action.MANAGE_HUTS`** → 放行。

第二条直接调用 `Permissions#hasPermission(Player, Action)`，它自己就包含了原版的绕过：

```java
if (hasPermission(getRank(player), action)) return true;
else if (player.hasPermissions(permissioneventbypassminpermlevel /* 服务端配置，默认 2 */)
         && player.isCreative()) {
    return hasPermission(OP_RANK, action);
}
return false;
```

即**创造模式 + 权限等级 ≥ 2** 才绕过，两个条件缺一不可。

> **不要额外加"OP 直接放行"这类绕过。** 原版强制要求创造模式是有道理的：OP 也可能在正常玩生存，不该因为挂了个 OP 就能在别人的殖民地里动工。这一条此前试过、已回退。

**权限不足时按钮置灰，不弹自定义提示。** 殖民地权限系统本身会向玩家报告拒绝（原生提示已经足够），所以在我们的窗口里改成按权限禁用按钮：

| 窗口 | 按钮 | 启用条件 |
|---|---|---|
| 工单卷轴 | `Cancel Selected Orders` | 已绑定市政厅且对该殖民地有 manage-huts |
| 控制器面板 | `Repair` / `Remove` | 该道路所在殖民地的 manage-huts（`Upgrade` 始终禁用） |
| 道路勘测仪 | 每条道路的勾选框 | 该道路所在殖民地有 manage-huts 才可勾选（`[-]` 表示无权限，行文字变灰） |
| 道路勘测仪 | `Release Plans` / `Remove Roads` | 选中项里**至少有一条**可管理 |
| 道路魔杖 | `Create Blueprint` / `Mirror` | 绑定的道路可管理（`Clear` 不受限） |

判定依据是随数据一起下发的权限标记：`RoadSyncPayload` 里多一个 `manageable`（UUID 字符串列表），`WorkOrderListPayload` 多一个 `canManage`。**这些只用于界面**，服务端每次操作仍然重新校验。

右键控制器被拒时**不取消事件**，让 MineColonies 自己的处理器走原生流程和提示；其余入口只写 `LOGGER.debug`。

> **BlockUI 的坑**：XML 里只写 `color="black"` 时，`AbstractTextElement` 会把 enabled / disabled / hover 三种颜色**都设成同一个值**（`setColors(color)` → `setColors(color, color, color)`），于是 `setEnabled(false)` 的按钮看起来和启用状态一模一样。相关按钮必须在窗口构造时用 `setColors(text, disabled, hover)` 显式给灰色（我们用 `0x000000` / `0x9A9A9A` / `0x000000`）。

| 操作 | 是否需要授权 |
|---|---|
| 右键控制器创建新 `RoadPlan` | 是（已有计划可只读打开） |
| 规划 / 生成蓝图 / 打开原生建造工具 | 是 |
| 创意即时 Complete/Pretty | 是（创意只免消耗，不自动免权限；创造+OP 按原版规则放行） |
| 原生 Survival 建工单 | 是，且必须位于殖民地范围内 |
| 控制器面板 Repair / Remove | 是 |
| Surveyor 批量 Release / Remove | 是（无权限的条目跳过并计数） |
| 取消工单 | 是 |
| 放置道路锚点（`initial` 蓝图 Survival） | 是 |
| 查看工单列表 / 查看道路列表 / 打开控制器面板 | 是（`ACCESS_HUTS`） |

想在生存模式下管理别人殖民地，只能靠殖民地权限本身（把自己加进殖民地 / 提升 rank），或临时切创造模式（原版规则）。

## 核心机制

### 严格宽度与锚点

- `RoadBounds.ofRoadOnly`：只包含道路宽度与锚点，**不含**外侧控制器 —— 预览与蓝图都用它。
- `RoadBounds.ofBody`：纯铺装体，连锚点 padding 都没有 —— 只用于判断某个格子是否真的在路面内。
- 锚点是**蓝图放置原点**，同时也是 WorkOrder 的位置（`Blueprint.setCachePrimaryOffset` 指向它）。锚点方块由 `Config.roadAnchorBlock` 决定。

### 控制器与迁移

控制器是原生 `minecolonies:decorationcontroller`，本模组不再提供自定义控制器方块。

蓝图**不含控制器**，只在"锚点沿宽度轴、落在铺装体内的一格"写 `structurize:blocksubstitution` 占位符，建筑工不会挖掉这一格。

`RoadControllerMigration.migrate` 的规则：

1. 候选格 = 锚点沿宽度轴偏移一格、落在 `RoadBounds.ofBody` 内的一侧；另一侧作为放置失败的回退；
2. **先放置并回读验证**：目标格先无条件清空（占位符 / 路面方块 / 旧控制器都 `removeBlock`），再 `setBlock`，保证控制器一定占到该格；
3. 放置成功后才删除旧控制器；
4. 更新 `plan.controllerPos`，重绑在线玩家的 Wand，发 `BindRoadWandPayload`；
5. 幂等**只在首选格成立**：控制器已在首选格则直接返回，不搬运。

> **幂等判断不能放宽到"任一候选格"**。外侧旧控制器的位置恒等于 `锚点 + FACING 反向`，也就是第二候选格；一旦按"任一候选格"判定就会直接短路，表现为"旧控制器原地不动、完全没有迁移"，而沿道路方向的控制器反而正常 —— 症状看起来像是迁移方向反了。

**顺序不可颠倒** —— 先删后建会让任何一次放置失败都表现为"控制器消失"。

迁移必须在四条路径上都执行：原生工具（Complete/Pretty/Paste/Survival）、本 mod 面板的创意即时建造、Repair。

### 完成判定

`RoadBuildWatcher` 每 20 tick 检查一次：把蓝图里所有道路方块（跳过空气与 substitution 占位符、跳过锚点和控制器格）与世界上实际方块**全量比对**，比较方块类型，并核对楼梯朝向/半部及DOM材质。Repair还会检查被冻结目标要求清空的旧高处格，原生队列或精确工单结束后才收尾。

不能用抽样：Complete/Pretty/Paste 与建筑工都是跨 tick 放置，提前判定会把控制器放进尚未执行完的占位符格里。

判定完成后进入 `GUARD` 守护窗口（400 tick），期间每 20 tick 确认控制器还在，被异步任务覆盖就补回。

**观察器是纯内存的，所以每次服务端启动都要重新挂接**（`RoadBuildWatcher#onServerStarted`）：
只对 `BUILDING` 或有匹配原生工单的道路恢复观察；普通 `PLANNED` 提案不因世界偶然匹配而自动建成。少了这一步，重启后只有**带殖民地工单**
的道路会被 `RoadWorkOrderRecovery` 找回来，而**用原生建造工具建的路根本没有工单**——没人盯，
完成判定永远不跑，状态停在 `PLANNED`，蓝图也永远留在 pack 里。

### 蓝图生命周期

持久生命周期（`RoadBlueprintStore` 快照）—— 建成、取消、超时**都不删**：

- 生成于 Plan 阶段；
- 只有 `Remove Road`、Surveyor 的 `Release Plan` / `Remove Roads`、`RoadDataCleaner` 会删。

这保证原生建筑工具随时可重开蓝图、Repair 有快照、拆除有完整依据。

**客户端本地副本走另一套规则**：`RoadSyncPayload.blueprints` 只列出**非 `BUILT`** 的道路，
客户端 `ClientRoadPackWriter#retainOnly` 会删掉不在名单里的文件——已建成的也会被删。
所以道路完工后，原生建造工具里不会再列出这条路的蓝图，客户端也不会一直留着一份没人用的副本。

#### 重新选取 A/B

对同一个控制器再次规划时：

- `plan.setId(old.id())` 且沿用旧 `displayName`，所以 UUID 与名称都不变；
- 文件名由 `<短名>-<4位UUID哈希>` 派生 → `generated/<短名>-<hash>.blueprint` 路径不变，**原地覆盖**；
- 快照 `blueprints/road_<uuid>.blueprint` 同样按 UUID 命名，**原地覆盖**；
- 覆盖前先清掉这条路遗留的 WorkOrder：换了几何还留着旧订单，建筑工就会拿新蓝图在老锚点施工；
- 唯一会产生新文件名的情况是旧计划名称为空串（会现生成一个随机名），此时 `RoadTemporaryPack.replace` 会按旧计划先删旧文件，不留孤儿。

**硬校验（不看状态，也不依赖端点字段）**：只要不是 `DRAFT`，新 A/B 就必须与**已存几何**同轴、
同横向原点，否则直接拒绝并提示（换轴 → `cannot change axis`；平移 → `cannot move sideways`）。
判据取自 `axis()` / `anchorPos()` / `minAlong()`，所以 0.1.3 之前建的老存档同样生效。

**建成后的重规划**：`BUILT` 的路需要先在控制器面板打开 `Edit Blueprint`，之后 A/B 选点与逐格编辑才被接受
（默认 `BUILT` 路的左键选点会被拦下并提示去开开关）。重规划只改蓝图、不动世界，
应用新形状的唯一途径是 **Repair**；`Create Blueprint` 按钮对已确认过的路永久置灰，避免第二条应用路径
绕过 Repair 的覆盖表迁移。

#### 端点归位（点击顺序无关）

重选 A/B 时先把两个点**按锚点分侧归位**：与已存 A 落在锚点同一侧的点成为 A，另一个成为 B，与玩家先点谁无关。
`travelDir` 因此恒定，覆盖表的 along 参照系不翻转，台阶方向也不会莫名反向。
归位发生在**选点当下**（客户端），服务端保留同一套校验兜底。

#### 覆盖表：编辑写 diff，Repair 才落表

`RoadPlan` 里有两张表：

| 字段 | 含义 | 谁写 |
|---|---|---|
| `rowOverrides` | 已确认的表，世界按它建 | 只有 Repair |
| `pendingRows` | 未应用的改动（**逐行 diff**，`FLAT` 条目表示"清掉这行"） | 逐格编辑；重选只做过滤 |

- 逐格编辑只写 diff，所以面板的 `N edited` 就是"还没应用的改动行数"；把一行改回原值会让它移出 diff。
- 重选时 diff 里越界的条目被丢弃，`dropped` 则**每次从已确认表重新数**，所以反复重选不会累积。
- **越界行在 Repair 之前不删**：缩短后再拉长，那几行原样回来；真删除发生在 Repair。
- 重建计划（重选、改灯距）时两张表都要搬过去，否则未应用的编辑会静默退回旧值。
- 两张表都随存档持久化并同步到客户端；面板显示的是 diff 大小。

Structurize 侧没有蓝图内容缓存（`StructurePacks.getBlueprint` 每次从磁盘读文件），所以覆盖后重新打开原生工具拿到的就是新几何。

### 名称与标识

- 内部索引始终是 `RoadPlan.id`（UUID），`RoadManager` 的 `SavedData` 名固定为 `mcreconstruction_roads`；
- 完整名称：`<殖民地名>-<随机路名>`（英文环境为 `<Colony> - Road <n>`），维度内唯一；
- 原生按钮显示短名：`<道路名>-<4 位 UUID 哈希>`，例如 `梧桐路-a1b2`；
- `Blueprint.fileName`、文件路径、WorkOrder 名称、工单卷轴显示保持一致；
- 旧 UUID path `mcreconstruction:road/<uuid>` 仍可读取，但不会自动改名。

## 工单创建（与原版对照）

原版（MineColonies）创建装饰/建筑工单的流程，见 `DecorationBuildRequestMessage#onExecute`：

1. 客户端 GUI 发消息到服务端；
2. 服务端用 `getColonyByPosFromDim(dimension, pos)` **按位置解析殖民地**；
3. 校验 `Action.MANAGE_HUTS`，没有权限直接 `return`；
4. 该位置已有同类型工单 → 删除（按钮是 toggle 语义）并 return；
5. 否则 `ServerFutureProcessor.queueBlueprint(...)` **异步**取蓝图；
6. 回调里 `WorkOrderDecoration.create(type, pack, path, name, pos, rotMir, 0)`；
7. `order.setBlueprint(blueprint, world)`；
8. `colony.getWorkManager().addWorkOrder(order, false)`。

`RoadWorkOrderService.createAndAdd` 走的是同一组 API：

- 同样的 `WorkOrderDecoration.create(...)` 工厂，同样的 `setBlueprint` + `addWorkOrder(order, false)`；
- 唯一差别是**我们不同步等蓝图**——`RoadBlueprintGenerator` 已经在内存里生成了 `Blueprint`，直接 `setBlueprint`，省掉 `ServerFutureProcessor` 那一步；
- 权限、toggle、位置解析现在也按原版补齐了。

`stage` 的语义（`AbstractWorkOrder`）：

```java
private BuildingProgressStage stage = null;   // 默认 null
public BuildingProgressStage getStage() { return stage; }   // 只由 setStage 写入
public boolean isClaimed() { return !BlockPos.ZERO.equals(claimedBy); }
```

所以 **“已认领 + stage 为 null” 是一个合法的持久状态**：某个建筑工 hut 已经认领这张单，但还没走到任何建造阶段（材料不够、路径不可达、蓝图异常都会长期停在这里）。这与创建者是谁无关，也不是创建流程与原版不一致导致的。列表 UI 必须容纳这个状态。

## 代码结构

```text
src/main/java/com/YLFXM/mcreconstruction/
  MCReconstruction.java            @Mod 入口：注册表、网络、配置、SurvivalHandler
  ModRegistry.java                 block/item/block entity 注册
  Config.java                      common 配置
  CustomRoadPack.java              每存档结构包 + initial/road.blueprint
  DecorationControllerHandler.java 原生控制器右键：建计划、绑定、开面板
  ItemRoadWand.java                Wand 物品
  ItemRoadSurveyor.java            Surveyor 物品
  ItemWorkOrderScroll.java         工单卷轴
  RoadWandHandler.java             取消 Wand 左键破坏方块
  RoadAnchorSurvivalHandler.java   initial 锚点蓝图的 ISurvivalBlueprintHandler
  RoadWorkOrderRecovery.java       登录/请求列表时从 RoadManager 恢复
  ColonyInfoAccessHandler.java     原版资源卷轴的读取权限检查
  RoadPermissions.java             权限判定（canView / canManage）
  ClientRoadRenderer.java          AABB 渲染
  network/
    Network.java                   全部 payload 的注册与处理
    ConfigureRoadPayload / OpenRoadPlanPayload / RequestRoadsPayload / RoadSyncPayload
    RoadBulkActionPayload / RoadControllerActionPayload / BindRoadWandPayload
    OpenBuildToolPayload / NativeBuildClosedPayload / WorkOrderListPayload / ClientRoadCache
    EditRoadCellPayload / ToggleBlueprintEditPayload   （0.1.4：逐格编辑与修改蓝图开关）
  road/
    RoadPlan.java                  核心计划（NBT 持久化）
    RoadSegment.java               轴向直段 / 台阶
    RoadState.java                 DRAFT/PLANNED/BUILDING/BUILT/REMOVING/REMOVED
    SegmentKind.java               ROAD / STAIR_UP / STAIR_DOWN
    RoadManager.java               每维度 SavedData 索引
    RoadPlanBuilder.java           端点校验、长度、交叉检测
    RoadBounds.java                AABB（严格宽度 / 纯铺装体）
    RoadUnit.java / UnitTemplate.java / RoadUnitExtractor.java  单元描述与开发提取对照
    RoadUnitLibrary.java / RoadHeightProfile.java  预切资源加载与锚点固定高度计算
    RoadBlueprintGenerator.java    严格宽度蓝图生成
    RoadTemporaryPack.java         蓝图在 pack 中的逻辑路径 + 清理旧 generated/
    RoadBlueprintStore.java        每计划蓝图快照（存档内）
    RoadBuildWatcher.java          完成检测、状态推进、迁移触发、守护
    RoadControllerMigration.java   控制器迁移
    RoadQuickBuilder.java          创意即时建造
    RoadRemover.java               按快照删方块
    RoadRepair.java                原生 Repair 会话，实际成功后提交逐行改动
    RoadNativeRemoval.java / RoadRemovalSession.java  原生拆除工单/创造队列与恢复
    RoadDataCleaner.java           全量清理
    RoadNames.java                 命名、短名、唯一性
    RoadWandBinding.java           Wand 绑定（物品 NBT）
    RoadWorkOrderService.java      A 管线工单创建
    RoadPlacementMode.java         PLAN/CONFIGURE/COMPLETE/PRETTY/SURVIVAL
    CellType.java                  FLAT / STAIR_UP / STAIR_DOWN（覆盖表的取值）
    RoadWorkOrderService / RoadManager ...
  client/
    WindowRoadWand.java            Wand 面板（Plan/Mirror/Lamp/Clear）
    WindowRoadController.java      控制器面板（Edit Blueprint/Upgrade/Repair/Remove）
    WindowRoadSurveyor.java        Surveyor 多选列表
    WindowWorkOrderList.java       工单卷轴列表
    ClientNativeBuildTracker.java  原生工具预选（反射）与关闭上报
    ClientRoadPreview.java / ClientSelectionEvents.java / ClientDecorationControllerEvents.java
    RoadSelectionClient.java
```

资源：

```text
assets/mcreconstruction/
  gui/windowroadwand.xml · windowroadcontroller.xml · windowroadsurveyor.xml · windowworkorderlist.xml
  lang/en_us.json · lang/zh_cn.json
  models/item/*.json
data/mcreconstruction/recipe/work_order_scroll.json
```

## 数据模型

`RoadPlan` 持久化字段：`id`(UUID)、`dimension`、`anchorPos`、`controllerPos`、`controllerFacing`、`mirror`、`pack`、`variant`、`displayName`、`roadName`、`width`、`walkFrom`/`walkTo`、`widthSide`、`lampSpacing`、`allowBlueprintEdit`、`endpointA`/`endpointB`(可空)、`travelDir`、`segments`、`rows`、`pendingRows`(可空)、`lastDroppedRows`、`state`、`workOrderId`、`creator`(UUID，可空)。

三个字段值得单独说明：

- **`walkFrom`/`walkTo`**（`WalkRange`）取代了原来的 `walkWidth`。可行走区是一个**区间**而不是宽度，这样风格包作者可以做不对称道路（例如只有一侧有宽人行道）。原版 streets 单元是 `[1, 2]`：0 是灯侧路沿、1 和 2 是铺装、3 是另一侧路沿。老存档只有 `walkWidth`，加载时居中还原，对称风格包结果完全一致。
- **`travelDir`**（`+1`/`-1`）记录 A→B 的方向。`RoadSegment.start` 存的是归一化后的最小角，方向信息在那一步就丢了，而台阶的正/反是**相对行进方向**定义的，所以必须单独存。
- **`rows`** 是覆盖表（**按行**）：键为 `along`，即道路轴上的世界坐标，**不含 Y**。不含 Y 是关键——把某一行改成台阶会抬升后面所有行的高度，若用带 Y 的坐标做键，整张表会在改一行的瞬间全部失效。0.1.4 起按行记录：台阶抬高的是整条可走宽度，逐列状态只能描述一条物理上断裂的路；老存档的 `cells`（`along`+`lateral`）仍可读，加载时按 `along` 折叠成行。
- **`pendingRows`** 是未应用的改动，存成与 `rows` 的**逐行 diff**（`FLAT` 条目表示清掉该行）。只有 Repair 会把 diff 写进 `rows`；面板的 `N edited` 就是 diff 大小，`lastDroppedRows` 是最近一次重选缩掉的行数。

状态机：

```text
DRAFT -> PLANNED -> BUILDING -> BUILT
  ^         |                      |
  |         \-- BUILDING 取消 / 工具关闭未建 --/
  \-- 工具关闭未建且这条路从未确认过（内存标记 FROM_DRAFT）
所有状态 -> REMOVING -> REMOVED
```

`DRAFT` 是"还没确认过规划"的宽松期（可以换轴、随便选点）；`PLANNED` 起轴与横向原点固定。
工具关闭却没建造、或工单被取消时，从未确认过的路退回 `DRAFT`，让 `Create Blueprint` 重新可用。

`RoadSegment(start, length, axis, rise, kind)`：轴只能是 X 或 Z，`length >= 1`，台阶必须 1 格长。

道路长度没有最小限制（`length = |A轴-B轴| + 1`），上限是 `Config.maxRoadLength`（默认 256）。

## 蓝图管线

1. **源单元**：经典包路面/灯来自longroad1，单级台阶来自smallstairs1；宽4、walkRange=[1,2]、surfaceY=1，预切产物随JAR发布。
2. **加载**：开发工具负责导出，运行时RoadUnitLibrary仅读取三份蓝图与稀疏掩码并缓存，不再逐次切源包。明确清空气的控制器格与灯单元不写的路面格分别保留语义，材质TE一并复制。
3. **生成**：RoadHeightProfile按锚点积分；RoadBlueprintGenerator按逐行新高度和旧applied的有界高度拼装。新路面下填充，上方非新灯格写空气；控制器预留最后写入，锚点作为primaryOffset原点而非额外写一块锚点。超高/超体积先拒绝。
4. **两处落盘**：
   - **结构包**（给原生工具/建筑工看）
     ```text
     saves/<world>/mcreconstruction/structurepack/
       pack.json
       initial/road.blueprint
       generated/<短名>-<4位哈希>.blueprint
     ```
     包名固定为 `Reconstruction Plan`，由 `CustomRoadPack` 每次重建 `pack.json` 并注册。
   - **快照**（给 Repair / Remove / 完成检测用）
     ```text
     saves/<world>/mcreconstruction/blueprints/road_<uuid>.blueprint
     ```

拆除通过RoadRemover进入原生工单/创造队列，只以实际应用快照的明确目标冻结范围；空气与替代填充不作为铺装拆除目标。

### 结构包同步（联机必读）

客户端建房工具里的包来自三处：mod jar（immutable）、客户端 `blueprints/` 目录、**服务端同步**。联机时 Structurize 的规则是：

- 服务端 `ServerStructurePackLoader.handleClientUpdate` 只会把 **`!pack.isImmutable()`** 的包发给客户端；
- 客户端 `ClientStructurePackLoader#checkPackDifferences` 收到服务端的包列表后，如果发现「服务端有、本地没有」的包，会把 `needsChanges = true`，于是 **不会调用 `StructurePacks.setFinishedLoading()`**；
- `StructurePacks.waitUntilFinishedLoading()` 返回 false 时，`getBlueprints` / `getCategories` / `getBlueprint` 全部返回空 —— **建房工具面板里一个包都看不到**。

所以本模组每存档的结构包在 `StructurePacks.discoverPackAtPath(...)` 时的 `immutable` 参数**必须传 `false`**，否则客户端会永远等一个服务端永远不会发送的包，表现为“建筑包搜不到”。

`pack.json` 的 `version` 也必须随内容变化（见 `CustomRoadPack#contentVersion`：对 `initial/` 和 `generated/` 下文件的**名字 + 内容**做哈希，且**排除 pack.json 自身**以免自反馈）。版本不变则客户端拿到旧包后永远不会更新；版本乱变则每次加入都重下整个包。注意这里**不能**用时间戳：`install()` 每次都会重写 `initial/road.blueprint`，时间戳一变版本就跟着变，客户端会无休止重下。

### 蓝图归属：谁能看到哪条路

**这是本模组最重要的安全边界，改动前务必读完。**

Structurize 同步结构包时是 `zipPack(pack.getPath())` —— 它**逐个文件遍历整个 pack 目录并打包**，没有按文件过滤的入口，也不提供 hook（除非上 Mixin，而本项目禁用 Mixin）。所以**只要文件在服务端 pack 目录里，它就会被发给每一个进服玩家**。

道路蓝图恰恰不能公开：它暴露别人殖民地的布局，而野外道路是创建者的私事。因此：

1. **服务端 pack 里的 `generated/` 只包含"施工中"的道路**。道路一旦变成 `BUILT`，它的蓝图立刻从 pack 里删掉（`RoadBuildWatcher#markBuilt` / 创意即时建造两处）。所以进服泄漏面 = 此刻正在建造的那几条路，不是全部历史。
2. **权威副本存在 `RoadBlueprintStore`**，路径 `saves/<world>/mcreconstruction/blueprints/road_<uuid>.blueprint`，在 pack 目录**之外**，永不被 zip。Repair / Remove / 完成判定都读它，不读 pack。
3. **每次服务端启动按快照重建 `generated/`**（`RoadTemporaryPack#rebuildFromStore`）：清空目录，再把所有非 `BUILT` 道路的蓝图从快照写回去。这样即使"建成"和"删文件"之间崩过一次，已完工的蓝图也不会永远留在 pack 里。
4. **客户端按需点对点接收**：有权限的玩家点 `Create Blueprint` 时，`OpenBuildToolPayload` 携带蓝图 NBT 与包版本；客户端 `ClientRoadPackWriter` 写进**自己本地**的 `blueprints/Reconstruction Plan/generated/` 并重新注册包。
5. **客户端会清理多余的本地蓝图**：`RoadSyncPayload` 额外带一个 `blueprints` 列表（该玩家在所有维度上**有权看到的蓝图文件名**），客户端 `ClientRoadPackWriter.retainOnly` 把 `generated/` 下不在列表里的文件删掉。

> **为什么不能干脆不放进 pack**（这条踩过坑，别再试）：原生建造工具的 Complete / Pretty / Paste 由**服务端**执行，
> `BuildToolPlacementMessage` 只把"包名 + 路径"发上去，服务端 `BlueprintPlacementHandling#handlePlacement`
> 自己调 `StructurePacks.getBlueprintFuture(packId, blueprintPath, ...)` 从磁盘读。
> 客户端有没有蓝图都不影响这一步。所以文件**必须**存在于服务端已注册的 pack 里，
> 否则每次放置都静默失败（日志：`NoSuchFileException` → `Couldn't retrieve blueprint`），路就是铺不出来。
> 曾经的错误判断是"服务端只读 `RoadBlueprintStore` 快照就够了"——只覆盖了 Repair/Remove/工单恢复，
> 漏掉了原生工具这条路。

**可见性规则**（`Network#isVisibleTo` / `#isEntitledToBlueprint`）：

| 道路位置 | 列表里能看到 | 能拿到蓝图文件 |
|---|---|---|
| 殖民地内 | **`ACCESS_HUTS`**（默认 = 领主 + 官员 + 好友） | 该殖民地有 **`MANAGE_HUTS`** 的人（= 领主 + 官员） |
| 野外·有主 | **仅创建者** | **仅创建者** |
| 野外·无主（`creator == null`） | 所有人 | 所有人 |

**所有权只有两种归属：所在殖民地，或创建者，且殖民地优先。** 位置决定归属——控制器落在某个
殖民地境内，这条路就属于那个殖民地，可见性与操作全部按殖民地权限走；落在野外则属于创建者。

**殖民地一旦吞并了某条野外道路，创建者的豁免权就消失了**：他不再是这条路的所有者，蓝图授权
与其他殖民地道路一样只按 `MANAGE_HUTS` 判定，不会因为"当初是我画的"而多一分权限。这一条是
刻意的不对称——领地属于殖民地，不属于曾经路过的人。

由此推出勘测仪的两种视图：

| 视图 | 内容 |
|---|---|
| 已绑定市政厅 | 该殖民地境内且玩家有 `ACCESS_HUTS` 的道路 **+ 玩家自己的野外道路** |
| 未绑定 | **只列野外道路**（自己创建的，以及无主的）。殖民地道路不属于个人，不在这里出现 |

**名字里的前缀跟着归属走。** 归属是每次根据控制器位置现算的，所以殖民地扩张把某条野外路圈进去之后，
名字会在几秒内从 `野外-望江路` 变成 `<殖民地名>-望江路`，不需要玩家手动改，也不需要重建蓝图——
原生按钮显示的是**去掉前缀**的短名（`RoadNames.fileName`），所以改名不会让蓝图文件或工单对不上。

绑定后仍然带着自己的野外道路，是因为绑定只是「聚焦某个殖民地」，不该让玩家找不到自己建在别处的路。

### 为什么用 `ACCESS_HUTS` 而不是自造"成员"或"新能力值"

**不要引入"殖民地成员"这个布尔量**——MineColonies 没有，而它看上去最像的那个东西是**错的**：

`IColony#getPermissions().getPlayers()` 返回的是一张**等级名册**（`Map<UUID, ColonyPlayer>`，每个条目带一个 `Rank`），不是成员表。它里面装着从 OWNER 到 **HOSTILE** 的全部玩家——玩家攻击别人殖民地时，防守方会把攻击者以 `HOSTILE_RANK_ID` 写进自己的名册（`EntityCitizen.java:1308`），权限界面加的玩家则会以 `NEUTRAL_RANK_ID` 进去（`PermissionsMessage:180/283`）。用「在名册里」当"成员"判定，会把**正在打这个殖民地的敌人**当成成员放行。这个错误本模组犯过并已修正。

**也不要试图新增一个 `Action`**——它做不成，三个硬障碍：

1. `Action` 是普通 `enum`，NeoForge 的 `IExtensibleEnum` 扩展机制要求目标枚举**自己实现该接口**，MineColonies 没有；
2. 权限界面用 `Arrays.asList(Action.values())` 生成复选框（`WindowPermissionsPage:110`），枚举之外的位**不会出现在界面上**，玩家无法授予/撤销；
3. `Rank` 只暴露 `addPermission(Action)` / `getPermissions()`，没有按位写入的 API。

于是只能用 `ACCESS_HUTS` —— 原版自己的"可以进入并使用殖民地建筑"能力位。`loadRanks()` 里它经 OWNER → OFFICER → FRIEND 的 fallthrough 授予，NEUTRAL / HOSTILE 没有，正好是"领主 + 官员 + 好友"这一档，并且可以在权限 GUI 里按 rank 调整。

**原版已经用它拦住了大部分"看殖民地内部"的入口**，我们不需要重复实现：

| 入口 | 原版拦在哪 |
|---|---|
| 右键任何小屋（含市政厅、建筑工小屋） | `ColonyPermissionEventHandler` 要求 `ACCESS_HUTS` |
| 打开市政厅 GUI | `BlockHutTownHall` 要求 `ACCESS_HUTS` |
| 请求清单 | 位于市政厅 GUI 内部，连带被拦 |
| 本模组工单卷轴绑定市政厅 | 走"右键小屋"路径，连带被拦 |

### 本模组补的三处缺口

| 位置 | 做法 |
|---|---|
| 道路列表（`sendRoads`） | 殖民地道路要求 `ACCESS_HUTS`；绑定视图同样过滤，否则绑一个市政厅就绕过了 |
| 工单卷轴列表（`sendWorkOrders`） | 要求 `ACCESS_HUTS`；拒绝时**不下发任何包**，客户端就不会开窗 |
| 原版资源卷轴（`ColonyInfoAccessHandler`） | `ItemClipboard` 的 `use`/`useOn` 原本**零检查**；在其 `PlayerInteractEvent` 上按手持物品拦截 |
| 控制器绑定与面板 | 要求 `ACCESS_HUTS`（`DecorationControllerHandler`） |

> 资源卷轴的拦截必须在**客户端和服务端都取消**：窗口是客户端自己的 `Item#use` 打开的，只拦服务端挡不住正常客户端。
>
> **限度**：MineColonies 会把工单推给 `closeSubscribers`（走近殖民地就自动加入的玩家），所以这份数据本来就在客户端内存里。我们只能做到"正常客户端打不开界面"，改过的客户端仍能读到。要真正堵住得改 `ColonyPackageManager` 的推送对象 —— 那是 Mixin，本项目不做。

### 拒绝提示

统一走 `RoadPermissions#notifyDenied`：`MessageUtils.format(TranslationConstants.PERMISSION_DENIED)`，即原版 `com.minecolonies.coremod.permission.no`，默认 `MessagePriority.NORMAL` = **灰色**。节流照抄 `ColonyPermissionEventHandler#cancelEvent`：同一玩家 10 秒内只提示一次（**不含**它那段"连续 10 次就弹漂浮"的惩罚，那个是反刷屏手段，不该因为点了个被拒的界面就把玩家浮起来）。

### 为什么野外必须留"创建者"

野外没有殖民地，就没有任何权限位可以问——这是唯一无法复用原版规则的地方。`RoadPlan.creator` 是持久化字段（NBT `creator`），在建计划时写入，重新规划 A/B 时**沿用旧值**（不能因为别人重选 A/B 就转移归属）。老存档没有这个字段，读出来是 `null`，这种**无主**道路按公开处理（列表与蓝图对所有人放开），并且被重新规划 A/B 时会自动认领给当前玩家。

### 为什么蓝图比列表严

蓝图是**逐方块的完整数据**，包含站在路上看不见的部分（地下的 `blocksolidsubstitution` 支撑填充、预留的路灯高度），而且能被放进原生建造工具复制到任何地方。列表只是元数据（名字、状态、长度、控制器坐标）。所以列表可以不设卡，蓝图不行。

> 网络层只负责「发给谁」，服务端**每次操作仍然重新校验权限**（`RoadPermissions.canManage`），不依赖客户端传来的任何标记。

**结构包只在进服时同步一次**（`ClientStructurePackLoader` 的 `onWorldTick` 在 `FINISHED_LOADING → SYNCING` 只走一遍）。所以会话中途生成的蓝图**不会**自动出现在客户端，原生工具会报找不到蓝图。这就是上面第 3 条点对点下发存在的原因。`pack.json` 同步写成服务端当时的版本号，避免下次进服时因版本不一致被整包删除重下。


## 网络协议

| Payload | 方向 | 作用 |
|---|---|---|
| `ConfigureRoadPayload` | C→S | A/B、模式、镜像 |
| `OpenRoadPlanPayload` | S→C | 打开控制器面板 |
| `RequestRoadsPayload` / `RoadSyncPayload` | C→S / S→C | 道路列表同步（`RoadSyncPayload` 里带 `bound` / `colony` / `manageable` / `blueprints`） |
| `RoadBulkActionPayload` | C→S | 批量 Release / Remove |
| `RoadControllerActionPayload` | C→S | Upgrade / Repair / Remove |
| `BindRoadWandPayload` | S→C | 迁移后通知客户端重绑 |
| `OpenBuildToolPayload` | S→C | 切包 + 打开原生工具 + 预选路径，**并携带蓝图 NBT 与包版本** |
| `NativeBuildClosedPayload` | C→S | 原生工具关闭（未建成时按 `FROM_DRAFT` 决定退回 `DRAFT` 还是保持 `PLANNED`） |
| `EditRoadCellPayload` | C→S | 逐格编辑：只传被点的方块，服务端自己解析归属行 |
| `ToggleBlueprintEditPayload` | C→S | 切换该道路的「修改蓝图」开关 |
| `WorkOrderListPayload` | S→C | 工单卷轴数据（含 `bound` 标记，未绑定时客户端显示提示而不是空白） |
| `CancelWorkOrdersPayload` | C→S | 取消选中的工单（按 colonyId + orderIds） |

## 配置

`config/mcreconstruction-common.toml`：

| 项 | 默认 | 说明 |
|---|---|---|
| `roadFillBlock` | `minecraft:stone` | 支撑填充的兜底方块 |
| `roadAnchorBlock` | `minecraft:stone_bricks` | 锚点方块，也是控制器 `FACING` 指向的目标 |
| `lampSpacing` | 4 | 路灯间距，从锚点向两端按此间隔放灯；1 表示不生成路灯（上限 8） |
| `defaultLampReserveHeight` | 6 | 预览与蓝图预留的路灯高度 |
| `maxRoadLength` | 256 | 单段道路最大长度 |
| `creativeInstantBuild` | true | 创意模式允许面板即时建造 |
| `debugLog` | false | 打开后完成检测会打印第一处不匹配的坐标 |

## 构建

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

输出 `dist/mcreconstruction-<版本>.jar`，版本取自 `neoforge.mods.toml`。

`build.ps1` 行为：

- classpath 来自 `cp.txt`（**未入库**，需要自己生成：把 Minecraft 实例 `libraries` 下的 jar 路径用 `;` 连接写进去）；
- Java 运行时按 `tools\jre` → `%APPDATA%\.minecraft\runtime\java-runtime-delta` → `JAVA_HOME` 顺序探测，取第一个能跑 `java -version` 的；
- 镜像里没有 `javac.exe` / `jar.exe` 时分别走 `java -m jdk.compiler/...` 与 `java -m jdk.jartool/...`。

代码回归入口为 `tests/run.ps1`。原生蓝图空气检查需要原版客户端JAR的语言/版本资源，默认使用指定测试实例；其他机器可设置 `MCRECONSTRUCTION_TEST_CLIENT_JAR` 指向Minecraft 1.21.1客户端JAR。测试只在独立进程中初始化注册表，不启动游戏。

安装：把 jar 复制进实例 `mods/`。

```powershell
Copy-Item -LiteralPath 'dist\mcreconstruction-0.1.6.jar' `
  -Destination '<你的实例>\mods\mcreconstruction-0.1.6.jar' -Force
```

## 本地测试服务器

仓库外另有一个 NeoForge 21.1.259 / Minecraft 1.21.1 专用服务器用于联机测试。

- **安装方式**：`maven.neoforged.net` 直连不通时，installer 可从 BMCLAPI 镜像取
  `https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/21.1.259/neoforge-21.1.259-installer.jar`；
  先把客户端 `.minecraft\libraries` 整个预置到 `server\libraries`，再 `--installServer`，这样它只需要从 Mojang 补服务端 jar 和官方库。
- **mods/**：与客户端实例保持一致，**两端必须同一个 jar**。
- **server.properties**：`server-port=25565`、`max-tick-time=-1`、`view-distance=8`、`spawn-protection=0`。
- **eula.txt**：需自行写 `eula=true`（代表已接受 Mojang EULA）。
- **OP**：控制台执行 `op <玩家名>`。离线模式（`online-mode=false`）下 UUID 由名字推导，换名字等于换人。
- **已知无害日志**：`BOWindow for invalid dist DEDICATED_SERVER`（某个 mod 的 mixin 指向 BlockUI 的客户端类）、`SSLHandshakeException`（NeoForge 版本检查访问 GitHub 失败），都不影响启动。

## 依赖源码

**查依赖 API 先读上游源码，不要一上来就反编译 jar。** 三个主要依赖都是开源的，直接看它们的仓库：

| 依赖 | 仓库 | 分支 |
|---|---|---|
| MineColonies | [`ldtteam/minecolonies`](https://github.com/ldtteam/minecolonies) | `version/1.21`（更新版本可能改为 `version/1.22` 等） |
| Structurize | [`ldtteam/structurize`](https://github.com/ldtteam/structurize) | `version/1.21` |
| BlockUI | [`ldtteam/blockui`](https://github.com/ldtteam/blockui) | `version/main` |

下载源码（zip 方式）：

```bash
curl -L -o minecolonies.zip \
  "https://github.com/ldtteam/minecolonies/archive/refs/heads/version/1.21.zip"
unzip -q minecolonies.zip -d minecolonies
```

也可以用 GitHub 的 Code → Download ZIP，或 `git clone --depth 1 -b version/1.21`。

- 建议把源码放在**仓库外**的目录（不要提交进来），例如 `../refs/minecolonies`。
- 分支是 **HEAD**，可能比你实际安装的版本略新（例：minecolonies `version/1.21` 的 `gradle.properties` 里 blockui 依赖写的是 1.0.199，而实际装的是 1.0.212）。查行为、查签名够用，**涉及二进制兼容时以你本机 `cp.txt` 指向的 jar 为准**。
- BlockUI 在新版本里把 `Window` 改名为 `BOWindow`。
- Domum Ornamentum 不必下载：本 mod 只用到它的方块（通过风格包蓝图），不引用其类。


## 已知限制

**整体状态：开发中。** 以下功能未完成或仅有部分实现：

- 当前仅内置经典street预切单元组；其他风格/宽度尚未提供，缺少匹配资源时明确拒绝。
- 交叉口只做几何检测与覆盖，没有专门铺装和路灯协调。
- 阶段 5 Repair 与首次施工已走原生工具，并通过本轮验收；Upgrade 按钮禁用。
- 名称唯一性是**维度内**全局，不是严格"同殖民地内"。
- 宽度 1 的道路几何上无法把控制器放进铺装体，会退化为相邻格。
- 专用服务器：结构包本体在服务端存档目录，客户端不下载整包；蓝图改为随 `OpenBuildToolPayload` 点对点下发（见「结构包同步」）。
- README 之外没有正式 wiki；英文文案仍有中英混杂。
- 游戏内验证由人工完成，已通过本轮单机／联机清单；封存项目仍未验证；存档格式仍可能变动。

## 开发状态

**当前版本 `0.1.6` 是开发中版本，不适合正式使用**：阶段6台阶已验收，阶段7交叉口未实现。

已实现并验证过的：

- Road Wand 选点 → 严格宽度蓝图 → 原生建造工具（Complete / Pretty / Paste）→ 控制器迁移；
- Survival 工单路径：道路成为殖民地建筑工的真工单，建成后迁移；
- 控制器面板的 Repair / Remove，Surveyor 的批量释放/拆除，工单卷轴的多选取消；
- 权限：完全复用 MineColonies 原版判定，界面按权限逐行置灰；
- 联机：结构包注册 + 蓝图点对点下发。

未完成或未充分验证的：

- 交叉口实现与道路覆盖策略（见「已知限制」）；
- 完整回归测试，存档格式稳定性；
- 英文本地化（`en_us.json` 内容仍是中文）；
- 原生施工、拆除和蓝图授权已完成本轮单机／联机验收；S8/S15/D10/N8仍封存，不等于通过全部极端场景。

## 路线图

**P1 严格原生流程收口**：已完成并验收。

**P2 路灯/台阶/交叉口**：灯、预览、逐行编辑和原生施工/拆除已验收；阶段6预切单元与真实台阶已验收，阶段7交叉口未开始。

**P3 地形/装饰/寻路/联机**：custom dig/fill、装饰控制扩展、Pathfinding Edition 联动、专用服务器结构包同步、Surveyor 过滤/分页/统计、自定义模型、完整本地化、旧代码清理。

## 开发约定

1. **不引入 Mixin**。曾因 `specified mixin not found` 启动失败，改为登录/列表事件恢复；要恢复必须先解决启动兼容。
2. **不删持久蓝图**，只有 Remove / Release / Cleaner 才删。
3. **控制器预留格必须是 `structurize:blocksubstitution`**，不能改回空气，否则建筑工会挖掉未来控制器格。
4. **原生按钮名用短名 + 4 位哈希**，不用完整殖民地名，也不退回 UUID。
5. **UUID 保留为内部索引**，外部可见标识一律用名称。
6. **迁移先放置验证、再删旧**，不允许先删后建。
7. **完成判定必须全量校验**，不能抽样。
8. **迁移必须在全部四条建造路径上调用**：原生工具、Survival 工单、创意即时建造、Repair。
9. 构建后必须复制 jar 到实例 `mods/`；代理环境不启动游戏，游戏内验证由人工完成。
10. 查 MineColonies / Structurize / BlockUI 的 API 先读上游仓库的源码（见「依赖源码」一节），不要一上来就反编译 jar。


## 0.1.4-fix.1 修复与判断合并（已验收）

用户已确认本轮修复全部验收通过，作为 0.1.4-fix.1 修复版本归档；阶段 5（Repair 接原生工具）未开始。

- 首次调整灯距不再提前确认规划；Create Blueprint 在客户端和服务端均只允许 DRAFT。
- 建成后开 Edit Blueprint 可以直接改灯距；施工中禁止逐行编辑、改参数与重选。
- 缩路时，即使已确认覆盖表为空，也过滤越界的未应用 diff 并报告；已确认行仍等 Repair 才删除。
- 迁移控制器只重绑原来指向它的魔杖。协议同步包含原位置和新位置，客户端与服务端需同步更新。
- 重启仅恢复 BUILDING 或有实际工单的计划，不自动把普通 PLANNED 编辑提案判成建成；无需等待缓存过期。
- 共享逻辑分别在 RoadRules、RoadReplanning、RoadWandBinding、RoadPermissions、RoadWorkOrders、RoadBlueprintOps 与 RoadBuildWatcher 收口。客户端提示与服务端权威校验仍各自保留。
- 多人行为按静态检查修复，联机验收按用户约定延后。游戏测试进度见仓库外 ColonyRefactor/重构计划-实现进度.md。


## 预览与删除保护修复（0.1.4-fix.1，已验收）

- 魔棒只显示所持魔棒绑定道路；规划仪显示自己的道路范围索引，二者不再共享过滤结果。显示不随玩家站在殖民地/野外自动切换。
- 初次选好 A/B 即可直接逐行编辑，不必先调灯距，编辑后仍为 DRAFT。草案换轴会清理不兼容行改动并提示。
- 删除会清除对应选择、幽灵和魔棒绑定；同位置的新道路不继承旧道路。异步请求与回复校验 UUID/维度/请求序号。
- 未施工道路删除不再按待建图清地形。建成后保存独立 applied_UUID.blueprint；已建后重规划也按最近实际应用图拆旧路。
- 旧 PLANNED 或部分施工无法证明实际范围时，删除保守取消任务并保留无法证明归属的已铺方块。已替换成不同类型的世界方块也保留。
- 当轮网络协议 3，客户端和服务端必须同一测试 JAR；本轮修复已通过用户验收。


规划仪相邻快捷栏直接互切也会按手位/槽位/绑定市政厅切换显示，旧回复不能恢复前一视图。道路索引框和魔棒幽灵（含其白框）共用 64 格最近体积距离限制；远离隐藏并保留缓存，靠近恢复。此补修已通过用户验收。

显示距离默认 64 格；后续计划提供客户端可调整项，目前尚未配置化。

本轮修复已于 2026-10-23 通过用户总体验收；多人专项仍保留先前约定，待本阶段功能完成后集中验证。代码回归 71 条通过，见 `tests/run.ps1`；道路显示距离客户端可配置化仍为待办。


## 0.1.5：原生施工与拆除（已验收）

2026-10-24用户确认本轮R1–R5全部通过，阶段5完成，收口至main；网络协议7，客户端、服务器和其他联机玩家须同版。P2完成前不推送，台阶和交叉口尚未开始。

- 首次Create与Repair共用原生布局及受控确认；仅接受本路蓝图、原锚点及原朝向。Complete/Pretty使用Structurize队列，Survival使用MineColonies工单，不新增放置循环。
- 持久会话冻结目标，确认前关闭退回原状态，首次施工退回DRAFT；确认后不因关窗/断线取消工单。完成且保存实际应用快照后才提交编辑、关闭编辑开关并迁移控制器。
- 建筑工拆除走原生REMOVE工单；创造模式单击拆除再选建筑工/快速拆除。DRAFT只清计划、控制器与锚点，保留原地形。野外要求最近殖民地的管理权限、建筑工及距离限制。
- 草案按维度与UUID保留本地A/B和镜像，重绑恢复，Clear保持清空；删除/失权/退出清理对应记录。覆盖表和未应用提案维持原有语义。
- 控制器先经服务端授权才开窗；取消工单通知相关在线玩家。道路被删或施工会话过期后，旧窗口确认会明确提示。此前错误图施工无可信实际应用快照时，不猜测扩大拆除范围。

构建与208条代码断言通过；本轮已通过项详见仓库外ColonyRefactor/重构计划-实现进度.md。S8、S15、D10、N8按用户要求封存；最后失效窗口提示用户明确免除游戏复测，不额外要求重测。0.1.5测试包SHA256：C24072FEFF186EA9DFE1B0826EBC10E961AF14A345CFEF3BAA75BB932D99FC00，指定客户端与服务器已同步。

已知观察：施工中重启后建筑工实体可能不可用，MineColonies召回操作会打印AWOL并尝试恢复。本mod恢复逻辑仅跟踪原工单，不删除、复活或传送公民；根因未确定，按用户要求暂不改动。

接手文档不持续维护，仅在用户需要时按对应版本另行编写。当前设计与进度继续正常维护。

## 0.1.6：预切单元与真实台阶（已验收）

运行时改读随JAR提供的普通路面、路灯与单级DOM泥土楼梯单元，保留稀疏跳过格、材质和方块实体；tools/export_road_units.py用于离线导出/核对真实源素材。当前仅内置经典street单元组，旧包名有明确映射，缺资源/依赖/不支持的宽度不静默换材质。

UP/DOWN按A→B累计高度，锚点行固定FLAT，镜像不反转坡向。灯柱随所在行基座升降；作业范围内低于新路面填充、高于新路面且非合法路灯格写空气，覆盖旧高处道路/灯柱。控制器预留最后写入；当前A/B外旧路不自动拆。新增maxGeneratedBlueprintVolume，默认1,048,576格；世界高度和体积超限在生成前拒绝。

首次/Repair仍走原生施工会话，成功才落表/更新applied；完成检查包括楼梯方向/半部、DOM材质及需要清空的旧高处格。旧会话使用原冻结图，缺快照只报警而不按新算法重生成。已建范围从实际应用内容派生，预览去重包含行类型内容。清空检查目标随会话保存，客户端只接收busy及派生范围。

首次规划沿轴缩短后，服务端在预览生成校验成功时直接丢弃越界的未应用行并刷新覆盖表计数；仍保持DRAFT，不提交已确认表。蓝图未写入格显式初始化为真实空气，控制器预留继续单独使用占位符。

魔棒真实幽灵显示时，控制器上方显示金黄色直立实心箭头（各边长为初版的75%），固定指向当前A→B方向。绕控制器移动或转视角不会改变其世界朝向，镜像不反转方向；箭头随幽灵隐藏，自身也受64格距离限制，规划仪不显示此箭头。

构建与590条Java断言、25个有效单元格导出校验通过。2026-10-24用户确认阶段6单机/联机及四项补修全部通过，版本收口0.1.6、协议8；没有素材的旧路/旧冻结图测试子项已删除，原封存项目仍封存。dist、指定客户端和本地服务器已同步0.1.6，SHA256：D0897BF27299630FEB9F780F2F71663F33B2C2AA59E55DF068638FD84CA04798。两端旧包已归档至output/stage6-0.1.6-previous-mods，mods中仅保留当前版本。阶段7未开始，不推送。
