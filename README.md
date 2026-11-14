# Wifi Look

一款基于 **纯 Kotlin + Android 原生 SDK** 开发的极简 Wi-Fi 无线可视设备的客户端。

## 主要特性

- **极简连接页（`ConnectActivity`）**
  - 展示当前设备连接状态、热点名称（SSID）、设备 IP 与设备电量。
- **即开即用、不驻留后台**
  - 退出相机页面时自动发送关闭灯光指令并立即关闭视频流 socket，避免设备持续亮灯或推流耗电。
  - 在主界面连续按两次返回键即可彻底退出应用，自动注销 Wi-Fi 请求并释放进程，不在后台保持长连接。

## 项目结构

```text
Wifi Look/
├── wifi-look.apk                     # 已签名正式版安装包
├── build.gradle / settings.gradle    # Gradle 构建配置
└── app/
    ├── build.gradle                  # 包名 com.wifi.wifilook，minSdk 24 / targetSdk 33 / compileSdk 35
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/wifi/wifilook/
        │   ├── ui/
        │   │   ├── ConnectActivity.kt    # 首页：设备信息卡片、一键连接、双击返回退出
        │   │   └── CameraActivity.kt     # 相机页：视频流渲染、灯光/镜像/旋转、拍照录像
        │   ├── net/
        │   │   ├── AutoConnector.kt      # BLE 发现与 Wi-Fi 热点自动连接/释放
        │   │   └── DeviceProtocol.kt     # UDP 视频流探测、帧重组、YUYV 解码、电量与灯光控制
        │   └── media/
        │       ├── VideoRecorder.kt      # 原生 MediaCodec 硬编码录像与 MediaStore 相册保存
        │       └── RotationFilter.kt     # 加速度计角度平滑滤波
        └── res/                          # 纯 XML 矢量图标与极简暗色主题布局
```

## 权限说明

应用仅申请本地硬件连接与低版本相册写入所需的最小权限，不收集或上传任何隐私数据：

- **网络与 Wi-Fi**：仅用于局域网连接设备热点与 UDP 音视频/控制通信。
- **蓝牙与位置**：仅用于 BLE 搜索附近耳勺设备及获取当前 Wi-Fi SSID。
- **存储**：写入相册权限。


- **直接安装**：下载仓库根目录的 [wifi-look.apk](./wifi-look.apk) 安装到 Android 7.0（API 24）及以上设备即可使用。
