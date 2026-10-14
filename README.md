# NJOCR 复刻工程

基于原版 `NJOCR_2.0.apk` 逆向结果复刻的本地离线 OCR 工具。

## 一、复刻原则

原版核心是一份二进制本地引擎（PP-OCRv5 + NCNN），无法用源码重写，
因此本工程的做法是：

| 层 | 做法 |
|---|---|
| 原生推理引擎 | **重编译** `libnjocr.so`（2 个 ABI），模型升级为 **PP-OCRv6 tiny** |
| 模型与字典 | **PP-OCRv6 tiny**（ONNX → ncnn 转换），字典 6904 字符 |
| JNI 绑定 | 类名与 native 方法签名**与原版完全一致**，直接对上原库符号 |
| 服务与接口 | **完整复刻**，协议 100% 对齐原版（已移除 base64 传图） |
| 界面 | **完整复刻**（程序化 UI，两个页签） |

> 因 JNI 符号写死为 `Java_com_wubugncnn_njocr_OCRService_initModels` /
> `Java_com_wubugncnn_njocr_OCRService_nativeProcessBitmap`，
> 所以包名、类名、方法名必须保持原样，**不要改名**。

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
        │   ├── OcrHttpServer.java           本地 HTTP 接口（1666 协议）
        │   ├── Http.java                    极简 HTTP 客户端（供插件复用）
        │   ├── PluginContract.java          Tasker/Locale 插件协议常量
        │   ├── PluginEditActivity.java      插件配置界面（宏里点插件时打开）
        │   └── PluginFireReceiver.java      插件执行入口（宏触发时干活 + 回传变量）
        ├── jniLibs/                  重编译 so（arm64-v8a / armeabi-v7a）
        │   ├── */libnjocr.so         ← PP-OCRv6 推理引擎（tiny/small/rapid 三档）
        │   └── */libc++_shared.so
        ├── assets/                   模型
        │   ├── det.* / rec.*         tiny 档
        │   ├── small/                small 档
        │   └── rapid/                RapidOCR 档（含 cls 方向分类）
        └── res/                      图标与主题
```

## 三、MacroDroid 插件（Tasker/Locale 规范）

本 App 同时是一个 **Tasker/Locale 兼容插件**，MacroDroid / Tasker 可直接调用。

### 怎么用（MacroDroid）

1. 新建宏 → 动作 → **Tasker/Locale Plugin**
2. 在插件列表里选 **小孙OCR**
3. 弹出的配置界面里设：
   - **图片来源**：截取当前屏幕 / 指定图片路径
   - **模型档位**：tiny / small / rapid
   - **输出格式**：纯文本 / 含坐标
4. 保存后，勾选动作里的 **「Block next actions until complete」**（等待完成）
5. 在后续动作里用变量拿结果

### 回传的变量

| 变量 | 内容 |
|---|---|
| `%xsocr_text` | 识别出的全部文本（多行用换行或空格拼接） |
| `%xsocr_count` | 文本块数量 |
| `%xsocr_success` | `true` / `false` |
| `%xsocr_error` | 出错信息（失败时才有内容） |
| `%xsocr_time` | 耗时（毫秒） |
| `%xsocr_tier` | 实际使用的档位 |

> 用 **「Block next actions until complete」** 才会等识别完成；
> 不勾选的话宏会直接往下走，变量可能还没填好。

### 实现说明

- 协议常量**手写内联**，不引入已归档的第三方库（`PluginContract.java`）
- 变量回传走 Tasker 扩展：
  `net.dinglisch.android.tasker.extras.VARIABLES` + 能力位 `2`
- `PluginFireReceiver` 用 `goAsync()`，因为 OCR 可能超过 onReceive 的 10 秒窗口
- 识别统一走本地 HTTP（1666），与 App 内、外部脚本**完全同一条路径**

## 四、接口协议

- 地址：`http://127.0.0.1:1666`
- 路由：`POST /`
- 参数（支持 JSON Body / form 表单 / query 三种方式）：

| 字段 | 说明 |
|---|---|
| `path` | 图片绝对路径（需文件权限） |
| `type` | `1`=纯文本　`2`=Json数组(含坐标)　`3`=详细Json(含矩形) |
| `tier` | 可选：`tiny`（轻量，默认）/ `small`（高精度）/ `rapid`（RapidOCR）。切换后**持续生效** |
| `action` | 可选：`setTier` 表示只切档位、不做识别 |

