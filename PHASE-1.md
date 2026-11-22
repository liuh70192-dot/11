# 第一阶段交付清单

| 项目 | 状态 | 说明 |
|---|---|---|
| Kotlin Android 工程 | 已建立 | 单 app 模块 |
| Jetpack Compose | 已建立 | Material 3 |
| 三个主页面 | 已建立 | 账号 / 资料修改 / 群组任务 |
| Turrit 安装应用扫描 | 已实现基础版 | 仅扫描启动器可见应用，不读取登录账号 |
| XLSX / ZIP 文件选择 | 已实现入口 | 尚未解析文件内容 |
| 模块目录 | 已建立 | account / profile / group / automation / database / ui |
| GitHub Actions | 已建立 | 构建 Debug APK 并上传 Artifact |
| 标准 Gradle Wrapper JAR | CI 首次运行生成 | 当前离线打包环境无法下载官方 wrapper JAR；Actions 会生成并单独上传 Wrapper Artifact |
| APK 构建验证 | 待 CI | 当前执行环境没有 Android SDK、Gradle，且无网络，不能声称本地构建成功 |

## 第二阶段入口

在 MuMu Android 12 与真实手机上确认 Turrit 的应用包名、应用分身命名、账号切换界面及无障碍节点结构，再实现登录账号识别。不能仅凭包名推断一个 Turrit 中的多个登录账号。
