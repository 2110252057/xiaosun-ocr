# MacroDroid 插件开发指南

> **来源说明**
>
> MacroDroid **没有官方插件开发文档**。本文所有内容来自对以下 APK 的
> 反编译分析（jadx / baksmali），均为**实证结论**而非推测：
>
> | 分析对象 | 提取内容 |
> |---|---|
> | MacroDroid `base.apk`（89 MB，8 个 dex） | 宿主侧实现：如何触发插件、如何读取变量 |
> | PP-OCRv6 插件 APK（本机实测可用） | 插件侧正确写法（含异步回传） |
> | 内置的官方 `locale-sdk` | 协议常量、能力位、结果码 |
>
> 关键类：
> - `com.twofortyfouram.locale.sdk.host.api.Setting` —— 宿主触发插件的核心
> - `com.twofortyfouram.locale.sdk.host.TaskerPlugin` —— 协议常量定义
> - `com.arlosoft.macrodroid.action.LocalePluginAction` —— MacroDroid 的动作实现
> - `com.arlosoft.macrodroid.action.receivers.LocaleTaskerSettingCompleteReceiver`
>   —— **异步结果接收器**（关键）
> - `t6g.b(...)` —— 把回传变量写入宏变量的实际逻辑

---

## 一、整体架构

MacroDroid 的插件机制基于 **Locale/Tasker 插件规范**（`com.twofortyfouram` 系）。
它内置了官方 `locale-sdk` 的宿主侧实现，因此**完全兼容 Tasker 的扩展协议**。

```
┌─────────────────────────────────────────────────────────────┐
│  配置阶段（用户点击插件时）                                    │
│                                                             │
│  MacroDroid ──ACTION_EDIT_SETTING──> PluginEditActivity      │
│              <──result Intent（含配置 Bundle + 变量清单）──    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  执行阶段（宏运行时）                                          │
│                                                             │
│  MacroDroid ──ACTION_FIRE_SETTING（有序广播）──> PluginFireReceiver
│              （带 COMPLETION_INTENT 回调地址）                 │
│                                                             │
│  ┌── 同步路径（11 秒内） ──────────────────────────────┐       │
│  │  插件 setResultExtras(VARIABLES) → 宿主读取          │       │
│  └──────────────────────────────────────────────────┘       │
│                                                             │
│  ┌── 异步路径（推荐，无超时限制）───────────────────────┐      │
│  │  插件先回 RESULT_CODE_PENDING(3)                     │      │
│  │  → 干完活 → signalFinish() 把结果送到预置的回调地址  │      │
│  │  （宿主指定 Service 就用 Service，否则就发广播）       │      │
│  │  → LocaleTaskerSettingCompleteReceiver 接收           │      │
│  │  → t6g.b() 写入宏变量                                │      │
│  └──────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、最小可用实现（清单文件声明）

插件只需声明**两个组件**，MacroDroid 就能自动发现它。

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- ========== 配置界面 ========== -->
    <activity
        android:name=".PluginEditActivity"
        android:exported="true"
        android:label="你的插件名">      <!-- ← 这个名字显示在 MacroDroid 插件列表里 -->
        <intent-filter>
            <action android:name="com.twofortyfouram.locale.intent.action.EDIT_SETTING" />
        </intent-filter>
    </activity>

    <!-- ========== 执行入口 ========== -->
    <receiver
        android:name=".PluginFireReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="com.twofortyfouram.locale.intent.action.FIRE_SETTING" />
        </intent-filter>
    </receiver>

</manifest>
```

> **注意**：`android:label` 就是插件在 MacroDroid 列表里的显示名。
> 不设的话会显示成类名，很难看。

---

## 三、协议常量速查

### 3.1 基础协议（Locale 规范）

| 常量 | 值 |
|---|---|
| `ACTION_EDIT_SETTING` | `com.twofortyfouram.locale.intent.action.EDIT_SETTING` |
| `ACTION_FIRE_SETTING` | `com.twofortyfouram.locale.intent.action.FIRE_SETTING` |
| `EXTRA_BUNDLE` | `com.twofortyfouram.locale.intent.extra.BUNDLE` |
| `EXTRA_STRING_BLURB` | `com.twofortyfouram.locale.intent.extra.BLURB` |

### 3.2 Tasker 扩展（命名空间 `net.dinglisch.android.tasker`）

| 常量 | 值 |
|---|---|
| `EXTRA_VARIABLES_BUNDLE` | `net.dinglisch.android.tasker.extras.VARIABLES` |
| `EXTRA_HOST_CAPABILITIES` | `net.dinglisch.android.tasker.extras.HOST_CAPABILITIES` |
| `EXTRA_RELEVANT_VARIABLES` | `net.dinglisch.android.tasker.RELEVANT_VARIABLES` |
| `EXTRA_COMPLETION_INTENT` | `net.dinglisch.android.tasker.extras.COMPLETION_INTENT` |
| `EXTRA_RESULT_CODE` | `net.dinglisch.android.tasker.extras.RESULT_CODE` |
| `EXTRA_REQUESTED_TIMEOUT` | `net.dinglisch.android.tasker.extras.REQUESTED_TIMEOUT` |
| `EXTRA_HINTS` | `net.dinglisch.android.tasker.extras.HINTS` |
| `EXTRA_CALL_SERVICE` | `net.dinglisch.android.tasker.EXTRA_CALL_SERVICE` |
| `EXTRA_CALL_SERVICE_PACKAGE` | `net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE` |
| `EXTRA_CALL_SERVICE_FOREGROUND` | `net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_FOREGROUND` |

### 3.3 宿主能力位（`HOST_CAPABILITIES` 的位掩码）

从 `TaskerPlugin` 类提取的**完整定义**：

| 名称 | 值 | 含义 |
|---|---|---|
| `SETTING_RETURN_VARIABLES` | `0x02` | 设置型插件可回传变量 |
| `CONDITION_RETURN_VARIABLES` | `0x04` | 条件型插件可回传变量 |
| `SETTING_FIRE_VARIABLE_REPLACEMENT` | `0x08` | 支持变量替换（`%var` 在触发前被替换） |
| `SETTING_SYNCHRONOUS_EXECUTION` | `0x20` | 支持同步执行 |
| `REQUEST_QUERY_DATA_PASS_THROUGH` | `0x40` | 查询数据透传 |
| **`ALL`** | **`0x7e`** | 上述所有位的并集 |

> **MacroDroid 实际发送的是 `0x7e`**（在 `Setting.fire()` 里硬编码），
> 即它声明支持全部能力。所以判断能力位时不必担心兼容性。

### 3.4 结果码

