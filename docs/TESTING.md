# Gallium 游戏内渲染测试清单

> 适用版本：Minecraft 1.21.1、1.21.3、1.21.4、1.21.5、1.21.8、1.21.10、1.21.11、26.1.2、26.2 + Fabric Loader
> 必备依赖：Fabric API、Sodium、Gallium
>
> 涉及 capture / mixin / pipeline / GUI 渲染的改动，应在**全部受影响版本**上重跑相关章节（B/C GUI glow、D Iris 深度策略、G Iris/Sodium 组合）。各版本的 API 形态差异较大（sampler 状态、深度测试、GUI 图集路径），逐版本验证是必要的。
>
> 版本差异提示：1.21.10 搭配的 Sodium 0.7.3 早于 config API，因此**该版本没有 Sodium 配置 UI**——唯一可达的用户控制是未绑定的资源包重载键位（或 F3+T）。1.21.11+ 才有 Sodium 设置页。

本文档只覆盖**游戏内渲染表现**。解析、配置文件 IO、错误隔离等纯逻辑由 `src/test` 下的 JUnit 单测覆盖。

### Iris / Sodium 依赖基线（2026-09-09）

按 [Iris 官方发行列表](https://modrinth.com/mod/iris/versions) 中各 Minecraft 版本的最新稳定版核对发行包内 `fabric.mod.json` 的 `depends.sodium`，同时核对 [Sodium 发行包](https://modrinth.com/mod/sodium/versions) 的 `breaks.iris`，选择双方都接受的最新稳定版。仅检查 Iris 单向依赖范围会漏掉 Sodium 对旧 Iris 的排斥规则；实际验证范围见下文。

| Minecraft | Iris | Iris 接受的 Sodium | 开发运行 Sodium |
|---|---|---|---|
| 1.21.1 | 1.8.8 | 0.6.x | 0.6.13 |
| 1.21.3 | 1.8.1 | 0.6.x | 0.6.8（0.6.9+ 反向要求 Iris ≥1.8.7） |
| 1.21.4 | 1.8.8 | 0.6.x | 0.6.13 |
| 1.21.5 | 1.8.11 | 0.6.x | 0.6.13（该 MC 版本仅有 beta 发布，保留现有版本） |
| 1.21.8 | 1.9.6 | 0.7.x | 0.7.3 |
| 1.21.10 | 1.9.7 | 0.7.x | 0.7.3 |
| 1.21.11 | 1.10.7 | 0.8.x | 0.8.12（0.8.13+ 排斥 Iris ≤1.10.7） |
| 26.1.2 | 1.11.3 | 0.9.x | 0.9.1 |
| 26.2 | 1.11.2 | 0.9.x | 0.9.1 |

1.21.1 另以 Sodium 0.8.13 作为仅编译依赖，保留 0.8 配置 API 支持；开发运行使用 0.6.13，以适配 Iris 1.8.8。该版本需分别验证 Sodium 0.6 的旧配置页和不安装 Iris 时 Sodium 0.8 的新配置页。

本次依赖升级验证（Windows / NVIDIA RTX 5090 D）：

- 最终依赖配置下全版本 `build` 通过；JUnit 6.1.3 共 2717 项测试，零失败、零错误、零跳过。
- 上表九个最终组合均通过实际客户端启动检查；1.21.3 和 1.21.11 在发现并修正 Sodium 的反向版本限制后重新验证通过。
- 1.21.1 + Sodium 0.6.13、1.21.3 + Sodium 0.6.8、1.21.11 + Sodium 0.8.12、26.1.2 + Sodium 0.9.1 的 Gallium 配置页均实际打开检查。
- 1.21.1 不安装 Iris、改用 Sodium 0.8.13 的独立运行也通过启动与 Gallium 新配置页检查，保留 0.6 / 0.8 双版本配置兼容。
- 26.2 在独立测试世界副本中启用 BSL 10.1.3 和 GalliumOrdinaryTest 资源包，检查了第一人称手持、背包及掉落物描边；世界与 GUI 管线正常创建，未发现渲染崩溃。
- 以上为依赖升级的启动、配置与代表性渲染冒烟验证，不覆盖下文全部遮挡、TAA、SR 和资源重载场景。开发账号产生的 Realms / 玩家证书认证错误不影响这些本地检查。

---

## A. 准备工作

- [ ] 用资源包提供 `assets/gallium/item_effects.json` 和世界/GUI 描边 shader。
- [ ] 截屏对照：在不开 Gallium 的相同视角先截一张作为基线。
- [ ] 客户端启动日志确认 `Loaded item effects: N rules, M shaders` 出现，且数量与 json 一致。
- [ ] 检查无 mixin apply 失败、无 `defaultRequire=1` 报错。

---

## B. 主渲染场景

物品发光描边在以下场景下应正确显示，且改变视角后描边贴合物品轮廓。每项至少切两个分辨率：窗口 + 全屏。

- [ ] 第一人称主手发光物品。
- [ ] 第一人称副手发光物品（盾牌槽）。
- [ ] 第三人称（F5）自己手持发光物品。
- [ ] 第三人称视角下其他玩家手持发光物品。
- [ ] 怪物（僵尸/骷髅）手持发光物品（命令：`/summon zombie ~ ~ ~ {HandItems:[...]}`）。
- [ ] 玩家穿戴发光盔甲（每个槽：头/胸/腿/靴）。
- [ ] 玩家自己穿盔甲（第三人称下也应可见）。
- [ ] 怪物穿发光盔甲。
- [ ] 掉落物（`/give` 后丢出）。
- [ ] 物品框（普通 + 发光物品框）中的发光物品。
- [ ] 展示架（Shelf）每个槽位中的发光物品。
- [ ] GUI 内发光物品：背包、热键栏、箱子、合成台、潜影盒、村民交易、铁砧。

---

## C. 同 shader 多变体（GUI 关键回归）

防止 `GuiGlowElementPipeline` 把不同 params 的规则错误统一：

- [ ] 写两条规则，**shader 同名**但 `params` 不同（例如 `glow` 配 `red` 与 `blue` 两组颜色）。
- [ ] GUI 中同时显示这两类物品 → 它们应显示**各自的颜色/强度**，不应被统一成同一种。
- [ ] 世界中同时显示这两类物品 → 同样应各自独立。
- [ ] 资源 `F3 + T` 重载后两条规则仍然独立工作。

---

## D. 遮挡（Occlusion）

发光描边的深度合成依赖于"第一人称用 mask depth、世界空间用 scene depth、Iris 启用时用早期捕获的 sceneDepthTarget"三条路径，重点验证三者表现一致且边界条件正常。

### D-1 第一人称（设计上不被世界几何遮挡）

- [ ] 第一人称手持发光物品，**靠近墙壁让物品穿入墙体** → 描边应仍**完整显示**，不应被墙体几何切断。
- [ ] 第一人称玩家手臂/地图渲染期间不应触发 capture（`renderPlayerArm` / `renderMapHand` 的 `beginSuppress` 路径）—— 不应在玩家自己的胳膊轮廓上出现额外发光。
- [ ] 第一人称下打开 GUI 同时手持发光物品 → 第一人称世界发光不与 GUI 内描边互相干扰。

### D-2 第三人称 / 世界空间（应被世界遮挡）

- [ ] 让其他玩家走到墙后 → 该玩家手持发光物品的描边应被墙正确遮挡，不穿墙。
- [ ] 把发光掉落物丢入箱子并关闭箱子 → 描边不应透过箱子可见。
- [ ] 物品框安装在墙的另一面 → 从这一侧看不应看到描边。
- [ ] 展示架隔着玻璃看 → 玻璃透过部分应可见，背后实体方块部分应被遮挡。
- [ ] 怪物站在树叶 / 雕花玻璃后 → 半透明区域的遮挡应与原版描边一致（不应整体被剔除）。
- [ ] 自己的盔甲在第三人称视角下被自身身体某些部分遮挡 → 描边在身体几何处应正确截断。
- [ ] 第三人称手持物品的可见边缘靠近玩家身体轮廓时，描边扩张部分也应被身体截断；不能只裁掉物品 mask 本体后又让外扩描边覆盖到身体上。
- [ ] 掉落物、物品框、展示架、实体手持物和盔甲分别贴近方块、实体及另一件世界物品时，所有外扩描边都应在较近几何处截断，不能有局部嵌入。

### D-3 半遮挡 / 边界过渡

- [ ] 移动相机让发光物品从**全可见 → 半遮挡 → 全遮挡** → 描边应平滑过渡，无闪烁、无突变。
- [ ] 极近距离物品（< 0.1m，比如贴脸）→ 描边不爆裂、不出现 z-fighting。
- [ ] 极远距离物品 → 描边在精度可接受范围内继续显示。
- [ ] 视锥剔除边界（屏幕边缘）→ 物品进入/离开视锥时描边无突现。

### D-4 多物品互不干扰

- [ ] 多个发光物品**前后排列**（如一排掉落物连成一线）→ 前面的物品不应被后面物品的描边覆盖、互相也不应混色。
- [ ] 不同 shader 的发光物品同屏显示 → 各自合成正确，不互相串色。

### D-5 水 / 特殊介质

- [ ] 发光物品丢入水中 → 描边可见且不被水体错误遮挡。
- [ ] 在水中第一人称看手持发光物品 → 描边正常。
- [ ] 在岩浆/熔岩中（如果游戏允许放）→ 描边表现合理。

### D-6 Iris shader 路径下的遮挡

- [ ] 启用 shader pack 后，世界空间遮挡仍然成立（`GlowCaptureManager.captureSceneDepth` 在主深度被 clearDepthTexture 清掉前提前抓取；有完整 `temporal_jitter` 声明时重放精确跟随当帧 TAA，未知 pack 才使用 3x3 最远值池化或旧版自适应 bias 回退）。
- [ ] 启用 shader pack 后，在第一人称用手臂或手持物遮住发光的掉落物、物品框或展示架 → 世界描边应在 Iris 手部轮廓处截断；Iris 手部在 `LevelRenderer` 内、深度清除前完成，因此应存在于 `sceneDepthTarget`。
- [ ] Iris 阴影 pass 期间**不出现**任何发光（`IrisCompat.isShadowPass()` 应令所有 mixin 短路）。
- [ ] 切换不同 shader pack 后遮挡仍正确，不出现穿墙描边。
- [ ] 对带 TAA / 超分辨率的 shader pack，分别在 TAA **开/关**、相机**静止/移动**时观察半遮挡物品：描边轮廓不应随帧产生水波状抖动或断续闪烁。iterationRP 内置 `FSR2_SCALE=1/2/3/4` 应自动取得正确viewport；只有 mask 与 Terrain/Water/DH scene-depth 的全部实际 Iris program/fallback 调用取得同一 uniform（或全为零）时才报告 exact，混合零 jitter 分支必须保守回退。`-1`与自定义渲染分辨率也不得误报exact。
- [ ] **远距离**和**贴墙**两组场景中，物品被墙/地形遮挡后其描边不应穿过遮挡物。重点看物体刚进入或离开墙边的一像素邻域：精确路径不得扩大遮挡物轮廓；未知 pack 的池化回退也不能演变为持续穿墙。
- [ ] 用奇数窗口尺寸（例如 1919x1079）分别测试 `FSR2_SCALE=1/2/3/4`：描边 X/Y 均贴合，不因内部尺寸分别向下取整而出现单轴偏移。
- [ ] 将发光物品放在斜坡、地面与天空交界处，旋转相机并改变视距：描边既不整体消失（"仅地平线可见"），也不出现随镜头移动的波纹。
- [ ] 物品发光放在地面（开启光影），描边应正常出现而非整体消失。精确 Iris 重放会清空 mask depth，但 FSH 必须在每个扩张来源 texel 上与场景深度比较，不能让被遮挡的来源从边缘漏出。

> 实现约束：Gallium 不通过名字猜测 `taaJitter`、`taaOffset`、`TAAJitter` 等自定义 uniform。标准 SR 可使用 schema `source_config`；内置 FSR 只有在语义输入、整数 extent、live viewport、全部实际 program/fallback 的受限仿射公式和调用参数同时一致后，才读取已证明的 shader-facing uniform。非标准顺序/表达式仍需完整 `gallium.json`，否则使用有界深度池化。
>
> 资源包 shader 在扩张轮廓时，必须先将每个来源 mask texel 的 `MaskDepthSampler` 与其自身的 `SceneDepthSampler` 比较，再与当前输出像素处 `MaskDepthSampler` 与 `SceneDepthSampler` 的较近值比较。前者会剔除精确 Iris 重放中原本被遮挡的来源；后者会裁掉落在前景几何上的外扩描边。无 Iris 时场景深度补充清除后渲染的 vanilla 手部，Iris 时则读取已包含 Iris 自定义手部的清除前快照。比较时不要添加固定 raw-depth 偏移。参考 `docs/SHADER_PACK_COMPATIBILITY.md` 的 Outline occlusion sampling。

---

## E. 配置开关单项验证（Sodium 设置 → Gallium 标签）

每个开关：先 ON 验证发光，再 OFF 验证消失。**关掉的类别不能影响其他类别**。

- [ ] `glow_enabled` 总开关：OFF 时所有发光消失，ON 恢复。
- [ ] `first_person`：OFF 仅第一人称消失，第三人称仍发光。
- [ ] `third_person`：OFF 仅第三人称玩家手持消失（第一人称、其他实体、GUI 都正常）。
- [ ] `other_entities`：OFF 时怪物手持、物品框、展示架都不发光（这三类共用此开关，是已知设计）。
- [ ] `dropped_items`：OFF 仅地上掉落物不发光。
- [ ] `armor`：OFF 时盔甲不发光，但同一玩家手持物品仍发光。
- [ ] `gui`：OFF 时 GUI 内不发光，世界中仍发光。

---

## F. 资源重载（GPU 生命周期）

- [ ] 触发原版 `F3 + T` 资源重载 → 配置生效，发光仍正确显示。
- [ ] 自定义 `RELOAD_RESOURCE_PACK_KEY`（默认未绑定，需先在控制设置里绑定）：按下后 GUI 状态保留。
- [ ] 切换不同分辨率（窗口 → 全屏 → 拖拽窗口边缘）后描边仍正确，无错位、无 GL 错误。
- [ ] **反复 `F3 + T` 重载 30 次**，观察 RAM/VRAM 应稳定 —— 持续增长说明资源泄漏未修复。
- [ ] 反复在不同分辨率间切换 20 次（拖拽窗口边缘）→ VRAM 稳定。

---

## G. Iris / Sodium 兼容

- [ ] 装上 Iris + 一个 shader pack（例如 ComplementaryReimagined），发光效果仍工作。
- [ ] Shader pack 启用时第一人称手持物品发光视觉合成正确（参考 D-6 遮挡部分）。
- [ ] Shader pack 切换（Iris 选择面板）后无崩溃。
- [ ] **不装 Iris** 时模组照常工作。
- [ ] 装 Sodium：进入 Sodium 设置 → 选项页 → "Gallium" 标签存在，所有 7 个开关都能切换。

---

## H. 性能 / 长稳

- [ ] 同时显示 100+ 个发光掉落物（`/give @p ... 64` 然后丢成堆），FPS 下降可接受、无内存爆炸。
- [ ] **高密度 burst**：50+ 个发光物品/盔甲在单帧同屏（例如 `/summon` 一群佩戴发光盔甲的怪物 + 周围撒发光掉落物）。瞬时 VRAM 峰值在驱动可承受范围内，下一帧 `POOL_HIGH_WATER_MARK` 的回收路径生效，VRAM 回落。无 GL_OUT_OF_MEMORY、无驱动 reset。
- [ ] 创造模式打开物品栏（GUI 大量物品）滚动 5 分钟，VRAM 不持续增长。
- [ ] **跑图 24 小时**，shader 时间相关参数（`u_time` 等）无可见跳跃。
- [ ] F3 帧时间观察：开/关发光前后 GPU 时间差合理（每个发光物品 < 0.5ms 期望）。
- [ ] 长时间打开高频 GUI（如村民交易页面频繁刷新），VRAM 稳定。

---

## I. 已知设计取舍（非 bug）

以下行为是有意为之，**不需要测试为"问题"**：

1. 第一人称发光**不被世界几何遮挡**（使用自己的 mask depth）。这是为了避免在第一人称下因物品穿墙触发的视觉异常。
2. `ItemCondition.Path.EXISTS` 等同于 `ItemStack.has(component)`，会返回 prototype 默认值的存在性，不仅是显式覆盖。
3. 物品框、展示架、怪物手持物品共用 `other_entities` 开关。
4. `DuplicatingSubmitNodeStorage` 直接 submit（不走 `order(int)`）的调用会被复制到 order=0 层。
5. `GlCommandEncoderMixin` 修补的是 vanilla `copyTextureToTexture` 的 size→absolute 坐标 bug（1.21.10、1.21.11、26.1.2 三个版本均存在）。该修复在零偏移拷贝时是 no-op，故无条件应用于所有版本；未来 Mojang 修复上游后需重新加版本门控，否则会双重偏移。

---

## J. Super Resolution 定义与主线渲染兼容

### J-1 全版本定义读取

以下检查在全部 Gallium 构建执行，不能按 Minecraft 版本删减 parser：

- [ ] 同一光影包分别只放 legacy、v1、v2、v3 定义，Gallium均能选中正确 profile。
- [ ] 多文件共存时严格选择 `v3 → v2 → v1 → superresolution.json` 的第一个文件。
- [ ] 目录包、根级 `shaders/` zip及带外层目录的zip都从Iris实际 shader root读取。
- [ ] 首选文件损坏、schema未知或文件名/schema不一致时 fail closed，不回退旧文件。
- [ ] `-1/-2`、显式 region、非零 origin及 1919×1079 奇数尺寸的 X/Y比例正确。至少使用一个
      四边均不贴物理纹理边界的region，分别检查左/右/上/下边缘：mask、mask depth和scene
      depth均只能访问`origin .. origin+size`，3×3保守采样不得泄漏到相邻packed region。
- [ ] 在上述非零origin用例中分别施加正、负sub-pixel jitter；合法region边界保持不动，
      只有当帧采样坐标及边缘的sub-pixel覆盖率移动，不能把整数region起止位置移到前一列/行
      或把absolute coordinate再次按`0 .. activeSize`裁剪。
- [ ] `source=mod`、shaderpack `const/uniform/variable` 均能解析；缺值/NaN回退而不崩溃。
- [ ] 带宏配置使用 SR/Iris环境、当前shaderpack boolean/string option及SR动态宏预处理；
      安装/不安装SR必须选择同一光影选项分支，无法完整预处理时不猜分支。
- [ ] 完全不装SR时，含普通 Iris option/环境宏的定义仍可解析；引用不可取得的`SR_*`
      动态宏时 fail closed，不能按0静默选错分支；但位于未选中的`#ifdef SR_INSTALLED`
      或 shaderpack option 分支内的动态宏不得误伤整份定义，未访问的`#elif`同理。
- [ ] v1/v2/v3省略input/output `region`时统一得到`[0,0,-1,-1]`；X/Y为负数、W/H为0
      或小于`-2`必须fail closed。
- [ ] 标准 SR 光影包不放 `gallium.json` 仍能自动对齐；显式 `gallium.json` 能覆盖自动定义。
- [ ] 活动 SR 的 live scale默认覆盖旧的内置 FSR `gallium.json`；只有
      `override_sr_definition=true` 才允许完整的非标准覆盖。
- [ ] iterationRP启用内置FSR且外部SR inactive/未安装时，从SR profile的语义输入和
      Iris已预处理target extent得到逐轴比例，不需要`gallium.json`。
- [ ] SR像素jitter按`2 * offset / screenExtent`逐轴转换为NDC；v2/v3 exact值必须与
      实际Iris调用参数一致。v1/legacy活动SR不得由调用点提升为post-upscale exact，
      应保留稳定的保守深度路径；同一调用点仍可供光影内置FSR使用。

### J-2 SR 0.9+ 主线

发布阻断目标：1.21.1、1.21.11、26.1.2、26.2。

- [ ] CaptureMode A/B/C分别测试世界物品与第一人称手持物。
- [ ] 比例 1.0、0.75、0.6667、0.5 均只合成一次，亮度不翻倍。
- [ ] 世界深度在全分 mask 中正确重采样；手/前景仍遮挡世界描边。
- [ ] 窗口缩放、全屏、最小化、资源重载和算法切换不复用旧 attachment。
- [ ] Iris shadercompat 与无 Iris hack path 都不出现尺寸不匹配、穿墙或黑帧。
- [ ] shadercompat 只有在本帧同一算法的 dispatch start/finish、输出尺寸及显示尺寸全部
      匹配时才用 display-identity mask；事件缺失、resize或base projection缺失均 fail closed。
- [ ] Vulkan presentation 的 hudless/final capture 都包含正确阶段的世界/GUI描边。
- [ ] 世界描边在entity outline及可选PostChain之后、`FogRenderer.endFrame`/hudless capture之前
      合成；首帧也不得先在renderLevel TAIL绘制后被Iris/SR覆盖。final hook失配时下一帧应
      只告警一次并恢复legacy TAIL，而不是永久丢失描边。
- [ ] Vulkan presentation取消`RenderSystem.flipFrame`时，下一客户端渲染帧补做一次
      DynamicUniforms/LevelRenderer cleanup；进入世界后的UBO容量不得跨帧持续翻倍。
- [ ] 有效帧生成路径中世界描边随真实帧进入 hudless input，不隔帧消失。
- [ ] 26.2 OpenGL/Iris forward-Z与原生 reverse-Z路径分别验证。

非 SR 主线的 Gallium 构建只认证通用定义读取，不声明旧 SR RenderTarget生命周期兼容。
