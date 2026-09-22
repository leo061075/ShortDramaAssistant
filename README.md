# 短剧助手 1.0

短剧助手 1.0 是一个专注于 **短剧广告识别与自动上滑** 的 Android 工具。

应用启动后直接进入跳广告页面，不包含其他模式。运行后会持续识别短剧页面，在广告播放结束并检测到“上滑继续观看短剧”等提示时，自动执行上滑手势进入下一集。

## 1.0 功能

- 广告页面识别
- “上滑继续观看短剧”提示识别
- 底部提示区域专项 OCR
- 广告结束后自动上滑
- 上滑结果确认
- 偶发滑动无效时最多补滑一次
- 防止同一广告连续上滑两次
- 当前集数识别
- 页面状态显示
- OCR / 上滑诊断信息
- 可拖动悬浮停止按钮
- OCR 截图超时自动恢复

## 使用要求

- Android 10（API 29）及以上
- 需要开启悬浮窗权限
- 需要开启“短剧助手”的无障碍服务

## 使用方法

1. 打开短剧助手。
2. 点击“悬浮窗”，完成悬浮窗授权。
3. 点击“自动识别”，在系统无障碍设置中开启短剧助手。
4. 返回应用，点击“开始跳广告”。
5. 切换到短剧播放页面正常观看。
6. 应用会在后台识别页面。
7. 广告结束出现继续观看提示后，应用会自动上滑。
8. 需要停止时，点击悬浮窗中的“停止”。

## 工作流程

```text
短剧播放
   ↓
识别页面文字
   ↓
发现广告相关内容
   ↓
等待广告播放完成
   ↓
底部专项 OCR 检测继续观看提示
   ↓
执行上滑手势
   ↓
确认提示是否消失 / 页面是否切换
   ├─ 已切换 → 等待下一次广告
   └─ 未切换 → 最多补滑一次
```

## 识别规则

### 正常短剧页

当页面同时出现以下特征时，优先按正常短剧页面处理：

- `第XX集`
- `短剧`
- `XX集全`

正常短剧页优先级高于广告辅助关键词，以降低误判。

### 广告相关特征

当前主要关注：

- `X秒后可解锁付费集`
- `免费广告`
- `点击进入直播间`
- `退出短剧`

辅助广告关键词不会单独作为唯一依据。

### 广告结束提示

重点检测：

- `上滑继续观看短剧`

同时兼容 OCR 可能出现的缺字、空格和换行情况。

## 自动上滑保护

为避免一次跳过两集，1.0 版本包含以下保护：

- 同一条继续观看提示只允许触发一次主上滑。
- Android 返回“手势完成”不会直接视为页面切换成功。
- 继续通过底部 OCR 检查提示是否消失。
- 如果提示仍持续存在，允许最多一次补滑。
- 页面稳定切换后才重新允许下一次广告触发。

## OCR 截图恢复

部分 Android 设备上，无障碍截图 API 偶尔可能长时间不回调。

1.0 版本加入截图 watchdog：

- 截图请求超时后自动释放锁。
- 旧截图回调会被丢弃。
- 后续 OCR 可以继续工作。
- 诊断区会显示当前 OCR 状态。

## 页面说明

主页面只保留跳广告相关内容：

- 识别广告
- 等待播放完
- 自动上滑
- 当前集
- 页面状态
- 运行方式
- 悬浮窗权限
- 自动识别权限
- 开始跳广告
- 识别诊断

应用启动后直接进入该页面。

## 悬浮窗

悬浮窗仅用于：

- 显示运行中的控制入口
- 拖动位置
- 停止跳广告模式

## 项目结构

```text
app/src/main/java/…/
├── MainActivity.kt
├── access/
│   └── DramaAccessibilityService.kt
├── ocr/
│   └── OcrMonitorEngine.kt
├── overlay/
│   └── FloatingControlService.kt
└── state/
    ├── AdStateCoordinator.kt
    ├── ContinuePromptDetector.kt
    ├── PlaybackSkipSession.kt
    ├── SkipRuntimeState.kt
    └── SwipeDiagnostics.kt
```


## 技术栈

- Kotlin
- Android AccessibilityService
- Android GestureDescription
- AccessibilityService.takeScreenshot
- ML Kit Chinese Text Recognition
- Android Overlay Window

## 构建

使用 Android Studio 打开项目根目录，完成 Gradle Sync 后执行：

```text
Build → Rebuild Project
```

主要环境：

- compileSdk 35
- targetSdk 35
- minSdk 29
- Java 17
- ML Kit Chinese Text Recognition 16.0.1

## 版本

**当前版本：1.0**