| 名称 | 值 | 用途 |
|---|---|---|
| `RESULT_CODE_OK` | `-1` | 成功 |
| `RESULT_CODE_OK_MINOR_FAILURES` | `1` | 基本成功（有小问题） |
| `RESULT_CODE_FAILED` | `2` | 失败 |
| **`RESULT_CODE_PENDING`** | **`3`** | **★ 异步处理中（关键）** |
| `RESULT_CODE_UNKNOWN` | `4` | 未知 |
| `RESULT_CODE_FAILED_PLUGIN_FIRST` | `10` | 插件自定义错误起始值 |

### 3.5 超时常量

| 名称 | 值 |
|---|---|
| `REQUESTED_TIMEOUT_MS_NONE` | `0` |
| `REQUESTED_TIMEOUT_MS_MAX` | `3599000`（≈1 小时） |
| `REQUESTED_TIMEOUT_MS_NEVER` | `3600000` |
| **宿主默认等待** | **`11000`（11 秒，硬编码）** |

### 3.6 变量名规范

```
完整表达式：%+[\w&&[^_]][\w0-9]+[\w0-9&&[^_]]
```

要点：
- **必须以 `%` 开头**
- **全小写**（大写会被识别为全局变量）
- 只含字母、数字、下划线
- **首字符不能是下划线或数字**
- **末字符不能是下划线**

---

## 四、配置界面（PluginEditActivity）

### 4.1 必须做的事

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // ① 读旧配置（用户之前保存过的）
    Bundle old = getIntent().getBundleExtra(
            "com.twofortyfouram.locale.intent.extra.BUNDLE");

    // ... 构建 UI ...

    if (old != null) applyOldConfig(old);
}
```

### 4.2 保存时必须做三件事

```java
private void saveConfig() {
    // ① 打包配置
    Bundle cfg = new Bundle();
    cfg.putString("myOption", "值");
    // ...

    Intent result = new Intent();
    result.putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", cfg);

    // ② 设置摘要（显示在宏的动作列表里，帮助用户辨认）
    result.putExtra("com.twofortyfouram.locale.intent.extra.BLURB",
            "截屏 · tiny · 纯文本");

    // ③ ★ 声明会产出哪些变量（这一步漏了，变量永远传不回来！）
    result.putExtra("net.dinglisch.android.tasker.RELEVANT_VARIABLES",
            new String[]{
                "%xsocr_text\n识别文本\n识别出的全部文字",
                "%xsocr_match\n是否找到\ntrue / false",
                // 格式：变量名\n标签\n描述
            });

    setResult(RESULT_OK, result);
    finish();
}
```

### 4.3 ⚠️ 变量声明格式（三段式）

**这是最容易踩的坑。** 声明必须是**三段式**，用 `\n` 分隔：

```
变量名\n标签\n描述
```

参考 PP-OCRv6 的实际写法：

```java
StringBuilder sb = new StringBuilder();
sb.append("%ocr_full_text\n")     // ① 变量名
  .append("完整文本")               // ② 标签（显示给用户看）
  .append("\n")
  .append("识别出的全部文字");       // ③ 描述
```

**只传裸变量名**（如 `"%ocr_full_text"`）虽然部分宿主也能列出，
但界面可读性差、解析也不稳定。**建议始终用三段式。**

### 4.4 配置界面的 UI 陷阱

**问题**：用 `Theme.Material3.Light.Dialog` 时，窗口会窄成一条竖线。

**原因**：该主题是给 `AlertDialog` 用的，`windowIsFloating=true` 且宽度 `wrap_content`。

**解法（三道保险）**：

```xml
<style name="PluginDialogTheme" parent="Theme.Material3.Light.Dialog">
    <item name="android:windowMinWidthMajor">92%</item>
    <item name="android:windowMinWidthMinor">92%</item>
    <item name="android:windowNoTitle">true</item>
</style>
```

```java
// 代码里再兜一层，防 ROM 差异
Window w = getWindow();
DisplayMetrics dm = getResources().getDisplayMetrics();
w.setLayout((int) (dm.widthPixels * 0.92f), WRAP_CONTENT);
```

```java
// 所有 addView 都要显式传 LayoutParams（MATCH_PARENT + WRAP_CONTENT）
root.addView(spinner, wide());
```

> 还有个坑：`Theme.Material3.Light.Dialog.NoActionBar` **不存在**，
> 写了会 AAPT 报错。Dialog 主题本身就不带 ActionBar。

---

## 五、执行入口（PluginFireReceiver）

### 5.1 关键认知：宿主只等 11 秒

从 `com/twofortyfouram/locale/sdk/host/api/Setting.smali` 的 `fire()` 方法：

```java
// ① 发有序广播（注意：不是普通广播！）
sendOrderedBroadcast(intent, null, fireResultReceiver, handler, ...);

// ② 等结果，但只等 11 秒
const-wide/16 v6, 0x2af8        // = 11000
invoke-virtual {latch, v6, MILLISECONDS}, CountDownLatch;->await(...)

