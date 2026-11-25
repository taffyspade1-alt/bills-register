# 安心记账

一个**完全离线**的手机记账应用。账单截图在手机本地做文字识别，账目只存在本机，App 没有联网能力。

<img src="应用图标预览.png" width="180" alt="应用图标">

## 为什么是离线

市面上记账 App 的常见做法是把账单截图传到云端做 OCR。这意味着你的消费流水会离开设备，落到某家公司的服务器上。

这个项目的做法相反：

- **Manifest 里没有 `INTERNET` 权限**，并且用 `tools:node="remove"` 强制移除任何第三方库合并进来的网络权限
- 文字识别用 ML Kit 的 **bundled 中文模型**，模型随 APK 打包，识别全程在设备本地完成，不依赖 Google Play 服务
- 截图只在内存里出现，确认入账后立即释放
- 备份文件通过系统文件选择器由用户自己选位置，App 不上传任何内容

代价是 APK 大了约 4MB，换取的是"数据从未离开这台手机"这个可以验证的保证。

## 功能

| 功能 | 说明 |
|---|---|
| 截图识别 | 一次选多张账单截图，端侧 OCR 提取文字，按视觉行回传后解析金额、收支方向、商户名、日期 |
| 逐笔确认 | 识别结果先列出来供核对，不对的单笔可删除重填，确认后才计入总账 |
| 手动记账 | 不截图也能记，适合银行 App 禁止截屏的场景 |
| 收支总览 | 总收入、总支出、结余，明细列表随时回看修改 |
| 使用说明 | 内置 11 章 40 步图文教程，教你怎么在各家 App 里找到账单页 |
| 字号调节 | 三档字号切换，适配老花眼用户 |
| 备份恢复 | 导出明文 JSON 到自选位置；新设备恢复时可选「合并」或「只留备份」 |

## 技术实现

```
jizhang-app/
├── app.html                  # 应用本体，单文件（HTML + CSS + JS），489 行
└── android/                  # Android 原生工程
    ├── app/src/main/
    │   ├── assets/index.html                      # app.html 的副本，APK 实际加载的就是它
    │   ├── java/com/taffyspade1/ledger/MainActivity.java  # WebView 壳 + 原生桥接（383 行）
    │   ├── AndroidManifest.xml                    # 权限清单
    │   └── res/                                   # 五档图标 + 自适应图标
    ├── build.gradle
    └── gradle/wrapper/
```

**没有前端框架，没有打包工具。** 整个界面是一个 58KB 的 HTML 文件，样式和脚本全部内联，用 `localStorage` 存数据。这样做的理由很实际：这个应用只有一个页面和四个视图，引入 Vue 或 React 只会让构建链变复杂，而 WebView 里首屏反而更慢。

原生层只做三件事，都通过 `@JavascriptInterface` 暴露给页面：

1. **批量选图**：调系统文件选择器，避开存储权限
2. **端侧 OCR**：把截图交给 ML Kit，按视觉行返回文本
3. **备份文件读写**：走系统文件对话框，由用户指定位置

`androidx.webkit` 只用来把 assets 映射成本机 https 虚拟域，让 `localStorage` 有稳定的 origin，不发起任何网络请求。

## 构建

环境要求：

| 项 | 版本 |
|---|---|
| JDK | **17**（AGP 8.0+ 强制要求，JDK 8 不可用） |
| Android Gradle Plugin | 8.1.4 |
| Gradle | 8.2 |
| compileSdk / targetSdk | 34 |
| minSdk | 23（Android 6.0 及以上） |

### 方式一：Android Studio（推荐）

1. 安装 Android Studio 稳定版，它自带 JDK 与 Gradle
2. SDK Manager 里安装 `Android SDK Platform 34`、`Build-Tools 34`、`Platform-Tools`
3. 打开工程，**目录选到 `jizhang-app/android` 这一层**
4. 等 Gradle Sync 完成（首次会下载 AGP、Gradle、ML Kit 模型库）
5. `Build → Build Bundle(s) / APK(s) → Build APK(s)`

