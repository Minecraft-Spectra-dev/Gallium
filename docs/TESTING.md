# Gallium 渲染验证

用于渲染功能的回归测试和发布验收。

## 使用范围

- 涉及捕获、Mixin、深度、合成或 GUI 的改动，在**全部受影响版本**执行相关章节；发布前执行全部目标构建 `.\gradlew.bat build`。
- 解析、配置 IO、捕获分类、嵌套作用域及异常恢复由 [JUnit 测试](../src/test/java/cn/spectra/gallium) 覆盖；客户端检查负责实际渲染、驱动和模组组合。
- 在对应变更的验证记录中注明 Minecraft / 模组 / 光影 / 资源包版本、分辨率、算法及截图；兼容性异常使用“禁用 Gallium”的同配置对照定位。
- TAA、区域坐标和深度采样的实现规则见 [光影兼容文档](SHADER_PACK_COMPATIBILITY.md)。

## 测试环境

- 构建目标以 [settings.json](../settings.json) 为准，Minecraft 和依赖版本取自 [versions](../versions) 下各目标的 `gradle.properties`。
- 安装 Fabric Loader、Fabric API、Sodium 和 Gallium；Iris、YDM、SR 按测试场景添加。测试存档与日常游玩存档隔离。
- 选择 Iris / Sodium 组合时，同时核对 [Iris](https://modrinth.com/mod/iris/versions) 的 `depends.sodium` 和 [Sodium](https://modrinth.com/mod/sodium/versions) 的 `breaks.iris`；仅编译依赖不得混入运行环境。
- 配置 UI 兼容改动需覆盖有/无 Iris 及受支持的 Sodium API 分支；无配置页的组合通过配置文件验证开关。

## 常规渲染与开关

- [ ] 资源包包含 `assets/gallium/item_effects.json` 及所需 shader；启动日志规则数正确，无 Mixin 应用失败或 shader 编译错误。
- [ ] 覆盖主/副手、自己及其他玩家手持、怪物装备、四个盔甲槽、掉落物、普通/发光物品框及适用版本的展示架。改变视角并切换窗口/全屏，描边应贴合。
- [ ] GUI 图标覆盖背包、热键栏、箱子、合成台、潜影盒、交易和铁砧；同名 shader 的不同参数及不同 shader 同屏时各自生效，重载后不串色。
- [ ] 第一人称物品穿入墙体时描边仍完整；手臂、地图不得产生额外捕获，打开 GUI 不干扰手持描边。
- [ ] 世界装备及外扩描边被较近的身体、方块或其他物品遮挡；背部武器从正面看不能穿过身体。半遮挡移动、贴脸、远处和屏幕边缘无闪烁、突变或爆裂。
- [ ] 世界物品与第一人称空手、主手、副手重叠时，世界描边不得覆盖手部。分别用原始 shader 和世界厚度缩放版验证，并在 Iris / SR / 26.2 reverse-Z 下复核。
- [ ] 多个前后排列的发光物品不互相覆盖或混色；水、玻璃、树叶等场景中可见部分不整体消失，不透明部分仍正确遮挡。
- [ ] 用支持世界空间描边宽度的资源包检查 2、4、8、16 格的光晕连续缩放，距离翻倍约减半；FOV、分辨率和渲染比例变化后仍对齐，无 16 格阈值或一像素下限。第一人称和 GUI 保持原有厚度。
- [ ] 每个开关执行 ON → OFF → ON，确认只影响对应对象。使用可用的 Sodium 配置页；无配置页时修改 `config/gallium.json` 并重启客户端。

| 配置键 | 控制对象 |
| --- | --- |
| `enabled` | 总开关，关闭后所有描边消失 |
| `first_person` | 第一人称左右手；不影响 YDM 身上装备或角色预览 |
| `third_person` | 玩家世界手持、背部装备及角色预览中的这些物品 |
| `other_entities` | 其他实体手持、物品框、展示架及其他实体预览的物品 |
| `dropped_items` | 世界掉落物 |
| `armor` | 盔甲，包含角色预览中的盔甲 |
| `gui` | GUI 物品图标；不控制角色预览装备 |

除 `enabled` 外，上述键位于配置文件的 `render_targets` 对象内。

## 角色预览

- [ ] 生存和创造背包中检查附魔盔甲、盾牌、手持及 YDM 背部装备；非附魔物品按资源包规则显示，同 shader 不同参数保持独立。
- [ ] 旋转角色、使武器靠近身体边缘：物品本体与外扩描边均受完整角色深度遮挡；外圈可见，透明区域无黑色矩形或暗边。
- [ ] 分别关闭 `first_person`、`gui`、`armor`、`third_person`，按上表检查归属；返回世界后手持和世界描边正常，无残影。
- [ ] GUI 缩放 2 → 3、窗口调整、暂停恢复、关闭再打开背包及 F3+T 后不丢失描边、错位或沿用旧深度；持续显示时资源稳定复用。
- [ ] 1.21.1–1.21.5 检查旧版帮助方法的裁剪区域与无裁剪调用；1.21.8+ 检查 PIP。26.2 分别验证原生 reverse-Z 和 Iris forward-Z。

预览复用世界 shader，不要求资源包新增文件；预览深度、投影和资源独立于世界 / SR。

## Iris / TAA

- [ ] 不装 Iris、开启/关闭光影、切换光影包均正常；阴影 pass 不出现额外发光，可用的 Sodium 配置页能控制全部七个开关。
- [ ] 开启光影后复核身体、墙体、第一人称手部和轮廓外扩的遮挡；远处、贴墙一像素边界、斜坡及地平线处不穿透、不整体消失。
- [ ] TAA 开/关与相机静止/移动组合下，半遮挡物品无水波状抖动。精确路径不扩大遮挡边界，保守回退不造成持续穿墙。
- [ ] iterationRP 内置 FSR 测试 `FSR2_SCALE=1/2/3/4`、`-1` 和自定义尺寸，并包含 1919×1079 奇数窗口；逐轴对齐，不能误报精确重放。
- [ ] 所有实际 program / fallback 的 jitter 一致才允许精确路径；混合零 jitter、非标准表达式或缺失参数应回退。标准 SR 定义和已证明的内置 FSR 路径无需额外 `gallium.json`。

采样规则见 [Outline occlusion sampling](SHADER_PACK_COMPATIBILITY.md#outline-occlusion-sampling) 和 [First-person foreground priority](SHADER_PACK_COMPATIBILITY.md#first-person-foreground-priority)。

## Super Resolution

定义读取覆盖全部构建目标；SR 0.9+ 渲染主线的发布阻断目标为 **1.21.1、1.21.11、26.1.2、26.2**。其他目标不因此获得旧 SR 渲染生命周期兼容认证。

### 定义与坐标

修改协议或投影逻辑时，除单测外核对实际 Iris profile 和采样区域：

- [ ] legacy / v1 / v2 / v3 均可读取；多文件选择 `v3 → v2 → v1 → superresolution.json`。首选文件损坏或 schema 不符时拒绝使用，不退回旧文件。
- [ ] 目录、根级 `shaders/` zip 和外层目录 zip 均取实际 shader root；维度 profile、`source=mod`、pack 的 `const/uniform/variable` 正确，缺值或 NaN 安全回退。
- [ ] `-1/-2`、省略 region、非零 origin、显式区域及奇数尺寸正确；负 origin、零尺寸或小于 `-2` 的尺寸被拒绝。默认 region 为 `[0,0,-1,-1]`。
- [ ] 区域四边均不贴物理纹理边界时，mask / mask depth / scene depth 和 3×3 采样均不得越界；正负 sub-pixel jitter 只改变采样覆盖，不移动整数区域边界或二次裁剪绝对坐标。
- [ ] 安装/不安装 SR 时，Iris 环境及光影 boolean/string option 选择一致；当前分支引用不可取得的 `SR_*` 动态宏应拒绝使用，未选中分支及未访问的 `#elif` 不误伤结果。
- [ ] 活动 SR 的比例默认优先于旧内置 FSR hint；仅 `override_sr_definition=true` 允许完整覆盖。未启用外部 SR 时仍能从语义输入和实际 viewport 获取内置 FSR 比例。
- [ ] 像素 jitter 按 `2 * offset / screenExtent` 逐轴换算；v2/v3 的精确值与实际 Iris 调用一致。活动 SR 的 v1/legacy 保留保守深度策略，不因调用点推断提升为 post-upscale 精确路径。

### 渲染与生命周期

- [ ] CaptureMode A/B/C × 比例 1.0/0.75/0.6667/0.5：世界与第一人称描边只合成一次，不增亮；世界深度正确重采样，前景仍遮挡世界描边。
- [ ] Iris shadercompat 与无 Iris 路径均无尺寸失配、穿墙或黑帧；切换算法、窗口/全屏、最小化及重载不复用旧附件。
- [ ] 只有同帧、同算法的 dispatch start/finish 和输出/显示尺寸全部匹配才进入显示空间重放；事件或投影缺失、resize 时安全回退。
- [ ] 世界描边在 entity outline / PostChain 后、hudless capture 前合成；首帧不丢失，最终 hook 失配时只告警一次并恢复备用路径。
- [ ] Vulkan presentation 的 hudless/final capture 均含正确阶段描边；帧生成不隔帧丢失。取消 `flipFrame` 后下一帧仍完成 DynamicUniforms / LevelRenderer 清理，UBO 容量不持续翻倍。
- [ ] 26.2 分别验证 OpenGL 原生 reverse-Z 与 Iris forward-Z；测试结束后正常关闭窗口，客户端应退出。

## 重载、压力与版本升级

按改动风险选择执行；未执行的长稳项必须记为未覆盖。

- [ ] F3+T 及自定义资源包重载键均恢复正确效果，自定义键保留 GUI 状态；连续重载 30 次、分辨率切换 20 次后资源不持续增长。
- [ ] 100+ 独立发光掉落物、50+ 装备单帧突增时无 OOM 或驱动重置，捕获池按预算回收；记录开关前后的帧时间和内存峰值。
- [ ] 创造物品栏滚动及高频 GUI 连续操作 5 分钟无持续增长；涉及时间参数时执行 24 小时长稳，检查跳变。
- [ ] 检查版本专用 Mixin 的注册/剔除、私有 shader 兼容性及测试驱动未打入 JAR。Minecraft 升级时复核 `GlCommandEncoderMixin` 的拷贝坐标修补，避免上游修复后重复偏移。