// ③ 超时后降级重发「普通广播」，并直接返回（丢弃结果）
if (!countedDown) {
    context.sendBroadcast(intent);
    return;
}
```

**推论：**
- 宿主**确实发有序广播**，变量回传机制是通的
- 但**只有 11 秒窗口**；超时后会降级重发普通广播
- 你会看到 `onReceive` 被调用**两次**，第二次 `isOrderedBroadcast()` 为 `false`

### 5.2 ⚠️ 11 秒不可协商

我验证过：MacroDroid 的 `LocalePluginAction` 虽然会调
`Setting.setTimeoutHintMs()`，但那个值**只是被塞进 intent 当提示**
（`addHintTimeoutMS`），`latch.await` 用的仍是**硬编码的 11000ms**。

在 MacroDroid 的 smali 里搜 `getRequestedTimeoutMS`、`REQUESTED_TIMEOUT`
的**业务调用方**，结果为空。

**结论：想延长等待时间？不可能。必须用异步方案。**

### 5.3 ★ 正解：异步补结果（`RESULT_CODE_PENDING` + `signalFinish`）

这是 PP-OCRv6 插件（实测可用）采用的方式，**完全绕开 11 秒限制**。

```java
@Override
public void onReceive(Context context, Intent intent) {
    if (!"com.twofortyfouram.locale.intent.action.FIRE_SETTING"
            .equals(intent.getAction())) {
        return;
    }

    // ① 判断宿主是否给了「回调地址」
    String completionUri = intent.getStringExtra(
            "net.dinglisch.android.tasker.extras.COMPLETION_INTENT");
    boolean hasCompletion = completionUri != null && completionUri.length() > 0;

    // ② ★ 先回 PENDING，告诉宿主"我在处理，别按超时处理"
    //    必须在 goAsync() 之前调用，且用 BroadcastReceiver 自己的方法
    if (isOrderedBroadcast()) {
        setResultCode(3);   // RESULT_CODE_PENDING
    }

    if (hasCompletion) {
        // ③ 走异步路线：onReceive 立即返回，不挂住广播
        //    （不用 goAsync，因为 PendingResult 有 ~10 秒限制）
        final Intent original = intent;
        new Thread(() -> {
            Bundle vars = doWork();                 // 慢慢跑，20 秒也行
            signalFinish(context, original, -1 /* OK */, vars);
        }, "plugin-worker").start();
        return;   // ← 立即返回
    }

    // ④ 兜底：没有回调地址时只能同步（必须在 11 秒内）
    PendingResult pending = goAsync();
    new Thread(() -> {
        Bundle vars = doWork();
        Bundle extras = pending.getResultExtras(true);
        extras.putBundle("net.dinglisch.android.tasker.extras.VARIABLES", vars);
        pending.setResultExtras(extras);
        pending.setResultCode(Activity.RESULT_OK);
        pending.finish();
    }).start();
}
```

### 5.4 `signalFinish()` 实现

复刻 `TaskerPlugin.Setting.signalFinish()`：

```java
public static boolean signalFinish(Context ctx, Intent fromHost,
                                   int resultCode, Bundle variables) {
    try {
        // ① 取出宿主预置的回调地址（是个序列化成 URI 的 Intent）
        String uri = fromHost.getStringExtra(
                "net.dinglisch.android.tasker.extras.COMPLETION_INTENT");
        if (uri == null || uri.isEmpty()) return false;

        // ② 还原成 Intent（flags=1 允许从 URI 还原）
        Intent completion = Intent.parseUri(uri, 1);

        // ③ 塞入结果码和变量
        completion.putExtra("net.dinglisch.android.tasker.extras.RESULT_CODE",
                resultCode);
        if (variables != null) {
            completion.putExtra("net.dinglisch.android.tasker.extras.VARIABLES",
                    variables);
        }

        // ④ 宿主可能要求用 Service 接收（而不是广播）
        //    ★ 这三个值必须从 **completion** 上读，不能从 fromHost 读！
        //    它们是宿主在把回调地址序列化成 URI 之前，放进 completion intent 里的；
        //    原始 fromHost intent 上没有这三项，从那儿读恒为 null，
        //    于是一律走广播 —— MacroDroid 收广播没问题，
        //    但 **Tasker 是按 Service 收结果的，广播到不了它手里，变量全丢**。
        //    （这是 2026-10 真实踩过的坑，对照 Tasker 官方 TaskerPlugin.java 才定位到。）
        String pkg = completion.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE");
        String cls = completion.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE");
        Boolean fg = (Boolean) completion.getExtras()
                .get("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_FOREGROUND");
        // 兜底：万一某些宿主把它放在原始 intent 上（只加不减，不影响已有行为）
        if (pkg == null) pkg = fromHost.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE");
        if (cls == null) cls = fromHost.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE");

        if (pkg != null && cls != null) {
            completion.setComponent(new ComponentName(pkg, cls));
            if (Boolean.TRUE.equals(fg) && Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(completion);
            } else {
                ctx.startService(completion);
            }
            return true;
        }

        // ⑤ 默认：发广播（MacroDroid 走的就是这条）
        ctx.sendBroadcast(completion);
        return true;
    } catch (Throwable t) {
        return false;
    }
}
```

### 5.5 MacroDroid 侧如何接住结果

```java
// com.arlosoft.macrodroid.action.receivers.LocaleTaskerSettingCompleteReceiver
public void onReceive(Context ctx, Intent intent) {
    Bundle extras = intent.getExtras();

    // ① 读变量
    Bundle vars = extras.getBundle(
            "net.dinglisch.android.tasker.extras.VARIABLES");

    // ② 读动作 ID（用于定位是哪个动作触发的）
    long actionId = intent.getLongExtra("action_id", 0);

    // ③ 遍历所有宏，找到 SIGUID 匹配的那个动作
    for (Macro macro : allMacros) {
        for (Action action : macro.getActions()) {
            if (action instanceof LocalePluginAction
                    && ((LocalePluginAction) action).getSIGUID() == actionId) {
                // ④ 写入变量
                t6g.b(vars, pluginAction.p(), action, ...);
            }
        }
    }
}
```

而 `t6g.b()` 的核心逻辑：

```java
for (String key : vars.keySet()) {
    Object value = vars.get(key);          // 拿到值（可能是 String/Integer）
    String mapped = mapping.get(key);      // 查「Output variables」映射表
    if (mapped == null) continue;          // ← 没做映射就跳过！
    String strValue = String.valueOf(value);   // ★ 统一转成字符串
    new VariableValue$StringValue(strValue, ...);
    action.variableUpdate(macroDroidVariable, stringValue, ...);
}
```

**关键结论：**

| 结论 | 说明 |
|---|---|
| 变量值会被 `String.valueOf()` | 传 `Integer` 或 `String` 最终都存成文本 |
| **必须做变量映射** | 用户要在「Output variables」里把插件变量映射到宏变量，否则 `mapping.get(key)` 返回 null，**直接跳过** |
| Bundle 的 key 就是变量名 | 如 `"%xsocr_text"`（带 `%`） |

### 5.6 ⚠️ 配置必须重新保存

**因为变量声明是在保存配置时发给宿主的。**

如果你更新了插件（改了变量清单），用户**必须重新走一遍配置**：

1. 删掉现有插件动作
2. 重新添加 → 选插件 → 配置 → **保存**
3. 在 **Output variables** 里重新映射变量

否则宿主不知道有新变量，`mapping` 表里没有，值会被丢弃。

---

## 六、常见问题排查

### 6.1 变量传不回来

**先分清两条完全独立的链路**，它们的变量回传方式不一样：

| | 触发方式 | 变量从哪回传 |
|---|---|---|
| **动作**（Setting） | 宿主发 `FIRE_SETTING` 有序广播 | `PluginFireReceiver` 里 `getResultExtras(true).putBundle(VARIABLES, vars)` |
| **事件触发器**（Event） | 插件主动广播 `REQUEST_QUERY` → 宿主回问 `QUERY_CONDITION` | **`PluginConditionReceiver` 自己回**，宿主不会经过 `FIRE_SETTING` |

⚠️ **本项目踩过的坑**：`PluginConditionReceiver` 原先只调 `setResultCode(16/17/18)`，
一个变量都没回。结果就是「动作能拿到变量，事件触发器里变量全是空的」——
宏确实被触发了，但 `%xsocr_menu_index` 之类全是空值。

> 💡 注意区分两种「空」：
> - **没选中菜单**（滑到空白处松手 / 触摸被系统抢走）→ 我们**故意不回传**
>   菜单变量，宏侧判「有没有收到 `%xsocr_menu_index`」。
> - **该回却没回**（这条链路的 bug）→ 选中了也拿不到，才是问题。
>   两者在宏里都表现为「变量空」，排查时先看日志里
>   `条件应答: 结果码=16 变量=3` 这一行 —— 变量=3 说明我们送出去了。

**事件链路的正确写法**（参照 AutoInput，`com.joaomgcd.autoinput`）：

```java
// 在 QUERY_CONDITION 的应答里：
Bundle out = getResultExtras(true);            // ★ 必须 true，拿的是宿主那份实例
if (out != null) {
    out.putBundle("net.dinglisch.android.tasker.extras.VARIABLES", vars);
}
setResultCode(code);
```

`getResultExtras(true)` 的 `true` 不能省：它返回宿主那一侧的**同一个 Bundle 实例**，
往里 put 才能被宿主读到；自己 `new Bundle()` 写了也传不回去。

**变量键只有一个**：`net.dinglisch.android.tasker.extras.VARIABLES`。
别想着「多写几个候选键保险」——同名多写只是覆盖，异名宿主不认，纯属自欺。

**要不要条件成立才回变量？** AutoInput 的做法是：
只有条件成立（返回 16）时才 `setVars()`。理由很实在——
宏只在条件成立时才真正往下跑，不成立时它本来就不会去读变量。
但本项目实现为**消息真实就回**（含 17），多给一点无害；
唯独**防伪校验失败（18）时坚决不回**，因为那不是一次真实手势，
回变量会让宏拿到上一次的陈旧值。

按顺序检查：

| 检查项 | 方法 |
|---|---|
| ① 是否声明了 `RELEVANT_VARIABLES` | 配置界面保存时有没有 `putExtra` |
| ② 是否重新保存了配置 | 改代码后必须重配 |
| ③ 是否做了变量映射 | MacroDroid 动作里「Output variables」 |
| ④ 是否超时 | 日志看耗时是否 > 11000ms |
| ⑤ 是否收到 `PENDING` | 宿主看到 PENDING 才不按超时处理 |
| ⑥ **事件触发器**：应答里有没有回变量 | 见上面的坑，条件接收器极易漏写 |

### 6.2 日志里出现「非有序广播」

```
非有序广播，无法回传变量（宿主需用 sendOrderedBroadcast）
```

**这不是宿主的错**——说明已经超时了，宿主降级重发了普通广播。
真正要解决的是**让流程快于 11 秒**，或改用**异步补结果**。

### 6.3 `IllegalStateException: Call while result is not pending`

**原因**：调了 `goAsync()` 之后，又用了 `BroadcastReceiver` 自己的
`setResultCode()` / `getResultExtras()`。

**解法**：`goAsync()` 后必须改用 `PendingResult` 对象：

```java
PendingResult pending = goAsync();
// ❌ setResultCode(...)          —— 会抛异常
// ✅ pending.setResultCode(...)
// ✅ pending.getResultExtras(true)
// ✅ pending.setResultExtras(...)
// ✅ pending.finish()
```

### 6.4 `ForegroundServiceStartNotAllowedException`

**原因**：Android 12+ 禁止后台应用启动前台服务。

**解法**：
- 收到广播后有约 **10 秒豁免窗口**，在这期间调用是允许的
- 或申请**悬浮窗权限**（`SYSTEM_ALERT_WINDOW`），拥有它可不受限制

---

## 七、完整示例：一个 OCR 插件

以下是本项目（小孙OCR）的实际实现，可直接参考。

### 7.1 变量定义
```java
public static final String VAR_TEXT  = "%xsocr_text";
public static final String VAR_MATCH = "%xsocr_match";
public static final String VAR_X     = "%xsocr_x";   // 数字，但回传必须是字符串
public static final String VAR_Y     = "%xsocr_y";   // 数字，但回传必须是字符串
public static final String VAR_ERROR = "%xsocr_error";
public static final String VAR_TIME  = "%xsocr_time";
public static final String VAR_TIER  = "%xsocr_tier";
// 滑动菜单（触发器路径，见 7.4 / 7.6）
public static final String VAR_MENU_INDEX  = "%xsocr_menu_index";   // 数字，但回传必须是字符串
public static final String VAR_MENU_TEXT   = "%xsocr_menu_text";
public static final String VAR_MENU_ACTION = "%xsocr_menu_action";
```

> **曾经有 6 个手势变量，现已全部撤除**：
> `%xsocr_gs_direction` / `_direction_cn` / `_handle` / `_event` / `_seq` / `_time`。
>
> 撤除理由：**方向匹配已经由插件在内部完成**。宏能跑起来，就说明这次手势
> 的方向正是宏里配的那个 —— 再回传一遍方向纯属冗余。其余四项（手柄名、
> 事件类型、序号、时间戳）都是排查用的调试信息，用户早在 MacroDroid 里
> 自己建变量接值、名字由自己定，插件不该替他决定。
>
> 只留菜单三项，因为「用户点了哪一项」**只有插件知道**。
> 判定逻辑（`EdgeGesturePrefs.gsLastDirection` 等字段）仍在内部保留，
> 但不再对外暴露。
### 7.2 声明（三段式）
```java
public static final String[] ALL_VARIABLES = {
    VAR_TEXT  + "\n识别文本\n识别出的全部文字（多行用换行连接）",
    VAR_MATCH + "\n是否找到\n指定的文本是否存在（true / false）",
    VAR_X     + "\n坐标X\n指定文本命中处的 x 坐标（数字文本）",
    VAR_Y     + "\n坐标Y\n指定文本命中处的 y 坐标（数字文本）",
    VAR_ERROR + "\n错误信息\n失败时才有内容",
    VAR_TIME  + "\n耗时(ms)\n本次识别耗时",
    VAR_TIER  + "\n模型档位\ntiny / small / rapid",
    VAR_MENU_INDEX  + "\n菜单索引\n滑动菜单选中的项序号（从 0 开始；-1 = 取消或未启用菜单）",
    VAR_MENU_TEXT   + "\n菜单文字\n选中项的显示文字（取消时为空）",
    VAR_MENU_ACTION + "\n菜单动作\n选中项的动作串（取消时为空）",
};
```

> ⚠️ **动作路径与触发器路径的变量清单是两份**，不是一个数组：
> `ALL_VARIABLES`（识别 7 项）给「动作」身份用，
> `MENU_VARIABLES`（菜单 3 项）给「事件」身份用。
> 触发器场景里根本没有 OCR 识别，把识别文本 / 坐标列出来
> 只会让人以为配个手势还要填图片路径。

### 7.3 回传（**值一律是字符串**）

```java
Bundle vars = new Bundle();
vars.putString(VAR_TEXT, sb.toString());
vars.putString(VAR_MATCH, matched ? "true" : "false");
// ★ 坐标、索引这类「数字」也必须写成字符串，不能 putInt：
//   Tasker 官方的变量协议只接受 String / String[] / ArrayList<String>，
//   回 Integer 会被静默丢弃 —— 表现为「文字变量能到、数字变量到不了」。
vars.putString(VAR_X, String.valueOf(matchX));
vars.putString(VAR_Y, String.valueOf(matchY));
vars.putString(VAR_ERROR, "");
vars.putString(VAR_TIME, String.valueOf(cost));
vars.putString(VAR_TIER, tier);
```

> ⚠️ **这是一个真实踩过的坑（2026-10）**：原先坐标与菜单索引用 `putInt()` 回传，
> 理由是「语意上是数字、更规范」。结果变量在 MacroDroid 里一切正常，
> 在 **Tasker 里却收不到** —— 因为 Tasker 只认字符串值，Integer 会被丢掉；
> MacroDroid 内部对每个值统一 `toString()`，所以不挑类型。
>
> 官方 `TaskerPlugin.addVariableBundle` 的 javadoc 原文：
>
> ```
> * Names must be valid Tasker local variable names.
> * Values must be String, String [] or ArrayList<String>
> ```
>
> 结论：**回传宿主的值，一律用字符串**。内部自己看的快照可以另存数字，
> 但发给宿主的那份必须是字符串。遍历保存时统一用
> `String.valueOf(bundle.get(key))`，对任何类型都安全。

### 7.4 把「边缘滑动手势」接进插件

手势由 `EdgeGestureService`（悬浮窗）产生。它**不对外发独立广播**，只做两件事：

1. 按事件插件协议向宿主发一条 `REQUEST_QUERY`（见 7.5），**只发这一条**；
2. 在进程内写下一组 volatile 静态字段，供应答路径零 IPC 取用：

```java
// EdgeGesturePrefs —— 供 PluginConditionReceiver 读取的历史快照
public static volatile String gsLastDirection = "";      // left / right / ...
public static volatile String gsLastDirectionLabel = ""; // 向左 / 向右 / ...
public static volatile String gsLastHandle = "";         // 手柄名
public static volatile String gsLastEvent = "";          // 恒为 "trigger"（另两种时机已下线）
public static volatile int    gsSeq = 0;                 // 每触发一次 +1
public static volatile long   gsLastAt = 0L;             // 毫秒时间戳
// 菜单结果
public static volatile int    menuIndex = -1;            // < 0 = 没选中（不回传变量）
public static volatile String menuText = "";
public static volatile String menuAction = "";
```

> ⚠️ 早期版本曾在 `PluginFireReceiver.stashGestureVars()` 里把这些字段
> 打包回传（`%xsocr_gs_*` 六个变量）。**那套已整体撤除**，
> 原因见 7.1 的说明。现在这些字段**仅供内部方向匹配使用**，不再对外暴露。

**方向匹配在哪里做**：不在宏里，也不在本应用里 —— 在**插件的应答路径**上。
宿主发 `QUERY_CONDITION` 问「手势成立吗」，`PluginConditionReceiver` 拿配置里
定的期望方向与 `EdgeGesturePrefs.gsLastDirection` 比对：

| 比对结果 | 回应 | 宏的行为 |
|---|---|---|
| 匹配 | `setResultCode(16)` + 回传菜单变量 | **跑** |
| 不匹配 | `setResultCode(17)` | **不跑**（宿主视为条件不成立） |
| 防伪校验失败 | `setResultCode(18)` | 安静跳过 |

于是宏只有一条可用路径：

| 路径 | 配置方式 | 拿得到什么 |
|---|---|---|
| 插件（唯一） | 触发器 → 「小孙OCR · 边缘滑动事件」 | **菜单三项变量** |

因为「不匹配就不回应」，宏里**不需要再判断方向** —— 能被触发，
就说明方向已经对上了。

> 曾经的「广播」旁路（`com.xiaosun.ocr.action.EDGE_GESTURE`）已撤除：
> 它每次滑动都往系统里扔一条人人可收的隐式广播，既多一次跨进程唤醒，
> 又不带任何防伪信息 —— 谁都能照着抄一条来骗过监听方的宏。
> 插件通道能直接回传变量，已覆盖全部用法。

**方向选了会排他地同步到手柄**：手势能不能触发，服务里判的是 `handle.enabled[dir]`。
配置界面里选定方向后，`EdgeGesturePrefs.setDirectionsForAllHandles()` 会以触发器为准改写
**所有手柄**的方向开关 —— 选中的打开、**其余七个一律关掉**
（有变动才 `EdgeGestureService.reload()` 让服务立刻重建，即改即生效）。
不这么做的话，用户在触发器里选了「向上」，手柄的「向上」其实没开，滑了不触发 ——
还得回手柄编辑页再勾一次，两处配置割裂、极易漏。

> 为什么是「排他」而不是「追加」：触发器里选了哪个方向，意图就是**只监听这一个**。
> 若只做追加，用户把「左下」改成「左上」之后左下仍然开着 ——
> 左下滑一下照样触发，宏会跑在意料之外的方向上。
> 想同时监听多个方向，到手柄编辑页手动开（那里能看到每个方向的状态）。

### 7.5 ★ 让插件出现在「触发器」列表里（事件协议）

**这是最容易踩的坑：宿主的「动作」和「触发器」是两份列表、两套协议。**

| 列表 | 发现插件靠的 action | 谁响应 |
|---|---|---|
| 动作（Action） | `com.twofortyfouram.locale.intent.action.EDIT_SETTING` | Activity |
| 触发器 → 事件 | `net.dinglisch.android.tasker.ACTION_EDIT_EVENT` | Activity（或 alias） |

只声明 `EDIT_SETTING` 时，插件在动作列表里正常，**在触发器列表里彻底看不到** ——
不是配置错了，是根本没注册那条 intent-filter。

> ★ 早期还实现过**条件身份**（`EDIT_CONDITION`），现已撤除。
> 条件与事件的配置界面、功能完全一样，唯一差别是事件携带防伪数据
> （随机 message id + 60 秒有效期），条件只能读进程内的
> 「最近一次手势」快照、理论上可被伪造广播骗过。
> 两个入口并列只会让人在触发器列表里挑错，还可能两个都配
> 导致同一次滑动触发两遍，所以只留事件。
> （`PluginContract.MODE_STATE` 与 `PluginConditionReceiver.answerState()`
>  字段保留了，仅为不打断升级前配好的老宏。）

#### ① 清单声明（两种身份，一份界面）

```xml
<!-- 配置界面本体：不挂任何 intent-filter，只作为别名目标 -->
<activity android:name=".PluginEditActivity" android:exported="true"
          android:theme="@style/PluginDialogTheme" />

