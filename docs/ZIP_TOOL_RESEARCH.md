# PhoneAgent 压缩能力选型调研（1.0.14 前置）

> 背景：用户实测反馈，Agent 在 Find X9 上无法生成 xlsx/docx/pptx — 设备无 python / zip / java / busybox / jar，只有 unzip 与 toybox（不能建 zip）。模型被迫用 printf 手工拼 ZIP，token 消耗巨大且易写坏（CRC、中央目录偏移）。
> 本文只做选型，不动手实现。

## 现状事实

- App 已有 `handler = "api"` 的原生工具通道（`ShizukuExecutor.execApi`），主进程直接执行，**不需要 Shizuku/Root**。
- 已有 `docx_text` 工具靠 `unzip -p "{path}" word/document.xml | sed ...` 实现，设备一旦无 unzip 就失效。
- `AndroidManifest.xml` 已声明 `MANAGE_EXTERNAL_STORAGE`，原生代码可直接读写 `/sdcard`。
- 构建：AGP + Kotlin，minSdk 26 / targetSdk 36，当前 APK 55 MB。

## 候选与实测体积

| 方案 | 体积 | 依赖/权限 | 能做什么 | 判断 |
|---|---|---|---|---|
| A 原生 `java.util.zip`（Kotlin 实现工具） | **0 KB 新增依赖** | 无；主进程执行 | 建/解 zip、列目录、store/deflate | **首选** |
| B zip4j 2.11.5 | jar 205 KB | Apache-2.0；纯 Java | 建/解、AES 加密、Zip64、进度 | 能力冗余，暂不引入 |
| C commons-compress 1.27.1 | jar 1.06 MB | Apache-2.0 | tar/7z/bzip2/xz/zip | 只在要多格式时才值得 |
| D busybox（伪装 `libbusybox.so` 入 jniLibs） | 静态 arm64 约 1.1–1.3 MB；Termux deb 472 KB（动态链接，还拖 libandroid-support 等） | 只需打包，无需权限 | zip/unzip/tar/gzip/sed/awk/find… | **可做"可选组件"** |
| E Termux `zip` + `unzip` 单独二进制 | 142 KB + 117 KB | 动态链接依赖多 | 仅 zip/unzip | 不如 D |
| F p7zip / libarchive JNI | 2–3 MB + 自编译 | 需 NDK 构建 | 还能读 rar/7z | 成本高，不做 |
| G 纯 shell 手工拼 ZIP（模型当前做法） | 0 | 无 | 理论可行 | 不可作为产品路径：token 爆炸、易坏 |

> Android 10+ 对 untrusted_app 强制 W^X：应用私有目录里写进去的二进制**不允许 exec**（`error=13 Permission denied`）。唯一正路是把二进制改名成 `lib*.so` 放进 `jniLibs`，安装时由 PackageManager 解到 `nativeLibraryDir`——该目录只读且被 SELinux 授予可执行权限，系统只按 `.so` 后缀筛选、不校验 ELF 是否真是共享库。

## 推荐

**本版（1.0.14）只做 A。** 三个工具，走 api 通道：

- `file_zip(src, dest, level)` — src 支持目录或逗号分隔多路径，level 0=store / 1–9 deflate；目录递归、保留相对路径、跳过隐藏垃圾文件。
- `file_unzip(path, dest)` — 解压并做 Zip Slip 防护（拒绝 `..` 与绝对路径条目）。
- `file_zip_list(path)` — 列条目名与大小，供模型先看清结构再决定怎么改（改 docx/xlsx 必备）。

顺带把 `docx_text` 从 `unzip -p | sed` 改成本地实现（读 `word/document.xml` 后剥标签），彻底摆脱对设备 unzip 的依赖。

**收益不止 Office**：备份打包、日志打包上传、解 apk/zip 看内容、给用户"把这一堆文件压成一个"的能力，都能复用这三个工具。

**D（busybox）留作"可选组件"**：正好落在之前讨论的实验性功能下载组件的框架里，但它只解决"shell 里没有通用工具"，而 Office 生成走 A 就够了。1.1 MB 换 sed/awk/find 对模型很划算，建议放到 1.0.15 的组件商店，不塞进本版。

## 需要注意

- 原生工具跑在应用 uid 下，访问 `/sdcard` 依赖已声明的 All files access；若某机型未授权，需回落到 shell 通道。
- store 模式（level 0）对 xlsx/docx/pptx 够用且更快、更省电（云电脑/手机都是低功耗场景），默认给 deflate(6) 但允许模型指定 0。
- 工具注册后要同步三处：`ToolRegistry.defaults()`、`docs/tools.json`、系统提示词里的工具清单。
- `app/build.gradle.kts` 第 53–54 行有重复的 `implementation(libs.androidx.webkit)`，顺手清掉。
</content>
