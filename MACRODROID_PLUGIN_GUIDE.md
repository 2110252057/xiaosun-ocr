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
│  │  → 干完活 → signalFinish() 往 COMPLETION_INTENT 发广播 │      │
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
        String pkg = fromHost.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_PACKAGE");
        String cls = fromHost.getStringExtra(
                "net.dinglisch.android.tasker.EXTRA_CALL_SERVICE");
        Boolean fg = (Boolean) fromHost.getExtras()
                .get("net.dinglisch.android.tasker.EXTRA_CALL_SERVICE_FOREGROUND");

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

按顺序检查：

| 检查项 | 方法 |
|---|---|
| ① 是否声明了 `RELEVANT_VARIABLES` | 配置界面保存时有没有 `putExtra` |
| ② 是否重新保存了配置 | 改代码后必须重配 |
| ③ 是否做了变量映射 | MacroDroid 动作里「Output variables」 |
| ④ 是否超时 | 日志看耗时是否 > 11000ms |
| ⑤ 是否收到 `PENDING` | 宿主看到 PENDING 才不按超时处理 |

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
public static final String VAR_X     = "%xsocr_x";   // 整数
public static final String VAR_Y     = "%xsocr_y";   // 整数
public static final String VAR_ERROR = "%xsocr_error";
public static final String VAR_TIME  = "%xsocr_time";
public static final String VAR_TIER  = "%xsocr_tier";
```

### 7.2 声明（三段式）

```java
public static final String[] ALL_VARIABLES = {
    VAR_TEXT  + "\n识别文本\n识别出的全部文字（多行用换行连接）",
    VAR_MATCH + "\n是否找到\n指定的文本是否存在（true / false）",
    VAR_X     + "\n坐标X\n指定文本命中处的 x 坐标（整数）",
    VAR_Y     + "\n坐标Y\n指定文本命中处的 y 坐标（整数）",
    VAR_ERROR + "\n错误信息\n失败时才有内容",
    VAR_TIME  + "\n耗时(ms)\n本次识别耗时",
    VAR_TIER  + "\n模型档位\ntiny / small / rapid",
};
```

### 7.3 回传（含整数变量）

```java
Bundle vars = new Bundle();
vars.putString(VAR_TEXT, sb.toString());
vars.putString(VAR_MATCH, matched ? "true" : "false");
vars.putInt(VAR_X, matchX);        // ★ 整数用 putInt
vars.putInt(VAR_Y, matchY);
vars.putString(VAR_ERROR, "");
vars.putString(VAR_TIME, String.valueOf(cost));
vars.putString(VAR_TIER, tier);
```

> ⚠️ **读取时不能用 `getString()`**——对 Integer 会抛 `ClassCastException`。
> 遍历保存时用 `String.valueOf(bundle.get(key))`。

---

## 八、要点速查表

| 事项 | 结论 |
|---|---|
| 发现插件 | 声明 `EDIT_SETTING` + `FIRE_SETTING` 两个组件 |
| 插件显示名 | Activity 的 `android:label` |
| 触发方式 | **有序广播**（`sendOrderedBroadcast`） |
| 同步回传 | `setResultExtras(VARIABLES)`，**限 11 秒** |
| **异步回传** | **`setResultCode(3)` + `signalFinish()`，无限制** |
| 超时能否延长 | **不能**，11000ms 硬编码 |
| 变量声明 | 配置界面 `putExtra(RELEVANT_VARIABLES, ...)`，**三段式** |
| 变量映射 | 用户必须在「Output variables」里手动映射 |
| 改代码后 | **必须重新配置插件动作** |
| 变量值类型 | 最终统一转成字符串，但 `putInt` 更规范 |
| 后台限制 | 广播后 10 秒豁免窗口；悬浮窗权限可解除 |

---

## 九、参考资料

- 官方 `locale-sdk`（已归档）：`twofortyfouram/android-plugin-api-for-locale`
- Tasker 插件规范：`tasker.joaoapps.com/plugins.html`
- 可用的参考实现：**PP-OCRv6 插件**（本机实测变量回传正常）