<!-- 动作身份：识别屏幕文字 -->
<activity-alias android:name=".OcrActionConfigActivity" android:exported="true"
                android:label="@string/app_name"
                android:targetActivity=".PluginEditActivity">
    <intent-filter>
        <action android:name="com.twofortyfouram.locale.intent.action.EDIT_SETTING" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity-alias>

<!-- 事件身份：手势触发器，必须用 alias（原版遵此） -->
<activity-alias android:name=".TaskerEventConfigActivity" android:exported="true"
                android:label="@string/tasker_event_name"
                android:targetActivity=".PluginEditActivity">
    <intent-filter>
        <action android:name="net.dinglisch.android.tasker.ACTION_EDIT_EVENT" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity-alias>

<!-- 查询应答者。★ 名字有误导性：它不只是给"条件"用的 -->
<receiver android:name=".PluginConditionReceiver" android:exported="true">
    <intent-filter>
        <action android:name="com.twofortyfouram.locale.intent.action.QUERY_CONDITION" />
    </intent-filter>
</receiver>
```

用 `activity-alias` 的价值只在**名字**：alias 的 `android:label` 决定它在
对应列表里显示什么。两个身份共用同一个 Activity 类，代码零重复，
界面靠 `getIntent().getAction()` 区分该显示哪套控件（见 `PluginEditActivity` 的 `applyMode()`）。

#### ② 事件是「插件主动喊」，不是宿主来问

```java
// 手势发生的那一刻（EdgeGestureService.fire()）
Intent i = new Intent("com.twofortyfouram.locale.intent.action.REQUEST_QUERY");
i.putExtra("com.twofortyfouram.locale.intent.extra.ACTIVITY",
        getPackageName() + ".TaskerEventConfigActivity");
