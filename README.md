# 小孙OCR

本地离线 OCR 工具。识别全部在本机完成，**无需联网**。

支持**三种截屏识别**（无障碍 / root / 录屏授权，自动挑选）、图片文件识别，
提供本地 HTTP 接口与 **MacroDroid / Tasker 插件**能力，
并内置**边缘滑动手势触发器**（含跟手滑出菜单），支持**从最近任务隐藏**，方便接入自动化流程。

## 一、技术构成

| 层 | 说明 |
|---|---|
| 推理引擎 | 自编译 native 库（2 个 ABI），基于 PP-OCRv6 模型；`rapid` 档改由 ML Kit 承担 |
| 模型档位 | `tiny`（轻量，native）/ `small`（高精度，native）/ `rapid`（ML Kit 捆绑版，英文+中文） |
| JNI 绑定 | 静态符号绑定，类名与方法签名须与 native 库一致 |
| 服务与接口 | 前台服务 + 本地 HTTP（127.0.0.1:1666） |
| 插件 | 完整实现 Tasker/Locale 协议，支持异步变量回传 |
| 截屏 | 无障碍截图（API 30+，免 root 免授权）/ root `screencap` / MediaProjection，自动挑选 |
| 边缘手势 | 悬浮窗手柄 + 8 方向判定，可跟手滑出菜单，结果经插件事件通道回传 |
| 界面 | 程序化 UI，两个页签（OCR设置 / 滑动触发） |

> **JNI 约定**：native 库没有 `JNI_OnLoad`，无法动态注册，
> 符号形如 `Java_<包名下划线化>_<类名>_<方法名>`，当前为
> `Java_com_xiaosun_ocr_OCRService_*`。
> ⚠️ 改动包名 / 类名 / 方法签名必须同步重编 native 库，否则启动即 `UnsatisfiedLinkError`。

## 二、目录结构

```
ocr/
├── build.gradle                     顶层构建
├── settings.gradle
├── gradle.properties
├── local.properties                 sdk.dir=/opt/android-sdk
├── gradle/libs.versions.toml        依赖版本目录
└── app/
    ├── build.gradle                 应用模块
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/xiaosun/ocr/
        │   ├── MainActivity.java            主界面（OCR设置 / 滑动触发 两个页签）
        │   ├── OCRService.java              前台服务 + JNI native 声明
        │   ├── OcrHttpServer.java           本地 HTTP 接口
        │   ├── Http.java                    极简 HTTP 客户端（供插件复用）
        │   ├── OcrAccessibilityService.java 无障碍截图（API 30+ takeScreenshot）
        │   ├── ScreenCaptureManager.java    MediaProjection 截屏
        │   ├── RootScreenCaptureManager.java root 截屏（screencap）
        │   ├── BootReceiver.java            开机自启
        │   ├── PluginContract.java          Tasker/Locale 插件协议常量
        │   ├── PluginEditActivity.java      插件配置界面
        │   ├── PluginFireReceiver.java      插件执行入口（回传变量）
        │   ├── PluginConditionReceiver.java 触发器应答（回传手势/菜单变量）
        │   ├── TaskerEventProtocol.java     事件身份防伪数据
        │   ├── EdgeGestureService.java      手势引擎（悬浮窗 + 菜单绘制）
        │   ├── EdgeGesturePrefs.java        手势/菜单配置读写
        │   ├── EdgeGestureActivity.java     手势设置主页
        │   ├── EdgeGestureEditorActivity.java 单个手柄编辑
        │   ├── EdgeGestureMenuActivity.java 滑动菜单配置
        │   └── MenuIcons.java               菜单项图标加载
        ├── jniLibs/                  native 库（arm64-v8a / armeabi-v7a）
        │   ├── */libnjocr.so         推理引擎（tiny/small 两档）
        │   └── */libc++_shared.so
        ├── assets/                   模型
        │   ├── det.* / rec.*         tiny 档
        │   └── small/                small 档
        └── res/
            ├── xml/accessibility_service_config.xml   无障碍截图服务配置
            └── ...                  图标与主题
```

## 三、MacroDroid 插件

本 App 是一个 **Tasker/Locale 兼容插件**，MacroDroid / Tasker 可直接调用。

