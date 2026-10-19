# 极速高德经纬度水印相机 (Android 原生工程源码 + 免Key高德零偏差版)

本项目是专为「**秒开秒拍、双坐标系切换（默认高德 GCJ-02）、图层分离无损二次改水印、一键零偏差唤起高德地图**」打造的原生 Android 工程（Kotlin + Jetpack Compose + CameraX + ExifInterface）。

## 核心特性

1. **免开发者 Key 的高德零偏差对齐（WGS-84 ↔ GCJ-02 双向数学纠偏）**
   - 直接读取手机硬件级 GPS 原始 `WGS-84` 坐标，内置国测局克拉索夫斯基椭球高精度转换算法（`CoordinateConverter.kt`），正反算误差 `< 0.01 米`。
   - 默认在照片水印显示高德 `GCJ-02` 坐标（可在设置中随时切换为 `WGS-84`），调用高德 URI（`androidamap://viewMap?...&dev=0`）时严格传入 `GCJ-02`，保证无论一键跳转还是复制数字去高德搜索，落点丝毫不差。

2. **0ms 快门定格 + 异步寻优合成（`FastLocationManager.kt`）**
   - 按下快门瞬间立即定格相机画面（零快门延迟）。
   - 若按下快门时 GPS 精度尚未完全收敛（如误差 `> 10m`），后台自动继续高频采样 1.2 秒获取最优锁星坐标后再合成水印落盘。

3. **双轨 EXIF 防二次偏移 + 图层分离无损修改水印（`WatermarkRenderer.kt`）**
   - **图层分离存储**：内部同时保存高清纯净底图（`clean_<id>.jpg`）与结构化水印配置 JSON（`meta_<id>.json`）。后续在 App 相册内随时修改备注、地址、微调经纬度或切换 `GCJ-02 / WGS-84` 时，均基于纯净底图重新渲染高清成片，绝无重影或画质损失。
   - **双轨 EXIF 机制**：导出的 JPEG 标准 `GPSLatitude / GPSLongitude` 写入国际通用 `WGS-84`，同时在 `UserComment` 写入包含 `GCJ-02` 与 `WGS-84` 的双坐标 JSON；导入外部系统相册含 EXIF 的历史照片时，自动将标准 `WGS-84` 纠偏为 `GCJ-02` 后再跳转高德，彻底杜绝二次偏移。

## 如何编译 APK

### 方式一：使用 GitHub Actions 云端一键自动打包（无需本地安装 Android Studio）
1. 将本压缩包解压后上传到你的任意 GitHub 仓库（公开或私有均可）。
2. 仓库已内置 `.github/workflows/build-apk.yml`，推送代码后点击仓库顶部的 **Actions** 标签页，等待约 2 分钟即可直接下载编译好的 `app-debug.apk` 安装到手机。

### 方式二：使用 Android Studio 本地编译
1. 使用 Android Studio Ladybug / Koala 或更新版本打开本工程根目录。
2. 等待 Gradle 同步完成后，连接安卓手机点击 **Run 'app'** 或执行 `./gradlew assembleDebug`。