TaskerEventProtocol.addEventData(i, dirKey);   // 附防伪数据
sendBroadcast(i);                              // ★ 不能 setPackage，宿主是第三方
```

`EXTRA_ACTIVITY` 指明「是哪个组件的实例出事了」，宿主据此把查询路由到对应宏。
已经撤掉条件身份之后，插件只剩事件这一种触发器身份，所以每次手势
**只发这一条 `REQUEST_QUERY`**，不存在「两条」的说法。

宿主收到后会回头给 `PluginConditionReceiver` 发 `QUERY_CONDITION`，
插件用 `setResultCode()` 回答：

| 结果码 | 常量 | 含义 |
|---|---|---|
| 16 | `RESULT_CONDITION_SATISFIED` | 成立 → 宏跑 |
| 17 | `RESULT_CONDITION_UNSATISFIED` | 不成立 → 宏不跑 |
| 18 | `RESULT_CONDITION_UNKNOWN` | 说不清 → 宿主安静跳过 |

**★ 别只回结果码，变量要一起回**（本项目踩过的大坑）：

事件触发器**不经过** `FIRE_SETTING`，所以 `PluginFireReceiver` 里那套
变量回传**在这条链路上根本不会执行**。宏确实会被触发，但里面
`%xsocr_menu_index` / `%xsocr_menu_text` / `%xsocr_menu_action` 全是空的。

必须在应答时自己把变量送回宿主（详见 6.1 与 `PluginConditionReceiver.answer()`）：

```java
Bundle out = getResultExtras(true);        // ★ true：拿宿主那份实例，不是新 Bundle
out.putBundle("net.dinglisch.android.tasker.extras.VARIABLES", vars);
setResultCode(16);
```

补充说明宿主塞进来的 `ResultReceiver`
（键 `net.dinglisch.android.tasker.EXTRA_RESULT_RECEIVER`，AutoInput 用的就是它）：
若存在，`receiver.send(结果码, 变量Bundle)` 是另一条独立通道。
本实现两条都写，哪条宿主没在用它会自己忽略。

⚠️ **只写一处必然漏**：判定逻辑散在各分支里、每处都手写一遍 `setResultCode` +
`putBundle`，早晚有分支忘了回变量。本项目改成「分支只返回结果码 + 填变量 Bundle，
发送统一走一个出口方法」——这次的 bug 就是这么防住的。

#### ③ 防伪：`REQUEST_QUERY` 谁都能发

隐式广播没有防护，任何 App 抄一条就能凭空触发用户的宏（而宏里往往是
「点击屏幕」「发短信」）。解法是给每次上报签一个随机号：

```java
// 上报方
int id = RANDOM.nextInt(Integer.MAX_VALUE);
RECENT_MESSAGES.put(id, SystemClock.elapsedRealtime());
data.putInt("net.dinglisch.android.tasker.MESSAGE_ID", id);
intent.putExtra("net.dinglisch.android.tasker.extras.PASS_THROUGH_DATA", data);