> 📖 **开发细节另见**：[MACRODROID_PLUGIN_GUIDE.md](MACRODROID_PLUGIN_GUIDE.md)
> —— MacroDroid 没有官方插件文档，那份指南是反编译 MacroDroid + PP-OCRv6 插件
> 得到的完整协议说明（常量表、能力位、异步回传机制、踩坑记录）。

### 配置步骤

1. 新建宏 → 动作 → **Tasker/Locale Plugin**
2. 在插件列表里选 **小孙OCR**
3. 在配置界面设置：
   - **图片来源**：截取当前屏幕 / 指定图片路径
   - **模型档位**：tiny / small / rapid
   - **输出格式**：纯文本 / 含坐标
   - **指定文本**（可选）：要查找的文字
   - **匹配方式**：包含文本 / 匹配文本（完全一致）
4. **保存**（保存时才会把变量清单告知宿主）
5. 在动作里展开 **Output variables**，把需要的变量映射到宏变量

### 回传的变量

| 变量 | 类型 | 内容 |
|---|---|---|
| `%xsocr_text` | 文本 | 识别出的全部文本（多行用换行或空格拼接） |
| `%xsocr_match` | 文本 | 指定文本是否找到：`true` / `false` |
| `%xsocr_x` | **整数** | 命中处的 x 坐标；未找到为 `-1` |
| `%xsocr_y` | **整数** | 命中处的 y 坐标；未找到为 `-1` |
| `%xsocr_error` | 文本 | 出错信息（失败时才有内容） |
| `%xsocr_time` | 文本 | 耗时（毫秒） |
| `%xsocr_tier` | 文本 | 实际使用的档位 |

> 坐标基于**截图原始像素**，可直接用于点击坐标。

**触发器（事件）路径**回传的是另一组变量 —— 手势本身不发识别请求：

| 变量 | 类型 | 内容 |
|---|---|---|
| `%xsocr_menu_index` | **整数** | 滑动菜单选中项序号（从 0 开始）；`-1` = 取消或未启用菜单 |
| `%xsocr_menu_text` | 文本 | 选中项的名字（取消时为空） |
| `%xsocr_menu_action` | 文本 | 选中项的动作值（取消时为空） |

> 手势方向**不单独回传**：插件在收到查询时就已经在内部做过方向匹配，
> 不匹配的这次直接回 17、宏根本不跑 —— 能跑起来的宏就是方向对的那个。
> 详见 [MACRODROID_PLUGIN_GUIDE.md](MACRODROID_PLUGIN_GUIDE.md) 第 7.4 / 7.6 节。

### 匹配方式说明

| 方式 | 行为 | 例子（查找「确定」） |
|---|---|---|
| **包含文本** | 文本块含这几个字即算命中 | 匹配「确定提交」「请点确定」 |
| **匹配文本** | 整个文本块与它完全一致 | 只匹配「确定」 |

两者均**不区分大小写**，取第一个命中的文本块。

### 实现说明

- **异步变量回传**：宿主发有序广播后只等 11 秒，超时即丢弃结果。
  本插件先回 `RESULT_CODE_PENDING`，再通过宿主预置的 `COMPLETION_INTENT`
  在识别完成后补送变量，因此**不受该超时限制**。
- **服务自动拉起**：插件触发时若 App 已被回收，会在广播豁免窗口内
  `startForegroundService()` 重新拉起服务。
- **截屏授权自动申请**：无 root 时截屏需要 MediaProjection 授权，
  而该授权只能由 Activity 发起。插件会拉起透明的
  `ScreenCaptureRequestActivity` 代为申请（需「悬浮窗权限」）。
- **无障碍截图免去授权**：若用户开启过「无障碍截图」（见第五节），
  截屏走这条路径，插件**既不需要悬浮窗权限、也不会弹授权框** ——
  这是无人值守场景最省事的一种配置。
- 协议常量**手写内联**，不引入已归档的第三方库（`PluginContract.java`）
- 识别统一走本地 HTTP（1666），与 App 内、外部脚本**完全同一条路径**

### ⚠️ 两种「免授权」配置，任选一种

MacroDroid 在后台触发插件时，App 可能处于后台或已被回收。
默认情况下要让插件能**自动弹出截屏授权框**，需要 `SYSTEM_ALERT_WINDOW`
（悬浮窗）权限 —— Android 10+ 只允许拥有该权限的应用从后台启动 Activity。

