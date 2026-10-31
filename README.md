# HanimeApp

hanime1.me 的第三方 Android 客户端。网络抓取 / HTML 解析 / 下载与本地库由 **Rust** 实现（编译为 `libhanime_core.so` 通过 JNI 暴露），UI 用 **Jetpack Compose + Material 3**，玻璃质感来自 `io.github.kyant0:backdrop`。

仅供学习与自用，请遵守当地法律与站点条款。

## 功能

- **首页**：顶部题材切换（全部 / 裏番 / 泡麵番 / Motion Anime / 3DCG / 2.5D / 2D動畫 / AI生成 / MMD / Cosplay / 新番預告），下方排序切换（最新上市 / 最新上傳 / 本日排行 / 本週排行 / 本月排行 / 觀看次數 / 讚好比例 / 時長最長 / 他們在看 / 過去 24 小時 / 過去 2 天 / 過去 1 週 / 過去 1 個月 / 過去 3 個月）；下滑分页加载；封面按题材自动适配横版（16:9）与竖版（0.68）两种比例。
- **下载页**（同时是本地资源页）：正在下载 / 已下载 / 未完成三组，进度条与断点续传，已下载条目直接点播本地文件（离线可看），两步确认删除（删文件或仅移出列表）。
- **设置页**：自动播放开关、默认清晰度（1080p / 720p / 480p）、屏蔽已观看开关与清空观看记录、HTTP 代理、下载目录。
- **详情页**：大图 hero、中文番剧名（站点有本地化时显示）、时长 / 观看数 / 日期、点赞比例、作者与分类、标签、三档播放与下载、简介、相关推荐。
- **播放器**：按视频真实宽高比渲染（竖屏加黑边不拉伸）、横向滑动 seek、左右半屏调亮度 / 音量、双击播放暂停、0.5x–2.0x 倍速、旋转、锁定、控制栏自动隐藏。
- **屏蔽已观看**：播放过的条目写入本地 `watched.json`，首页与列表按开关过滤。

## 目录结构

```
hanime-core/          Rust 核心
  src/net.rs          HTTP（浏览器头 + 代理 + gzip）
  src/html.rs         站点解析（tl）
  src/lib.rs          设置 / 已观看 / 下载管理 / 本地库
  src/jni.rs          JNI 桥（Java_com_hanime_app_core_Native_*）
  src/model.rs        数据结构（serde 序列化）
  src/bin/coretest.rs 命令行验证工具
HanimeApp/            Android 工程
  app/src/main/java/com/hanime/app/...
  app/src/main/jniLibs/ 预编译的 libhanime_core.so（arm64-v8a、x86_64）
```

## 构建

### 1. 编译 Rust 核心

需要 rustup + Android NDK（示例为 Windows，NDK 28）。

```bash
export NDKBIN=$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin
export CC_aarch64_linux_android=$NDKBIN/clang.exe
export AR_aarch64_linux_android=$NDKBIN/llvm-ar.exe
export CFLAGS_aarch64_linux_android=--target=aarch64-linux-android24
export CC_x86_64_linux_android=$NDKBIN/clang.exe
export AR_x86_64_linux_android=$NDKBIN/llvm-ar.exe
export CFLAGS_x86_64_linux_android=--target=x86_64-linux-android24

cd hanime-core
cargo build --release --features jni-bridge --target aarch64-linux-android
cargo build --release --features jni-bridge --target x86_64-linux-android
```

产物复制到：

```
HanimeApp/app/src/main/jniLibs/arm64-v8a/libhanime_core.so
HanimeApp/app/src/main/jniLibs/x86_64/libhanime_core.so
```

`hanime-core/.cargo/config.toml` 里已配好 linker（NDK 的 `clang.exe` + `--target=<triple>24`）。**注意：改完 Rust 必须重新交叉编译并覆盖这两个 `.so`，否则 APK 里跑的是旧解析器。**

### 2. 构建 APK

```bash
cd HanimeApp
./gradlew :app:assembleRelease
# 产物 app/build/outputs/apk/release/app-release.apk
```

需要 JDK 17+ 和 Android SDK（compileSdk 35）。首次构建需联网拉依赖。

### 3. 命令行验证解析（可选）

```bash
cargo run --release --bin coretest -- raw https://hanime1.me/
cargo run --release --bin coretest -- lf page.html     # 解析列表页
cargo run --release --bin coretest -- wt watch.html ID  # 解析详情页
```

## 实现要点

- **必须带完整浏览器头**：站点前置 Cloudflare，缺少 `Accept` / `Accept-Language` / `Accept-Encoding` 会返回 403。客户端只发 `gzip`（不发 `br`，因为 ureq 未启用 brotli 时会拿到未解压的 body）。
- **列表页有两套模板**：裏番 / 泡麵番 / 新番預告用 `.video-card-inner`，其余题材用 `.video-item-container`（与首页横向卡同构）。`parse_list` 先试前者，为空再回退到后者。
- **封面 URL 必须带 `?secure=` 参数**：`image/cover/<id>.jpg` 不带参数会 403，所以只能用页面里刮到的完整 URL。
- **中文番剧名**不在 `og:title` 里，而在 `div.video-description-panel` 中「觀看次數」之后的第一个 div。
- **代理优先级**：设置页手填的代理 > Android 系统代理（模拟器无直连外网时可填 `10.0.2.2:<宿主代理端口>`）。

## 已知限制

- 未做代码混淆与签名加固，用的是 debug 签名，仅供自测。
- 登录相关功能（点赞、订阅、评论）未实现——视频播放与下载不需要登录。
- 站点没有公开 JSON API（`/api/v8/*` 均 404），全部依赖 HTML 解析，站点改版会导致解析失效。