// 应答方：宿主把 Bundle 原样递回来，核对
//   ① id 在表里（是我们签的）  ② 距签发 ≤ 60 秒（不是重放）
// 任一不满足 → 回 18（不是 17，18 才会被安静忽略）
```

要点：
- 时间基准用 `SystemClock.elapsedRealtime()`，**不要用 `currentTimeMillis()`**——
  系统时间被 NTP 校正或用户手改时，60 秒有效期会算错
- 表用 `LinkedHashMap<Integer,Long>`，超 100 条时从迭代器首个（最旧）开始删
- 校验失败回 **18** 而非 17：17 会被宿主当成「条件确实不成立」可能引发副作用，
  18 才是「无可奉告」

配置里留了「校验收到的消息」复选框，给那些不把 `PASS_THROUGH_DATA`
递回来的宿主留退路（各版本行为不一），关掉则退化为「最近一次手势」判断。

#### ④ 排查：触发器列表里看不到插件

| 现象 | 原因 |
|---|---|
| 动作列表有、触发器列表没有 | 缺 `ACTION_EDIT_EVENT` 的 intent-filter（挂在 alias 上） |
| 列表里名字很怪或没名字 | Activity（或 alias）没设 `android:label` |
| 能配好但手势时宏不跑 | 没发 `REQUEST_QUERY`；或发了但应答器（`PluginConditionReceiver`）没注册 |
| 宏偶尔莫名触发 | 没做防伪校验，别的 App 伪造了 `REQUEST_QUERY` |
| 配完改了代码不生效 | 宿主缓存了配置，**必须重新保存一次插件动作** |

### 7.6 ★ 跟手滑出菜单（不抬手选完）

#### 想要的效果

手指按住边缘手柄、朝有效方向滑，滑过触发距离后**不抬手**，
直接弹出一排按钮；继续滑到某个按钮上（跟手高亮），抬手就选中它。

#### 为什么宏弹不了这块菜单

普通做法走不通，卡在 Android 输入分发的一条硬规则上：

> `InputDispatcher` 在手指按下（`ACTION_DOWN`）那一刻做一次命中判定，
> 把**整条触摸序列**绑定给当时那个窗口。此后 MOVE / UP 只投递给它。
> 中途新建的窗口收不到 MOVE（无法跟手高亮），也收不到 UP（无法判定选中）。

官方**没有任何 API** 能把进行中的触摸序列转交给别的窗口或进程。

更致命的是：宏的 Java 动作是**一次性脚本**，跑完即退，
根本没法常驻持有窗口。所以「宏自己弹菜单」这条路彻底走不通。

#### 分工

于是菜单由**已经拿住那次触摸的那个悬浮窗**自己弹，也就是本应用的
`EdgeGestureService`：

| | 负责什么 |
|---|---|
| 本应用 | 配菜单（设置页）、弹菜单、跟手高亮、抬手判定，把选中项写成变量 |
| 宏 | 被触发后读变量，分支执行动作 |

关键在于**宏被唤起的时刻，正是手指抬起的时刻**。
所以宏侧既不需要常驻，也不需要处理「手指还按着」的中间态 ——
它只是在「发生了」之后按结果做事。

#### 配置（全在本应用里，宏不用管）

**滑动触发** 页 → **滑动菜单**：

- **启用开关**：关 = 滑过距离立即触发宏（老行为）；开 = 弹出菜单。
- **外观**：图标大小、显示文字、文字大小、文字间距、浅色圆底。
- **布局**：每排数量、逐排数量、图标间距、每排间距。
- **配色**：圆底 / 文字 / 选中色，支持 `#RGB`、`#ARGB`、`#RRGGBB`、`#AARRGGBB`。
- **热区大小**：命中半径 = 图标半径 × 倍率（100%~300%），下方带感应范围预览。
- **菜单项列表**：双列网格；点按编辑，长按出「上移 / 下移 / 编辑内容 / 删除」。

