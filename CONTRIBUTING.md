# 贡献与版本移植

本项目采用[GPL-3.0](LICENSE)。保留许可和原作者署名，修改版说明改动。代理协作以[AGENTS.md](AGENTS.md)为准；当前机制与逐文件职责见[代码结构与机制](docs/代码结构与机制.md)，验收状态见[实现进度](../ColonyRefactor/重构计划-实现进度.md)。

## 开发环境与依赖核对

当前本机测试环境是Minecraft 1.21.1、NeoForge 21.1.259、Java 21。准确安装依赖见README；构建classpath来自未入库的cp.txt，换机器须重新配置映射Minecraft与依赖JAR。

查API先读仓库外refs内的MineColonies、Structurize、BlockUI源码，按需检索，不反编译依赖。refs是对应分支源码，可能比实际安装包新，二进制签名以cp.txt指向的依赖为准。

## 版本移植时优先检查

1. **原生窗口公开API**：当前WindowRoadRepairBuildTool通过WindowExtendedBuildTool公开构造回调组合，不继承可能为final的类。ClientNativeBuildTracker仅用updateBlueprints进入generated，不反射自动预选。若API变动，先验证安装版本的签名与布局。
2. **结构包装载与版本**：CustomRoadPack注册可变包，版本由initial/generated文件名及内容计算，排除pack.json。不得让道路包残留为殖民地默认风格，关工具后恢复原选择。
3. **会话中生成图的下发**：原生包同步不保证运行中新增图到达，OpenBuildToolPayload携带工图NBT与包版本，由ClientRoadPackWriter写本地并重新注册。建成或失权按授权名单清理。原生进服整包同步仍有发布文件可见性限制，不能宣称点对点传送消除了它。
4. **真实Blueprint语义**：原生便捷构造器默认格可能是placeholder，不是AIR。生成器显式初始化AIR；稀疏skip格、填充和占位保护要分开。addBlockState会清primary offset缓存，所有改写后恢复锚点偏移。
5. **原生施工保护**：Complete/Pretty使用RoadProtectedStructurePlacer，建筑工使用RoadProtectedIterator，确保blocksubstitution在清空/放置阶段都不触碰邻路。不能只看预览正确。
6. **权限与可信准入**：殖民地按原生ACCESS_HUTS/MANAGE_HUTS，管理员绕过要求创造模式且达到permissionEventMinBypassPermLevel配置。野外已有计划另查creator/无主/创造OP。RoadBuilderDispatch可信准入只用于已验证冻结道路，不能推广到普通建筑。
7. **完成与提交**：原生结束信号加全量实体/楼梯方向半部/DOM材质及冻结清空格校验。RoadJointJournal只在世界校验完成后记录多快照重做，恢复数据提交，不能借此重新生成或重放世界工图。
8. **拆除目标**：RoadNativeRemoval消费实际快照及邻路保护掩码；保留原生填充语义，缺快照不猜测扩删。创造快速拆除也走原生扫描操作。
9. **客户端类隔离**：common代码的签名/回调不可意外加载LocalPlayer等客户端类型，专用服务器必须能加载。根包也有Dist.CLIENT类，不能只凭目录判断运行侧。
10. **BlockUI行为**：核对BOWindow/AbstractWindowSkeleton等API；按钮enabled/disabled/hover显式设置不同颜色，不能仅XML单色导致禁用看起来仍可用。

## 修改机制时保持的边界

- 道路本体使用原生队列或工单；初始锚点/控制器管理方块与完成迁移另有明确管理动作。
- 提案、显示图、冻结工图与实际应用图不能混用。普通/联合预览显示补全不改施工掩码；成功施工才提交实际结果。
- 逐行表的键是轴向世界along，不含Y或lateral；明确手工FLAT另记约束。重规划保留UUID、创建者及两张行表，已建PLANNED仍受编辑开关约束。
- 同层交集严格平路等高；分层同时校验路面差、实际碰撞净空、整灯与地基保护。
- 规划批准只及本次范围，客户端access/jointApproved不是授权证明。原样Repair免重复批准必须有实际快照及工图证明。
- 控制器先在目标放置并回读成功，再删旧；只重绑匹配旧位置的魔棒。所有成功路径共用迁移/守护。
- 选点/规划允许玩家远离控制器；实际右键开窗有原生交互距离校验，不能把两种入口的距离规则混用。
- 当前不使用Mixin。旧直接施工类虽保留，现行消息准入已拦住，不能作为新功能捷径。
- 文档与实装的待定差异显式记录；当前重复拒绝漂浮行为尚未决定调整，不通过文档改写假定已确认。

## 构建、验证与提交

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File build.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tests/run.ps1
```

测试资源需要原版客户端JAR，可设置MCRECONSTRUCTION_TEST_CLIENT_JAR。详见README。代理不启动游戏；单元/回归不能替代真实原生施工、权限、取消/重启和多人交互验收。纯文档修改检查内容、链接和差异即可。

本仓库每阶段验收后才提交并升级补丁版本，P2完成前不推送。标题沿用“阶段 N：功能说明（版本号）”或“修复版本：修复范围（版本号）”；正文按新增/修复/文档分节，末尾写实际验证，不把封存或待测记为通过。

报告问题请附客户端/服务端JAR是否一致、准确依赖版本、相关日志、道路状态及复现步骤；存档与冻结图先保留，不直接删缓存、会话或实际应用快照。