开启方式：App 内「OCR设置」页 → 屏幕截屏识别卡片 → **悬浮窗权限** 按钮。

> 不开也能用，但需要先手动打开一次 App 完成截屏授权；
> 授权在服务存活期间有效，服务重启后需要重新授权。

**但如果你用了下面任一种方式，连悬浮窗权限都不需要：**

| 方式 | 条件 | 效果 |
|---|---|---|
| **无障碍截图** | Android 11+，勾选一次 | 免 root、免授权、不弹框 |
| **root 截屏** | 设备有 root | 免授权、不弹框 |

两者都会让 `screenCaptureReady` 直接为 `true`，插件跳过整个授权流程。
详见第五节。

## 四、接口协议

- 地址：`http://127.0.0.1:1666`

### 路由

| 路由 | 说明 |
|---|---|
| `POST /` | 识别（`path` 可选，不传则自动截屏） |
| `GET /last` | 读取上一次识别结果（JSON） |
| `GET /last?format=text` | 同上，返回纯文本 |
| `GET /ocr?format=text` | 一步完成「取图 → 识别 → 返回文本」 |

### POST / 参数

支持 JSON Body / form 表单 / query 三种方式：

| 字段 | 说明 |
|---|---|
| `path` | 图片绝对路径（需文件权限） |
| `type` | `1`=纯文本　`2`=Json数组(含坐标)　`3`=详细Json(含矩形) |
| `tier` | 可选：`tiny`（轻量，默认）/ `small`（高精度）/ `rapid`（ML Kit，英文+中文）。切换后**持续生效** |
| `action` | 可选：`setTier` 表示只切档位、不做识别 |

> **不传 `path` 时自动截屏识别**（有 root 走 `screencap`，无需授权）。
>
> **模型档位**是全局状态，切一次对所有后续请求生效——外部调用与 App 内切换共用同一状态。
>
> 三档差异：
> | 档位 | 特点 |
> |---|---|
> | `tiny` | 最快，模型最小，字典 6904 |
> | `small` | 字典 18708，保留更多标点/符号 |
> | `rapid` | **ML Kit 文字识别（捆绑版）**：模型随 APK 打包，**不需要 Google Play 服务、不联网下载**；英文 + 中文（中英混排），输出行级包围盒与置信度 |
>
> ⚠️ **不支持 `base64` 传图。** 原因：base64 会让请求体膨胀 33%，
> 且服务端要先整块解码成字节数组再解码位图，一张 4K 图峰值内存可达 60MB+，
> 低端机容易 OOM。改用 `path` 后由 `decodeFileSafe` 直接按需降采样，内存可控。

- **状态码（修复⑨）**：HTTP 状态码与响应体里的 `status` **保持一致**。
  例如图片不存在 → HTTP `404` **且** body `{"status":404,...}`；
  模型尚在加载 → HTTP `503` **且** body `{"status":503,...}`。
  于是 `curl -f`、Auto.js、OkHttp 等只看状态码的调用方也能正确判错。
  > ⚠️ 唯一例外：`GET /last` **恒回 HTTP 200** —— 它是"探活"接口，
  > 只要服务在监听就算通；"还没有识别记录"属于业务状态，写在 body 的 `status` 里。
  > 插件路径（`PluginFireReceiver`）读的始终是 body 内的 `status`，不受影响。

- 响应：JSON 文本，成功 `{"status":200,...}`，失败 `{"status":400/404/500/503,"data":"Error: ..."}`

### 调用示例

```bash
# 启动后台服务（供自动化脚本使用）
am start-foreground-service com.xiaosun.ocr/.OCRService

# 传路径识别
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"path":"/sdcard/test.png","type":1}'

# 不传 path：自动截屏识别
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"type":3}'

# 只切换模型档位（不识别）
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"tier":"small","action":"setTier"}'

# 识别的同时切换档位
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"path":"/sdcard/test.png","type":1,"tier":"small"}'

# 一步完成截屏+识别，直接拿纯文本
curl "http://127.0.0.1:1666/ocr?format=text"

# 读取上一次识别结果
curl "http://127.0.0.1:1666/last?format=text"
```