每一项有三个字段：

| 字段 | 说明 |
|---|---|
| 名字 | 显示在菜单按钮上的文字，必填 |
| 动作值 | 回传给宏的 `%xsocr_menu_action`；留空就和名字一样 |
| 图标 | `app:包名` / `file:路径` / 资源名，留空则显示名字前两个字 |

**动作值为什么要单独一栏**：名字是给人看的、动作值是给宏比的。
两者分开之后，按钮可以叫「微信」而动作值叫 `wechat`，
宏里的判断条件比按序号好读得多。

`app:包名` 是最省事的写法，比如：

```
名字=截图   动作值=shot    图标=app:com.miui.screenshot
名字=OCR    动作值=ocr     图标=app:com.xiaosun.ocr
名字=翻译   动作值=trans   图标=（留空，显示「翻译」）
```

> 注：本应用 `targetSdk 34`，受包可见性（package visibility）限制，
> `getApplicationIcon(pkg)` 对**未声明过**的包会抛 `NameNotFoundException`，
> 表现为「填了 app:包名 但图标是空的」。
> 本应用已在 `AndroidManifest.xml` 里同时声明了 `QUERY_ALL_PACKAGES`
> 与 `<queries>`（MAIN/LAUNCHER + 常见目标应用直接点名），两条路任一生效即可。
> 界面里仍**没有**「从已装应用里挑一个」的列表 —— 直接填包名即可，
> 编辑框旁边有实时预览，打错一个字母立刻能看出来。

#### 回传的变量

| 变量 | 含义 |
|---|---|
| `%xsocr_menu_index` | 选中项序号（从 0 开始）；**没选中时本变量不出现** |
| `%xsocr_menu_text` | 选中项的名字；没选中时不出现 |
| `%xsocr_menu_action` | 选中项的动作值；没选中时不出现 |

> ★ **没选中就一个变量都不传**（不是传 `-1` / 空串）。
> 菜单弹出来了、但滑到空白处松手，或者这批触摸被系统抢走 ——
> 这几种情况都算「没选中」，宏收到的菜单变量是**空的**。
> 宏里判「有没有收到 `%xsocr_menu_index`」即可，不必记 `-1` 这种魔法值。

> ★★ 更准确地说：**没选中时连通知都不发**。
> 插件不会广播 `REQUEST_QUERY`，宿主压根不知道发生了这次手势，
> 于是宏**根本不会被触发** —— 不是「触发了但变量空」。
> 所以宏里不需要为「空变量」写兜底分支：能跑起来，就说明一定选中了某一项。

宏里这样分支：

```
（宏能跑起来 = 一定选中了某一项，不需要判空）
如果 %xsocr_menu_action = shot → 截图
如果 %xsocr_menu_action = ocr  → OCR 识别
```

⚠ 一个细节：`index` 是用 `putIntAsString()` 放进变量 Bundle 的 ——
Tasker 的变量协议只认 String / String[] / ArrayList&lt;String&gt;，
用 `putInt` 会被它静默丢弃（详见 6.1）。宿主最终把它当文本，
所以宏里按字符串比较即可。

#### 实现要点（改插件时别踩）

- **窗口只能「原地长大」**：`wm.updateViewLayout()` 只改 `width/height/gravity`，
  窗口 token 与 input channel 不变，触摸不中断。
  ❌ **绝不能用 `removeView()` + `addView()`** —— 当前手势会立刻收到 `ACTION_CANCEL`。
- **多指要过滤**：菜单展开后窗口是全屏的，第二根手指按下来也落进本窗口。
  必须只认 `ACTION_DOWN` 那一刻握住的那根手指（`activePointerId`），
  否则高亮会跟着别的手指乱跳，甚至被别的手指的 `UP` 提前结束菜单。
- **抬手后必须缩回**：还原 `width/height/gravity/x/y`，否则手柄再也不出现。
- **`ACTION_CANCEL` 不能当成选中**：那是系统中断（来电、窗口被顶掉）。
  收到它只记一次「取消」并收回窗口，**不触发宏**。
- **没选中就不通知宿主**：抬手时不在任何图标上（滑到空白处松手）
  同样**不 `fire()`** —— 不广播 `REQUEST_QUERY`，宿主不知道有这次手势，
  宏不会跑。只有真压在图标上抬手才通知。这样宏侧不需要为
  「触发了但没选中」写兜底分支，也杜绝了「跑成上一次的旧结果」。
