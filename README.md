# 小孙OCR

本地离线 OCR 工具。识别全部在本机完成，**无需联网**。

支持截屏识别、图片文件识别，并提供本地 HTTP 接口与 **MacroDroid / Tasker 插件**能力，
方便接入自动化流程。

## 一、技术构成

| 层 | 说明 |
|---|---|
| 推理引擎 | 自编译 native 库（2 个 ABI），基于 PP-OCRv6 模型；`rapid` 档改由 ML Kit 承担 |
| 模型档位 | `tiny`（轻量，native）/ `small`（高精度，native）/ `rapid`（ML Kit 捆绑版，英文+中文） |
| JNI 绑定 | 静态符号绑定，类名与方法签名须与 native 库一致 |
| 服务与接口 | 前台服务 + 本地 HTTP（127.0.0.1:1666） |
| 插件 | 完整实现 Tasker/Locale 协议，支持异步变量回传 |
| 界面 | 程序化 UI，两个页签（接口文档 / 在线调试） |

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
        │   ├── MainActivity.java            主界面（文档/调试 两个页签）
        │   ├── OCRService.java              前台服务 + JNI native 声明
        │   ├── OcrHttpServer.java           本地 HTTP 接口
        │   ├── Http.java                    极简 HTTP 客户端（供插件复用）
        │   ├── ScreenCaptureManager.java    MediaProjection 截屏
        │   ├── RootScreenCaptureManager.java root 截屏（screencap）
        │   ├── BootReceiver.java            开机自启
        │   ├── PluginContract.java          Tasker/Locale 插件协议常量
        │   ├── PluginEditActivity.java      插件配置界面
        │   └── PluginFireReceiver.java      插件执行入口（回传变量）
        ├── jniLibs/                  native 库（arm64-v8a / armeabi-v7a）
        │   ├── */libnjocr.so         推理引擎（tiny/small 两档）
        │   └── */libc++_shared.so
        ├── assets/                   模型
        │   ├── det.* / rec.*         tiny 档
        │   └── small/                small 档
        └── res/                      图标与主题
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
- 协议常量**手写内联**，不引入已归档的第三方库（`PluginContract.java`）
- 识别统一走本地 HTTP（1666），与 App 内、外部脚本**完全同一条路径**

### ⚠️ 建议开启「悬浮窗权限」

MacroDroid 在后台触发插件时，App 可能处于后台或已被回收。
要让插件能**自动弹出截屏授权框**，需要 `SYSTEM_ALERT_WINDOW`（悬浮窗）权限 ——
Android 10+ 只允许拥有该权限的应用从后台启动 Activity。

开启方式：App 内「屏幕截屏识别」卡片 → **悬浮窗权限** 按钮。

> 不开也能用，但需要先手动打开一次 App 完成截屏授权；
> 授权在服务存活期间有效，服务重启后需要重新授权。

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

## 五、截屏识别：root 优先，无需授权

不传 `path` 时，服务会自行截屏。截屏有两条路径，**按顺序自动选择**：

| 优先级 | 方式 | 是否需要授权 | 说明 |
|---|---|---|---|
| ① | **root 截屏**（`screencap`） | **完全不需要** | `su -c screencap -p <path>`，App 启动时自动探测 |
| ② | **MediaProjection** | 需要用户点一次「开启截屏授权」 | 无 root 时的兜底方案 |

### 行为说明

- **有 root 的设备**：服务启动后自动启用 root 截屏（会先试拍一张确认可用），
  `screenCaptureReady` 直接为 `true`，调用方**无需任何授权步骤**，
  也不会出现系统录屏确认框。适合无人值守 / 自动化脚本场景。
- **无 root 的设备**：需在 App 内点一次「开启截屏授权」。
- 界面「屏幕截屏识别」卡片会显示当前走的哪条路径，
  并提供 **检测 root 截屏** 手动入口。
- 若 root 截屏在某次调用中失败（例如 SELinux 临时拦截），
  会自动**回退到 MediaProjection**，不会直接报错。

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

## 六、构建

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

## 七、使用步骤

1. 安装 APK，首次启动会申请「所有文件访问权限」。
2. **打开 App 即自动启动 OCR 服务**（无需手动点启动）。
3. 首次启动需在后台加载模型（约 1~3 秒），界面上方会显示服务状态。
   > **冷启动行为（修复⑥）**：服务采用「HTTP 先上线、模型后台加载」，
   > 因此**端口会立刻可连**（`GET /last` 秒回），但此时识别尚未可用：
   > `POST /` 会返回 `503 {"status":503,...}`，`/last` 里的 `modelReady` 为 `false`。
   > 插件会自动轮询等待模型就绪后再发识别请求，无需人工干预；
   > 自己写脚本调用时，请以 `modelReady` 或 `503` 作为"稍后重试"的信号。
4. 切到「在线调试」页，输入图片路径或选择图片，选择格式，点击 **开始识别**。
5. 也可由自动化脚本直接调用 `127.0.0.1:1666`。

> 界面只有「接口文档 / 在线调试」两个页签；服务随 App 启动自动运行。
> 注意：从最近任务划掉 App 时，本服务**不会**随之停止 —— 服务在 Manifest 中声明了
> `android:stopWithTask="false"`，正是为了让外部自动化脚本的调用不因清理后台而中断。
> 如需彻底停止，请在系统设置中「强行停止」应用，或调用系统停止服务接口。