```javascript
// Auto.js：传路径
var res = http.post("http://127.0.0.1:1666",
        { "path": "/sdcard/test.png", "type": "1" });
log(res.body.string());
```

## 五、截屏识别：三种方式自动挑选

不传 `path` 时，服务会自行截屏。截屏有**三条**路径，**按顺序自动挑选**，
前一条失败就往下试，不会因为某条路临时失效就整体不可用：

| 优先级 | 方式 | 是否需要授权 | 说明 |
|---|---|---|---|
| ① | **无障碍截图** | **勾选一次，永久免授权** | `AccessibilityService.takeScreenshot()`，需 Android 11+、不需要 root |
| ② | **root 截屏**（`screencap`） | **完全不需要** | `su -c screencap -p <path>`，App 启动时自动探测 |
| ③ | **MediaProjection** | 每次重启都要点一次「开启截屏授权」 | 前两者都不可用时的兜底 |

### ① 无障碍截图（最省事）

Android 11（API 30）给无障碍服务开了 `takeScreenshot()`。用户只需在系统
「设置 → 无障碍」里**勾选一次**，之后本应用随时可截、不弹框、不需要 root。

开启方式：App 内「OCR设置」页 → 截屏识别卡片 → **无障碍截图** 按钮，
在列表里找到「**小孙OCR 截图**」并开启。

> **它是可选的一条路，不是必需。** 不开也不影响其它功能，只是退回 ② / ③。

**关于隐私**：该服务 `onAccessibilityEvent` 是**空实现**，配置里也
**没有声明任何 `accessibilityEventTypes`** —— 它收不到屏幕文本、控件树、
点击或按键事件，只保留「截图」这一项能力。用户勾选它，等于只说了一句
「允许你在需要时截一张屏」。

> 实现要点：`takeScreenshot()` 是回调式 API，而 `captureScreen()` 是同步
> 返回 `Bitmap` 的老接口，中间用 `CountDownLatch` 桥接（8 秒上限）。
> 回调给的 `HardwareBuffer` 包装出的是 HARDWARE 位图，必须在
> **关闭缓冲之前** `copy(ARGB_8888)` 落到软件内存，否则 OCR 引擎读不到像素。

### 行为说明

- **开了无障碍**：任何设备（Android 11+）都直接可用，**不需要 root、
  不需要录屏授权**。这是无人值守最推荐的一种配置。
- **有 root 的设备**：服务启动后自动启用 root 截屏（会先试拍一张确认可用），
  `screenCaptureReady` 直接为 `true`，调用方**无需任何授权步骤**，
  也不会出现系统录屏确认框。适合无人值守 / 自动化脚本场景。
- **两者都没有的设备**：需在 App 内点一次「开启截屏授权」，
  且服务重启后需要重新授权。
- 界面「屏幕截屏识别」卡片会显示当前走的哪条路径，
  并提供 **无障碍截图** / **检测 root 截屏** / **开启截屏授权** 三个入口。
- 任一条路在某次调用中失败（例如 SELinux 临时拦截、投屏会话失效），
  会自动**回退到下一条**，不会直接报错。

### 为什么用 `screencap`

`screencap` 是 Android 自带的系统命令，root 下可直接抓取当前屏幕，
只依赖一次 `su` 授权（由 Magisk / SuperSU 管理，一次授权长期有效），
与系统的 MediaProjection 授权机制完全独立。

> 首次调用 `su` 时，Magisk 等会弹一次「授权」框需要用户确认，
> 因此探测超时给得比较宽松（20 秒），避免用户还没点就被判为"无 root"。

### 实现细节

- PNG 先写到应用私有缓存目录（`/data/data/<pkg>/cache`），
  App 自身必定可读、**不依赖存储权限**；失败再退回外部缓存目录。
- 解码后做「旋正 + 去透明通道」再交给 OCR 引擎
  （引擎对带 alpha 通道的画面表现不稳定）。
- 命令用 `sh -c` 包一层完整字符串传入，避免 `ProcessBuilder` 拆参数出错；
  同时排空子进程输出，防止缓冲区写满导致 `screencap` 阻塞。

## 六、边缘滑动手势

在屏幕边缘放一个可拖动的**手柄**，手指从手柄上划过设定距离即触发
（滑过即响应，不必抬手）。共 **8 个方向**（上/下/左/右 + 四个斜向），
每个方向的触发距离可单独调，也可让某个方向不响应。