- **展开期间不再申请手势排除区**：整屏已经归本窗口，此时正是最不该
  干扰系统手势判定的时刻（用户可能只是想点按钮）。收起后重新报。
- **自绘而不是 `addView()`**：`GestureView` 是 `View` 不是 `ViewGroup`；
  而且自绘没有布局延迟 —— 跟手判定必须零延迟。
- **命中框比圆大**：命中框要抱住图标下方的文字（落在文字上也算选中），
  但要按图标直径画**真正的圆**，别拿命中框画圆角矩形，
  否则会变成胶囊状，看着像进度条。
- **命中判定用极坐标，不要用矩形相交**：早期用 `Rect.contains()` 判定，
  相邻图标（cell 216px、命中框 246px）的框会互相重叠，
  导致「点在 B 上却选中 A」。改成「角向半槽角 + 径向双重限制」后才准确。
- **绘制圆心 = 判定圆心**：让 `menuRects` 的矩形严格等于图标外接正方形
  （边长 = 图标直径），三者同心，否则会出现「高亮圈和图标差半个身位」。
- **每排要各自居中**：`x = c * sp * dirX + r * rowGap * offX` 这种写法
  会让每排**左端对齐在同一条斜线上**，项数一变就歪成三角。
  正确写法是减去本排中点偏移：
  ```java
  float u = (c - (cnt[r] - 1) * 0.5f) * sp;   // c = 本排内第几项
  float x = u * dirX + r * rowGap * offX;
  float y = u * dirY + r * rowGap * offY;
  ```
- **菜单锚点要用屏幕坐标**：展开瞬间窗口刚提交「变全屏」、View 还没重新布局，
  此时 `getX()` 是相对手柄那条窄条的坐标，换算出来会落在屏幕左上角。
  必须存 `getRawX()/getRawY()`，排版时再用 `getLocationOnScreen()` 转回 View 坐标。

### 7.7 菜单项没填图标时显示什么

菜单项只填了名字、没填图标时，按钮上直接用**名字的前两个字**顶着
（名字本来就只有一个字，就显示一个字），字号会自动收窄一点。

规则分布在两处，**必须保持一致**：

| 位置 | 作用 |
|---|---|
| `EdgeGestureService.drawMenu()` | 悬浮窗里**实际绘制**的菜单 |
| `EdgeGestureMenuActivity` | 设置页里的**缩略图预览** |

> 早期只取**首字**，识别度太低 ——「微」「设」这种完全看不出是哪个应用；
> 改成两个字后基本能认出来，又不会溢出圆底。
> `MenuIcons.describeSource()` 里那句「无图标（显示文字前两个字）」
> 就是这条规则的提示文案。

---

## 八、要点速查表

| 事项 | 结论 |
|---|---|
| 发现插件（动作） | 声明 `EDIT_SETTING` + `FIRE_SETTING` 两个组件 |
| **发现插件（触发器）** | **声明 `ACTION_EDIT_EVENT` 到 alias 上**（条件身份已撤除） |
| 触发器应答 | `QUERY_CONDITION` 接收器，`setResultCode(16/17/18)` |
| **触发器变量回传** | **应答里自己 `getResultExtras(true).putBundle(VARIABLES, vars)`；`FIRE_SETTING` 那套在事件链路上不执行** |
| 触发上报 | 插件主动发 `REQUEST_QUERY` + `EXTRA_ACTIVITY`，**不能 setPackage** |
| 事件防伪 | 随机 `MESSAGE_ID` + 60 秒有效期（用 `elapsedRealtime`） |
| 两个身份共用界面 | `activity-alias` 换 `label`，看 `getIntent().getAction()` 分支 |
| 插件显示名 | Activity（或 alias）的 `android:label` |
| 触发方式 | **有序广播**（`sendOrderedBroadcast`） |
| 同步回传 | `setResultExtras(VARIABLES)`，**限 11 秒** |
| **异步回传** | **`setResultCode(3)` + `signalFinish()`，无限制** |
| 超时能否延长 | **不能**，11000ms 硬编码 |
| 变量声明 | 配置界面 `putExtra(RELEVANT_VARIABLES, ...)`，**三段式** |
| 变量映射 | 用户必须在「Output variables」里手动映射 |
| 改代码后 | **必须重新配置插件动作** |
| 变量值类型 | 一律字符串（`putIntAsString`）；`putInt` 会被 Tasker 丢弃 |
| 后台限制 | 广播后 10 秒豁免窗口；悬浮窗权限可解除 |
| 手势方向匹配 | 在 `PluginConditionReceiver` 里比对，**不匹配回 17**，宏不跑 |
| **手势变量（gs_*）** | **已全部撤除**，只做内部匹配用，不对外回传 |
| **菜单在哪配** | 本应用设置页（`EdgeGestureMenuActivity`），宏不参与 |
| **菜单项没填图标** | 显示名字**前两个字**（仅一字就显示一字），字号自动收窄 |
| **App 从最近任务隐藏** | `AppTask.setExcludeFromRecents()`；Intent 标志 / 重建 task 均无效 |
| **菜单结果回传** | `%xsocr_menu_index` / `%xsocr_menu_text` / `%xsocr_menu_action`；**没选中就一个都不传** |
| **跟手菜单前提** | 菜单必须由拿住触摸的那个窗口弹，宏弹不了 |
| **菜单窗口变形** | 只能 `updateViewLayout()` 原地长大，`removeView()+addView()` 会收到 ACTION_CANCEL，手势当场中断 |
| **截屏方式（三条）** | 无障碍（免授权，API 30+）→ root → MediaProjection，自动挑选 |
| **无障碍截图能力** | `CAPABILITY_CAN_TAKE_SCREENSHOT` + `takeScreenshot()`，同为 API 30 |
| **无障碍服务隐私** | 不声明 `accessibilityEventTypes`，`onAccessibilityEvent` 空实现 |
| **摄像头实时识别** | 独立页 `CameraOcrActivity`（Camera2，不引 CameraX）；结果只保留数字，不进插件链路 |
| **相册识别** | 同一页内「相册」按钮走 `ACTION_OPEN_DOCUMENT`（SAF，**不需要读相册权限**）；`inSampleSize` ≤1920 + EXIF 转正；识别后钉住界面并暂停实时 |

---

## 九、参考资料

- 官方 `locale-sdk`（已归档）：`twofortyfouram/android-plugin-api-for-locale`
- Tasker 插件规范：`tasker.joaoapps.com/plugins.html`
- 可用的参考实现：**PP-OCRv6 插件**（本机实测变量回传正常）