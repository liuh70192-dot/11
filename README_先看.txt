Turrit 任务助手 / MuMu 安卓 APK 源码 v0.1 beta
==============================================

一、这是什么
- 适配：MuMu 1080×1920、480 DPI、Turrit（Android 包名 org.telegram.group）。
- 技术：原生 Java 安卓应用 + AccessibilityService 无障碍 + 前台调度服务。
- 不是 Telegram API/Telethon；不需要 API ID、Bot Token、VPS。
- 保留 TGManager 的任务管理思路，但与 Windows 图像识别代码不兼容，已重新实现。
- 当前提供完整 Android Studio 工程和 GitHub Actions 在线构建脚本；本交付环境
  缺少 Android SDK / Gradle 且无法访问构建仓库，因此无法在本地生成或实机验证 APK。
  这是未经 MuMu + Turrit 实机兼容性测试的 beta 源码，请勿把它当成已验证可用的安装包。

二、已完成的功能
1. 5 栏简洁中文 UI：总览 / 账号 / 消息库 / 任务 / 日志。
2. Turrit 自动进入“我的”、展开账号列表并上下滑动收集候选账号名；
   如无法读取可手动添加账号；已保存账号可备注、启停和“测试（不发送）”。
3. UTF-8 TXT 导入（一行一条、去空行、去重复、最多两万条），全账号
   共用消息池，乱序抽取，当前轮用尽再洗牌。最多 4096 字/条。
4. 点击开始后按每个账号的自定义次数，在未来 24 小时的等间隔时间片
   内随机安排具体执行时刻；每次固定 1 条、2 条或随机 1～2 条。
5. Turrit 账号切换、tg://resolve?domain=... 定向打开机器人聊天、
   校验聊天顶部名称关键词、清除草稿、填写并点击发送、检查输入框是否清空。
6. 失败跳过，无自动重发；日志最多保留 100 条；通知栏显示运行状态及“停止”。
7. 手动点开始才运行。模拟器退出即不再执行。系统不会在设备重启时自动恢复任务。

三、重要说明（必须先读）
- 当前使用无障碍节点（文本、可点击性、编辑框）+ 少量比例坐标来操作 Turrit。
  Telegram/Turrit 自绘界面可能无法暴露完整的无障碍节点；版本更新、
  键盘改变、字体缩放也可能使点击失效。首次务必先用测试模式，不要直接发送。
- 目标机器人必须配置两个信息：@机器人用户名、聊天顶部显示名称关键词。
  缺任意一项不启动。聊天顶部无法确认就立即跳过，以免错发。
- 输入框清空只证明发送点击被客户端接受，不代表消息一定被服务端送达。
- 无障碍服务可以读取应用界面文字，请仅在可信自用环境开启，并妥善保管模拟器。
- 这套程序不是防封工具，使用时应遵守 Telegram 服务条款与接收方规则。
- 如果手机/模拟器被系统休眠、权限被收回、网络断开，任务可能失败。
- 运行过程中请不要手动操作 Turrit，以免干扰账号切换与发送。

四、最简单打包 APK：GitHub Actions（无需在本地装 Android Studio）
1. 新建一个自己的 GitHub 仓库（可以设置为 Private）。
2. 把 TurritMuMuAPK 文件夹内的所有文件上传到仓库根目录，包括隐藏目录
   .github/workflows/build-apk.yml（Windows 解压时务必保留 .github）。
3. 进入仓库 Actions → Build Turrit MuMu debug APK → Run workflow。
4. 构建成功后，在该次 Actions 运行页面底部 Artifacts 下载
   TurritMuMu-debug-apk，解压得到 app-debug.apk。
5. 将 app-debug.apk 拖进 MuMu 安装即可。
※ 在线构建依赖 GitHub 官方 runner 下载 Android SDK/Gradle；需要网络。

五、电脑已经有 Android Studio 时
- Android Studio → Open → 选择本文件夹。
- 等待 Gradle sync 完成 → Build → Build Bundle(s) / APK(s) → Build APK(s)。
- 输出在 app/build/outputs/apk/debug/app-debug.apk。
- 或通过已配置 Android SDK 和 Gradle 8.9 的终端运行：gradle :app:assembleDebug。

六、首次使用顺序
1. 在 MuMu 里安装 Turrit（先正常登录好账号），再安装助手 APK。
2. 打开助手 → 总览 → 开启无障碍；在系统设置里启用“Turrit任务助手”。
3. 账号 → 识别 Turrit 账号；扫描会自动切换到 Turrit。返回助手核对列表，
   误识别项可删除；如自动识别失败，可尝试手动填写真实显示名称。
4. 消息库 → 导入 TXT（每行一条）；TXT 应为 UTF-8。
5. 任务 → 填写 @机器人用户名、聊天顶部名称关键词、每日次数、每次条数并保存。
6. 账号 → 选择一个账号点“测试”（只打开机器人并验证输入框，不发送）。
7. 测试成功再到总览 → 开始任务；可在通知栏或总览停止。
8. 修改任务数量/账号勾选后，先停止再重新开始，新计划才会更新。

七、已知限制
- 无法在当前环境实机验证 MuMu/Turrit 的账号滚动、按钮命中与编辑框结构。
- 第三方客户端如不公开无障碍文字，自动识别可能失败；此时手动添加只
  能保存名称，实际切换仍要求 Turrit 列表文本可被无障碍识别。
- 不支持图片、视频、贴纸、自动验证码、短信登录、机器人按钮自动点击。
- 「立即测试」是无发送检查；不提供错误重试，符合用户所选模式。
- 没有开机自启，前台调度服务被系统终止后，需手动重新开始。

包名：com.example.turritrunner
最低 Android API：26（Android 8.0）
构建工具：JDK 17、Gradle 8.9、Android Gradle Plugin 8.7.3、SDK 35