设置入口：App 主界面 → **滑动触发** 页。

### 与宏的配合

手势引擎本身**不执行任何动作** —— 它只做一件事：告诉 MacroDroid
「某个方向的手势成立了」。真正干什么由宏决定。

配置步骤：

1. 「滑动触发」页 → **打开手势设置**，添加手柄（位置、宽度、颜色、可触发方向）
2. 在 MacroDroid 里新建宏 → 触发器 → **小孙OCR · 边缘滑动事件**
3. 在插件配置界面选定**方向**（不匹配时插件直接回 17，宏不会跑）
4. 展开 **Output variables**，把菜单三项映射到宏变量

### 滑动菜单（跟手滑出）

开启后，滑过触发距离**不再立即触发宏**，而是弹出一排按钮；
继续滑到某一项上（跟手高亮），抬手才选中它。

配置页：**滑动触发** 页 → **滑动菜单**。

可调项：

| 分组 | 项目 |
|---|---|
| 开关 | 启用 / 关闭 |
| 外观 | 图标大小、显示文字、文字大小、文字间距、浅色圆底 |
| 布局 | 每排数量、逐排数量（逐排单独覆盖）、图标间距、每排间距 |
| 配色 | 圆底颜色、文字颜色、选中颜色（支持 `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB`） |
| 交互 | 热区大小（命中半径 = 图标半径 × 倍率，100%~300%，带感应范围预览） |
| 菜单项 | 添加 / 改名 / 选图标 / 排序 / 删除 |

菜单项有三个字段：

| 字段 | 说明 |
|---|---|
| 名字 | 按钮上显示的文字，必填 |
| 动作值 | 回传给宏的 `%xsocr_menu_action`；留空则与名字相同 |
| 图标 | `app:包名` / `file:路径` / 资源名，留空则显示名字前两个字 |

> **动作值为什么要单独一栏**：名字是给人看的、动作值是给宏比的。
> 按钮可以叫「微信」而动作值叫 `wechat`，宏里的判断比按序号好读得多。

**排列方式**：图标沿 45° 斜线从左下排到右上，**每排各自居中**；
一排排满就在上方另起一排（每排数量可逐排单独设置）。
从触发位置弹出，出屏时先平移圆心再整体等比缩小。

> **菜单项没填图标时**：用名字的**前两个字**顶着（名字本来就只有一个字，就显示一个字），
> 字号会自动收窄一点。只取一个字（早先的行为）认不出来 ——
> 「微」「设」这种完全看不出是哪个应用。设置页的缩略图预览与实际绘制**共用同一套规则**。

### 为什么菜单必须由本应用弹

普通做法走不通，卡在 Android 输入分发的一条硬规则上：

> `InputDispatcher` 在手指按下（`ACTION_DOWN`）那一刻做一次命中判定，
> 把**整条触摸序列**绑定给当时那个窗口。此后 MOVE / UP 只投递给它。
> 中途新建的窗口收不到 MOVE（无法跟手高亮），也收不到 UP（无法判定选中）。

官方**没有任何 API** 能把进行中的触摸序列转交给别的窗口或进程。
更关键的是：宏的 Java 动作是**一次性脚本**，跑完即退，根本没法常驻持有窗口。
所以菜单由**已经拿住那次触摸的那个悬浮窗**自己弹（`EdgeGestureService`）。

关键在于**宏被唤起的时刻，正是手指抬起的时刻** —— 所以宏侧既不需要常驻，
也不需要处理「手指还按着」的中间态，只是在「发生了」之后按结果做事。

### 屏蔽系统侧滑返回

手势导航下，系统是从屏幕边缘往内判定「返回」的，优先级比应用窗口还高 ——
手柄贴着边缘时，手指刚碰上去就被系统当成返回吃掉了，表现为「条子在那儿但按不动」。

本应用用平台 API `setSystemGestureExclusionRects()`（API 29+）申请排除区，
**非 MIUI 专属**。开启的手柄会实时上报自己的矩形，系统据此把这一小条的
边缘手势让给应用。

