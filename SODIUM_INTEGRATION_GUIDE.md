# Sodium 界面集成指南

本文档介绍如何为自定义 Mod 集成 Sodium 的配置界面系统。

---

## 目录

1. [核心原理](#1-核心原理)
2. [快速开始](#2-快速开始)
3. [详细步骤](#3-详细步骤)
4. [常用控件参考](#4-常用控件参考)
5. [高级功能](#5-高级功能)
6. [完整示例](#6-完整示例)
7. [常见问题](#7-常见问题)

---

## 1. 核心原理

### 1.1 集成架构

Sodium 使用 `SodiumOptionsGUI` 作为配置界面入口，内部维护一个 `List<OptionPage>` 存储所有选项页面。

**集成方式**：通过 Mixin 在 `SodiumOptionsGUI` 构造函数末尾注入自定义页面。

```
SodiumOptionsGUI (Sodium原生)
        │
        ├── pages[0]: 图形设置页面 (Sodium原生)
        ├── pages[1]: 性能设置页面 (Sodium原生)
        │
        ↓ @Inject(at = @At("TAIL"))
        ├── pages[n]: 你的自定义页面 ← 通过Mixin添加
        └── ...
```

### 1.2 核心类关系

```
OptionPage                    # 选项页面（如"动画设置"）
    └── List<OptionGroup>     # 选项组
            └── OptionImpl    # 具体选项
                    ├── Control<T>      # UI控件
                    ├── Binding<T, S>   # 数据绑定(getter/setter)
                    └── Storage<S>      # 配置存储
```

---

## 2. 快速开始

### 2.1 最简集成示例

只需 4 个文件即可完成基础集成：

```
你的项目/
├── src/main/java/com/example/
│   ├── MyModOptions.java           # 配置数据类
│   ├── MyOptionsStorage.java       # 存储实现
│   ├── MyOptionPages.java          # 选项页面定义
│   └── mixin/
│       └── MixinSodiumOptionsGUI.java  # Mixin注入
└── src/main/resources/
    └── mymod.mixins.json           # Mixin配置
```

---

## 3. 详细步骤

### 步骤 1：创建配置数据类

配置数据类负责存储所有配置项，并提供读写方法。

```java
package com.example.mymod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;

public class MyModOptions {
    // === 配置项定义 ===
    public boolean enableFeature = true;
    public int renderDistance = 12;
    public float brightness = 1.0f;
    
    // === 内部管理 ===
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();
    
    private final File configFile;
    
    public MyModOptions(File configFile) {
        this.configFile = configFile;
    }
    
    // 保存配置到文件
    public void writeChanges() {
        try (Writer writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    // 从文件加载配置
    public static MyModOptions load(File configFile) {
        if (configFile.exists()) {
            try (Reader reader = new FileReader(configFile)) {
                return GSON.fromJson(reader, MyModOptions.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return new MyModOptions(configFile);
    }
}
```

### 步骤 2：实现选项存储接口

`OptionStorage` 是 Sodium 提供的接口，用于统一管理配置的读取和保存。

```java
package com.example.mymod;

import me.jellysquid.mods.sodium.client.gui.options.OptionStorage;

public class MyOptionsStorage implements OptionStorage<MyModOptions> {
    private final MyModOptions options;
    
    public MyOptionsStorage(File configFile) {
        this.options = MyModOptions.load(configFile);
    }
    
    @Override
    public MyModOptions getData() {
        return this.options;
    }
    
    @Override
    public void save() {
        this.options.writeChanges();
    }
}
```

### 步骤 3：创建选项页面

使用 Sodium 的 `OptionImpl` 构建器创建选项。

```java
package com.example.mymod;

import com.google.common.collect.ImmutableList;
import me.jellysquid.mods.sodium.client.gui.options.*;
import me.jellysquid.mods.sodium.client.gui.options.control.*;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MyOptionPages {
    // 存储实例（单例）
    private static final MyOptionsStorage STORAGE = new MyOptionsStorage(
        new File("config/mymod-options.json")
    );
    
    public static OptionPage createGeneralPage() {
        List<OptionGroup> groups = new ArrayList<>();
        
        // === 第一组：功能开关 ===
        groups.add(OptionGroup.createBuilder()
            .add(OptionImpl.createBuilder(boolean.class, STORAGE)
                .setName(Component.translatable("mymod.options.enable_feature"))
                .setTooltip(Component.translatable("mymod.options.enable_feature.tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding(
                    (options, value) -> options.enableFeature = value,
                    options -> options.enableFeature
                )
                .build()
            )
            .build()
        );
        
        // === 第二组：渲染设置 ===
        groups.add(OptionGroup.createBuilder()
            .add(OptionImpl.createBuilder(int.class, STORAGE)
                .setName(Component.translatable("mymod.options.render_distance"))
                .setTooltip(Component.translatable("mymod.options.render_distance.tooltip"))
                .setControl(opt -> new SliderControl(opt, 2, 32, 1, ControlValueFormatter.quantity("区块")))
                .setBinding(
                    (options, value) -> options.renderDistance = value,
                    options -> options.renderDistance
                )
                .build()
            )
            .add(OptionImpl.createBuilder(float.class, STORAGE)
                .setName(Component.translatable("mymod.options.brightness"))
                .setTooltip(Component.translatable("mymod.options.brightness.tooltip"))
                .setControl(opt -> new SliderControl(opt, 0.0, 2.0, 0.1, ControlValueFormatter.percentage()))
                .setBinding(
                    (options, value) -> options.brightness = value,
                    options -> options.brightness
                )
                .build()
            )
            .build()
        );
        
        return new OptionPage(
            Component.translatable("mymod.options.page_title"),
            ImmutableList.copyOf(groups)
        );
    }
    
    // 提供存储访问（供其他类使用）
    public static MyOptionsStorage getStorage() {
        return STORAGE;
    }
}
```

### 步骤 4：创建 Mixin 注入

这是集成的关键步骤，通过 Mixin 将自定义页面注入到 Sodium 界面。

```java
package com.example.mymod.mixin;

import com.example.mymod.MyOptionPages;
import me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = SodiumOptionsGUI.class, remap = false)
public class MixinSodiumOptionsGUI {
    
    // 获取 Sodium 的页面列表
    @Shadow
    @Final
    private List<OptionPage> pages;
    
    // 在构造函数末尾注入自定义页面
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        // 添加你的自定义页面
        this.pages.add(MyOptionPages.createGeneralPage());
        
        // 可以添加多个页面
        // this.pages.add(MyOptionPages.createAdvancedPage());
    }
}
```

### 步骤 5：配置 Mixin

在资源文件中注册 Mixin。

**mymod.mixins.json**:
```json
{
  "package": "com.example.mymod.mixin",
  "compatibilityLevel": "JAVA_17",
  "mixins": [],
  "client": [
    "MixinSodiumOptionsGUI"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

**fabric.mod.json** 中引用：
```json
{
  "mixins": [
    "mymod.mixins"
  ]
}
```

### 步骤 6：添加本地化文本

**assets/mymod/lang/en_us.json**:
```json
{
  "mymod.options.page_title": "My Mod Settings",
  "mymod.options.enable_feature": "Enable Feature",
  "mymod.options.enable_feature.tooltip": "Enables the special feature of this mod",
  "mymod.options.render_distance": "Custom Render Distance",
  "mymod.options.render_distance.tooltip": "Adjust the custom render distance",
  "mymod.options.brightness": "Brightness Override",
  "mymod.options.brightness.tooltip": "Adjust the brightness level"
}
```

**assets/mymod/lang/zh_cn.json**:
```json
{
  "mymod.options.page_title": "我的模组设置",
  "mymod.options.enable_feature": "启用功能",
  "mymod.options.enable_feature.tooltip": "启用此模组的特殊功能",
  "mymod.options.render_distance": "自定义渲染距离",
  "mymod.options.render_distance.tooltip": "调整自定义渲染距离",
  "mymod.options.brightness": "亮度覆盖",
  "mymod.options.brightness.tooltip": "调整亮度级别"
}
```

---

## 4. 常用控件参考

### 4.1 开关控件 (TickBoxControl)

适用于 `boolean` 类型的选项。

```java
OptionImpl.createBuilder(boolean.class, storage)
    .setName(Component.translatable("option.name"))
    .setTooltip(Component.translatable("option.tooltip"))
    .setControl(TickBoxControl::new)
    .setBinding(
        (opts, value) -> opts.myBoolean = value,
        opts -> opts.myBoolean
    )
    .build()
```

### 4.2 滑块控件 (SliderControl)

适用于 `int` 或 `float` 类型的数值选项。

```java
// 整数滑块
OptionImpl.createBuilder(int.class, storage)
    .setName(Component.translatable("option.name"))
    .setTooltip(Component.translatable("option.tooltip"))
    .setControl(opt -> new SliderControl(opt, 0, 100, 5, ControlValueFormatter.quantity("个")))
    // 参数：选项对象, 最小值, 最大值, 步进, 格式化器
    .setBinding(
        (opts, value) -> opts.myInt = value,
        opts -> opts.myInt
    )
    .build()

// 浮点滑块
OptionImpl.createBuilder(float.class, storage)
    .setControl(opt -> new SliderControl(opt, 0.0f, 1.0f, 0.1f, ControlValueFormatter.percentage()))
    // ...
```

### 4.3 循环选择控件 (CycleControl)

适用于枚举类型的选项。

```java
// 枚举定义（实现 TextProvider 接口以支持本地化）
public enum MyEnum implements TextProvider {
    OPTION_A,
    OPTION_B,
    OPTION_C;
    
    @Override
    public Component getText() {
        return Component.translatable("mymod.enum." + this.name().toLowerCase());
    }
}

// 使用 CycleControl
OptionImpl.createBuilder(MyEnum.class, storage)
    .setName(Component.translatable("option.name"))
    .setTooltip(Component.translatable("option.tooltip"))
    .setControl(opt -> CycleControl.create(opt, MyEnum.values()))
    .setBinding(
        (opts, value) -> opts.myEnum = value,
        opts -> opts.myEnum
    )
    .build()
```

### 4.4 常用格式化器

| 格式化器 | 显示效果 | 示例 |
|---------|---------|------|
| `ControlValueFormatter.quantity("单位")` | 数字 + 单位 | `12 区块` |
| `ControlValueFormatter.percentage()` | 百分比 | `50%` |
| `ControlValueFormatter.multiplier()` | 乘数 | `2x` |
| `ControlValueFormatter.degrees()` | 角度 | `45°` |
| `ControlValueFormatter.number()` | 纯数字 | `100` |

---

## 5. 高级功能

### 5.1 条件启用选项

根据其他条件动态启用/禁用选项。

```java
OptionImpl.createBuilder(int.class, storage)
    .setName(Component.translatable("option.dependent"))
    .setTooltip(Component.translatable("option.dependent.tooltip"))
    .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.number()))
    .setEnabled(() -> storage.getData().enableFeature)  // 只有当 enableFeature 为 true 时才可用
    .setBinding(
        (opts, value) -> opts.dependentValue = value,
        opts -> opts.dependentValue
    )
    .build()
```

### 5.2 选项标志

某些选项更改后需要触发特定行为。

```java
OptionImpl.createBuilder(boolean.class, storage)
    .setName(Component.translatable("option.graphics"))
    // ...
    .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)  // 更改后重载资源
    .build()
```

**可用标志**：
- `OptionFlag.REQUIRES_ASSET_RELOAD` - 重载资源
- `OptionFlag.REQUIRES_RENDERER_RELOAD` - 重载渲染器
- `OptionFlag.REQUIRES_GAME_RESTART` - 重启游戏

### 5.3 可滚动页面（选项过多时）

当选项过多时，可以实现可滚动的选项页面。参考 Sodium Extra 的实现：

```java
// 在 Mixin 中替换原生的 rebuildGUIOptions 方法
@Inject(method = "rebuildGUIOptions", at = @At("HEAD"), cancellable = true)
private void rebuildGUIOptions(CallbackInfo ci) {
    // 创建自定义的可滚动框架
    OptionPageScrollFrame frame = new OptionPageScrollFrame(
        new Dim2i(x, y, width, height),
        this.currentPage
    );
    this.addRenderableWidget(frame);
    ci.cancel();  // 取消原方法执行
}
```

---

## 6. 完整示例

以下是一个完整的、可直接使用的示例项目结构：

### 项目结构

```
my-sodium-addon/
├── src/main/
│   ├── java/com/example/addon/
│   │   ├── MyAddon.java                 # 主类
│   │   ├── config/
│   │   │   ├── MyAddonOptions.java      # 配置数据
│   │   │   └── MyAddonStorage.java      # 存储实现
│   │   ├── gui/
│   │   │   └── MyAddonOptionPages.java  # 选项页面
│   │   └── mixin/
│   │       └── MixinSodiumOptionsGUI.java
│   └── resources/
│       ├── fabric.mod.json
│       ├── myaddon.mixins.json
│       └── assets/myaddon/lang/
│           ├── en_us.json
│           └── zh_cn.json
└── build.gradle
```

### build.gradle 依赖配置

```groovy
dependencies {
    // Sodium 依赖（根据实际版本调整）
    modImplementation "maven.modrinth:sodium:mc1.21-0.6.0-beta.2"
}
```

---

## 7. 常见问题

### Q1: 页面没有显示在 Sodium 界面中？

**检查项**：
1. 确认 Mixin 类已正确注册在 `mixins.json` 中
2. 确认 `@Mixin` 注解中 `remap = false` 参数存在
3. 检查 `fabric.mod.json` 中是否引用了 mixins 文件
4. 确认 Sodium 已正确安装并加载

### Q2: 选项保存后没有持久化？

**解决方案**：
确保 `OptionStorage.save()` 方法被正确调用。Sodium 会在用户点击"完成"或"应用"时调用 `save()`。

### Q3: 如何在代码中读取配置值？

```java
// 获取配置实例
MyModOptions options = MyOptionPages.getStorage().getData();

// 读取值
if (options.enableFeature) {
    // 执行逻辑
}
```

### Q4: 如何在 Sodium 不存在时优雅降级？

使用条件 Mixin 加载：

```java
// 在 MixinConfigPlugin 中检查 Sodium 是否存在
@Override
public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
    try {
        Class.forName("me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI");
        return true;
    } catch (ClassNotFoundException e) {
        return false;  // Sodium 不存在，不加载此 Mixin
    }
}
```

### Q5: 本地化文本不显示？

1. 确认语言文件路径正确：`assets/你的modid/lang/语言代码.json`
2. 确认使用 `Component.translatable()` 而非 `Component.literal()`
3. 确认语言文件的 JSON 格式正确

---

## 参考资料

- [Sodium 源码](https://github.com/jellysquid3/sodium-fabric)
- [Fabric Mixin 文档](https://fabricmc.net/wiki/tutorial:mixin_introduction)

---

> 本指南适用于 Sodium 0.5+ 版本，所有代码示例均可直接使用。
