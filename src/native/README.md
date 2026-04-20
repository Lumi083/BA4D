# Android Mimosa in src/native

## 技术栈选择

- 语言: Kotlin
- 运行位置: Android 前台服务进程内
- WS Server: `org.java-websocket:Java-WebSocket`
- Shell 权限桥: `Shizuku API`
- 协议: Mimosa 鼠标子协议兼容
  - 订阅: `[0xA0, 0x01, fps]`
  - 广播: `[0x01][x:int32 LE][y:int32 LE][wheel:float LE][btn:uint8]`
- 端口: `42891`

## 当前实现

- 文件: `src/native/com/potdroid/overlay/nativews/AndroidMimosaServer.kt`
- 文件: `src/native/com/potdroid/overlay/shizuku/ShizukuMimosaCollector.kt`
- 功能:
  - 启动本地 `ws://127.0.0.1:42891`
  - 接收客户端鼠标订阅
  - 通过 Shizuku 启动 `getevent -lt` shell 进程
  - 后台抓取触控输入并发布坐标与按压状态
  - 30 秒订阅过期清理

## 为什么不用 Python mimosa.py 直接移植

`mimosa.py` 依赖桌面输入库（pynput/keyboard/inputs）和桌面权限模型，Android 上不成立。这里保留 Mimosa 的协议外形，替换为 Android 原生触控数据源。
