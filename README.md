# 极速高德经纬度水印相机 v9.0.0 (Android 原生双模版 + 微信小程序版 + Web PWA 版)

本项目是专为「**秒开秒拍、光学拍照/极速抓拍双模切换、NTP 授时 + SHA-256 外业防伪验真、批量相册加水印、类 iOS 分层网格相册、高德 GCJ-02 零偏差定位码、非防篡改照片全要素无损改水印**」打造的全端工程。

## v9.0.0 重大架构重构与全端深度修复

1. **🧹 做减法：彻底移除滤镜 + 模板 0 底部暗影默认关闭（双端同步）**
   - 彻底清理 Android 与微信小程序全部滤镜配置、矩阵渲染、`RenderEffect` 与 CSS `filter`（恢复小程序 `<camera>` 同层渲染性能），将主底栏黄金交互位升级为**「批量水印」**一级入口。
   - 核实用户反馈的“暗角”源自模板 0 硬编码的底部 390px 纯黑渐变遮罩，现已重构为可选开关 `showBottomScrim`（**默认关闭**），仅保留清晰文字投影，预览与成片 100% 一致。

2. **⚡ 新增「批量相册加水印」防 OOM 串行流水线（双端同步）**
   - **Android 端**：接入 `PickMultipleVisualMedia(50)` 与相册长按多选，采用 `Dispatchers.IO.limitedParallelism(1)` 串行队列 + 两步 `inSampleSize` 采样（`<= 2560px`）+ 即时 `recycle()` 锁死峰值内存；支持原片 EXIF 时间/GPS 独立读取与 100m 网格逆地理缓存、实时进度与取消。
   - **微信小程序端**：拆出独立分包 `packageBatch` 自动卸载首页相机释放显存；新增纯 JS 64KB 头部解析器 `utils/exif_lite.js`；基于 `OffscreenCanvas` 串行合成并直存系统相册（不占 `USER_DATA_PATH` 配额），逐张释放纹理并支持单张容错与一键重试。

3. **🖼️ 重构类 iOS 分层相册 UI（遵循双端平台权限最优解）**
   - **Android 端**（`ui/GalleryScreen.kt`）：默认 4 列按日吸顶网格（水印相册 / 系统相册双 Tab，自动 EXIF 旋转纠偏 + 懒读元数据 + 长按多选批量操作），点击进入全屏 `HorizontalPager` 滑切 + 双指缩放详情页，底部 4 键栏补齐系统 ShareSheet 一键分享与 Material 3 `ModalBottomSheet` 详情抽屉。
   - **微信小程序端**（`packageAlbum` 双页分包 + `utils/photo_store.js`）：告别单页巨型弹窗与 `<page-container>` 闪黑冲突；采用**双轨分级存储**（240px 缩略图 + 120 张水印成品 / 25 张无损底图 + 冷启动孤儿文件 GC），顶部融合「从系统相册批量加水印 / 选图验真」双入口，3 列网格秒开，独立详情页支持 `<swiper>` 滑切与半屏验真抽屉。

4. **🛡 P0 安全、稳定性与合规缺陷全量根治**
   - **消除防伪漏洞**：彻底删除小程序验真降级假阳性分支；移除 Android 空备注回退绕过；严格校验 `sig.length >= 10`；双端 QR 码升级至 ISO/IEC 18004 V14（8 掩码择优）彻底杜绝超长中文截断签名；防伪模式下严禁未定位默认坐标签发哈希并同时锁定地点/备注；Android 端开启 R8 混淆与 64 位 dHash 图像感知指纹绑定。
   - **修复崩溃、泄漏与合规**：修复 Android 外部导图 OOM、`FastLocationManager` Activity/协程泄漏、Android 14 `READ_MEDIA_VISUAL_USER_SELECTED` 权限、同秒文件名碰撞与 EXIF 海拔/方向缺失；修复小程序 `VKSession` 异常未销毁泄漏、iOS 4096px 纹理上限、隐私合规 `utils/privacy.js`、线上 NTP 授时降级与 `onShareAppMessage` 错发旧图。

5. **🏗️ 工程化模块化拆分**
   - **Android 端**：将 3,700 行 `MainActivity.kt` 拆分为 `ui/{GalleryScreen,BatchWatermarkUi,WatermarkEditorSheet,CameraDialogs,VectorIcons}.kt`，隔离高频定位重组、恢复全画面点按对焦并支持工程台账 CSV 导出。
   - **微信小程序端**：将 5,595 行单页巨石拆为 `packageAlbum`、`packageBatch` 双分包、`components/watermark-overlay/` 预览组件及 `utils/{watermark_renderer,photo_store,exif_lite,privacy}.js`。

## 目录结构

- `app/`：Android 原生工程 v9.0.0（Kotlin + Jetpack Compose + CameraX + ExifInterface）
- `wechat-miniprogram/`：零依赖原生微信小程序工程 v9.0.0（直接导入**微信开发者工具**即可真机运行）
- `web-pwa/`：单文件离线 Web PWA 预览版

## 历史版本

- `v8.0.0`：三级无侵入识图验真、防篡改硬锁定 vs 非防篡改无损重绘、双端三坐标系对齐。