产物在 `app/build/outputs/apk/debug/app-debug.apk`，用自动生成的 debug 密钥签名，可直接安装自测。

### 方式二：命令行

```bash
cd jizhang-app/android

# local.properties 需自行创建，指向本机 SDK
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew assembleDebug
```

国内网络连不上 `google()` 时，在 `settings.gradle` 的 `repositories` 里换成可访问的 Maven 镜像。注意这只是**构建阶段**下载依赖，与 App 运行时不联网不冲突。

### 改了页面之后

`app.html` 和 `assets/index.html` 是两份文件，**改完必须同步覆盖**，否则重新编译后手机里看到的还是旧版：

```bash
cp jizhang-app/app.html jizhang-app/android/app/src/main/assets/index.html
```

## 数据备份

数据只存在手机里，卸载或换机就会丢失。

**导出**：明细页最下方 →「备份与恢复」→「① 导出备份文件」→ 自己选保存位置。

**恢复**：同一入口 →「② 从备份文件恢复」→ 选文件 →「合并」（保留现有账目、自动跳过重复）或「只留备份」（覆盖）。

备份文件是明文 JSON，不含密码、银行卡号、身份证号，但拿到文件的人能看到全部账目，别放群聊或公共网盘。

更换 `applicationId`（本次从 `com.example.jizhang` 改为 `com.taffyspade1.ledger`）时同样要走一遍导出 → 装新版 → 恢复，旧应用的数据目录不会自动迁移。

## 构建排错

| 现象 | 处理 |
|---|---|
| `Unsupported class file major version` | JDK 不是 17。Android Studio 里改 `Settings → Build Tools → Gradle → Gradle JDK` |
| Sync 一直卡在下载依赖 | 检查能否访问 `google()` 与 Maven Central，不通就配国内镜像 |
| 找不到 `gradle-wrapper.jar` | 用 Android Studio 打开工程会自动补齐；纯命令行需先执行一次 `gradle wrapper` |
| APK 装不上 | 确认是已签名的 debug 包、手机允许未知来源、Android ≥ 6.0 |
| 点「自动识别」没反应 | 确认装的是含 OCR 桥接的新包、Gradle Sync 成功且 ML Kit 依赖已下载；截图太糊也会识别失败 |
| 改包名后旧版账本不见了 | 正常，包名变了系统视为新应用。在旧版导出备份、新版恢复即可，别先卸旧版 |
| 改了 `app.html` 手机里没变化 | 忘记同步 `assets/index.html`，见上文「改了页面之后」 |

## 已知限制

- **OCR 不会百分之百准确**：商户名可能认错，日期默认取截图当天，所有识别结果都要人工核对。绝不建议换成云 OCR——那会破坏离线保证
- **银行 App 普遍禁止截屏**（系统 `FLAG_SECURE`），截出来是全黑，这是正常保护，请改用电子回单或手动记账
- **release 包未配置签名**：`build.gradle` 里没有 `signingConfig`，`assembleRelease` 产出的是未签名包，正式分发前需自行生成密钥库
- **包名已改为 `com.taffyspade1.ledger`**（`com.example` 是模板占位前缀，商店审核会拒）。注意 `applicationId` 一变，系统就视为全新应用，旧版账本不会自动带过来：先在旧版导出备份，装新版后恢复，确认无误再卸载旧版；因为包名变了，建议先 `Clean Project` 再重新 Build
- 只在 Android 上验证过，没有 iOS 版本

## 图标

原创设计，「深蓝底 + 白色账本 + 青绿 ¥」，不含任何第三方品牌元素。

替换方式：覆盖 `res/mipmap-*/ic_launcher.png` 五档 PNG，并同步修改 `res/drawable/ic_launcher_foreground.xml`；只换底色改 `res/values/ic_launcher_background.xml` 里的 `#185FA5`。