> 曾经还提供一个「整个边缘」选项（把整条边都变成触摸区），**已移除**：
> 整条边全变触摸区会让系统侧滑返回彻底失效、触摸目标混乱。
> 现在只保留「手柄条 + 调宽」这一条路 —— 手柄宽度可调范围 `[0, 200] dp`。

## 七、构建

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/opt/android-sdk
cd /sdcard/AndroidIDEProjects/ocr
bash ./gradlew assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 构建提示

1. **不要用 `./gradlew`**，`/sdcard` 不支持执行位，请用 `bash ./gradlew`。
2. 若遇到 `AAPT2 Daemon startup failed`（容器/受限环境常见），可加：
   ```
   android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2
   ```
   写入 `gradle.properties`，或改用 Android Studio / AndroidIDE 构建。
3. 首次构建需联网下载 AGP 与依赖（约几分钟）。

## 八、使用步骤

1. 安装 APK，首次启动会申请「所有文件访问权限」。
2. **打开 App 即自动启动 OCR 服务**（无需手动点启动）。
3. 首次启动需在后台加载模型（约 1~3 秒），界面上方会显示服务状态。
   > **冷启动行为（修复⑥）**：服务采用「HTTP 先上线、模型后台加载」，
   > 因此**端口会立刻可连**（`GET /last` 秒回），但此时识别尚未可用：
   > `POST /` 会返回 `503 {"status":503,...}`，`/last` 里的 `modelReady` 为 `false`。
   > 插件会自动轮询等待模型就绪后再发识别请求，无需人工干预；
   > 自己写脚本调用时，请以 `modelReady` 或 `503` 作为"稍后重试"的信号。
4. 切到「**OCR设置**」页，输入图片路径或选择图片，选择格式，点击 **开始识别**。
5. 也可由自动化脚本直接调用 `127.0.0.1:1666`。
6. 想用边缘滑动手势，切到「**滑动触发**」页启用，并在 MacroDroid 里
   加触发器「小孙OCR · 边缘滑动事件」。

> 界面只有「**OCR设置** / **滑动触发**」两个页签；服务随 App 启动自动运行。
> 「OCR设置」页顶部那张开关卡控制 OCR 服务本身（关掉后接口不再监听、也不开机自启），
> 它与边缘滑动手势**互不影响** —— 只用手势触发宏的人可以把 OCR 服务关掉。
> 注意：从最近任务划掉 App 时，本服务**不会**随之停止 —— 服务在 Manifest 中声明了
> `android:stopWithTask="false"`，正是为了让外部自动化脚本的调用不因清理后台而中断。
> 如需彻底停止，请在系统设置中「强行停止」应用，或调用系统停止服务接口。

## 九、从最近任务隐藏

「OCR设置」页有一张开关卡：**从最近任务隐藏**（默认关）。

开启后，多任务界面里不再出现本应用的卡片，但**服务照跑、手势照用**，
从桌面图标照常进入。

### 为什么不用 Intent 标志

这是踩过一圈坑的地方，记下来免得重走：

| 方案 | 结果 |
|---|---|
| `Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS` | ❌ 只在 task **首次创建**时记进 `baseIntent`，之后补的标志会被丢掉 |
| `TaskDescription` 里的 `excludeFromRecents` | ❌ 该类**根本没有**这个字段（`ActivityManager$TaskDescription` 无此 API） |
| 带标志**重建** task | ❌ 系统会**复用已存在的 task**（task id 不变），后到的标志同样被丢 |
| **`ActivityManager.AppTask.setExcludeFromRecents()`** | ✅ 就地改、立即生效、无需重建 |

正确做法是拿到本应用的 task 列表逐个设（API 21+）：

```java
ActivityManager am =
        (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
for (ActivityManager.AppTask t : am.getAppTasks()) {
    t.setExcludeFromRecents(hidden);   // hidden = 是否隐藏
}
```

设置值存在 `xsocr_prefs` 的 `hide_from_recents`（默认 `false`，老用户行为不变）；
`MainActivity` 在 `onCreate` / `onResume` 各对齐一次，拨开关时**当场生效**
（不用切出去再进来）。

> 与第八节末尾那条「划掉 App 服务也不停」**不冲突**：那条说的是
> `android:stopWithTask="false"`，管的是**服务存活**；
> 这个开关管的是**卡片显不显示**。两件事，可以同时要。