> **不传 `path` 时自动截屏识别**（有 root 走 `screencap`，无需授权）。
>
> **模型档位**是全局状态，切一次对所有后续请求生效——外部调用与 App 内切换共用同一状态。
>
> 三档差异：
> | 档位 | 特点 |
> |---|---|
> | `tiny` | 最快，模型 3.1 MB，字典 6904 |
> | `small` | 字典 18708，保留更多标点/符号 |
> | `rapid` | **RapidOCR**：clipper 精确后处理 + AngleNet 方向纠正 + 置信度 |
>
> ⚠️ **已移除 `base64` 传图方式。** 原因：base64 会让请求体膨胀 33%，
> 且服务端要先整块解码成字节数组再解码位图，一张 4K 图峰值内存可达 60MB+，
> 低端机容易 OOM。改用 `path` 后由 `decodeFileSafe` 直接按需降采样，内存可控。

- 响应：JSON 文本，成功 `{"status":200,...}`，失败 `{"status":400/404/500,"data":"Error: ..."}`

### 调用示例

```bash
# 启动后台服务（供自动化脚本使用）
am start-foreground-service com.xiaosun.ocr/.OCRService

# 用 curl 测试（传路径）
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"path":"/sdcard/test.png","type":1}'

# 不传 path：自动截屏识别
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"type":3}'

# 只切换模型档位（不识别）
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"tier":"small","action":"setTier"}'

# 切到 RapidOCR 档
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"tier":"rapid","action":"setTier"}'

# 识别的同时切换档位
curl -X POST http://127.0.0.1:1666 -H "Content-Type: application/json" \
     -d '{"path":"/sdcard/test.png","type":1,"tier":"small"}'
```

```javascript
// Auto.js：传路径
var res = http.post("http://127.0.0.1:1666",
        { "path": "/sdcard/test.png", "type": "1" });
log(res.body.string());
```

## 四、实现说明（与原版的差异点）

原版 HTTP 服务端用的是 **Ktor + Netty**（Kotlin 协程库）；
本工程是 Java 模板，从 Java 调用 Ktor 的 suspend DSL 极不稳定，
因此改用 **NanoHTTPD**（纯 Java 嵌入式服务器）实现**完全相同的对外协议**。

**对外行为（端口、路径、type、响应、错误码）完全一致**，
识别结果由同一个 `libnjocr.so` 产出。

> 与最初复刻版的两点差异：
> 1. 已**移除 `base64` 传图**（原版支持）。改用 `path`，并新增按需降采样，
>    避免大图 OOM，见第三节说明。
> 2. `libnjocr.so` 为**重编译版**，内部做了准确率参数优化（见 `build/njocr_patch/`）。

## 四之二、截屏识别：root 优先，无需授权

不传 `path` 时，服务会自行截屏。截屏有两条路径，**按顺序自动选择**：

| 优先级 | 方式 | 是否需要授权 | 说明 |
|---|---|---|---|
| ① | **root 截屏**（`screencap`） | **完全不需要** | `su -c screencap -p <path>`，App 启动时自动探测 |
| ② | **MediaProjection** | 需要用户点一次「开启截屏授权」 | 无 root 时的兜底方案 |

### 行为说明

- **有 root 的设备**：服务启动后自动启用 root 截屏（会先试拍一张确认可用），
  `isScreenCaptureReady` 直接为 `true`，调用方**无需任何授权步骤**，
  也不会出现系统录屏确认框。适合无人值守 / 自动化脚本场景。
- **无 root 的设备**：行为与原来一致，需在 App 内点一次「开启截屏授权」。
- 界面「屏幕截屏识别」卡片会显示当前走的哪条路径，
  并提供 **检测 root 截屏** 手动入口。
- 若 root 截屏在某次调用中失败（例如 SELinux 临时拦截），
  会自动**回退到 MediaProjection**，不会直接报错。

### 为什么用 `screencap` 而不是 `su` + MediaProjection

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

新增文件：`RootScreenCaptureManager.java`。

## 五、构建

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
3. 首次构建需联网下载 AGP 8.13 与依赖（约几分钟）。

## 六、使用步骤

1. 安装 APK，首次启动会申请「所有文件访问权限」。
2. **打开 App 即自动启动 OCR 服务**（无需手动点启动）。
3. 首次启动需在后台加载模型（约 1~3 秒），界面上方会显示服务状态。
4. 切到「在线调试」页，输入图片路径或选择图片，选择格式，点击 **开始识别**。
5. 也可由自动化脚本直接调用 `127.0.0.1:1666`。

> 界面只有「接口文档 / 在线调试」两个页签；服务随 App 启动自动运行。
> 从最近任务划掉 App（进程结束）后，服务会随之停止，下次打开 App 自动恢复。