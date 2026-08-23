# 安卓弹窗答题（手写听写守护）

纯原生 Kotlin 安卓项目，通过 WebView 承载前端手写听写模块，实现：

- **完全隐身**：无桌面图标、无 LAUNCHER、不进最近任务列表，仅由无障碍服务自启
- **多层保活**：双进程互拉 + 媒体前台服务 + 60 秒定时自检 + 4 权限循环引导
- **全界面悬浮听写弹窗**：方案1 系统悬浮窗 + 方案2 无障碍兜底绘制，答错锁死、答对关闭并重置计时
- **本地题库与错题本**：Room 数据库永久存储，加权出题（错题10 / 新词5 / 已对1）
- **云端自动打包**：推送后 GitHub Actions 自动生成未签名 APK

## 目录说明

- `app/` —— 安卓工程主模块（Kotlin + WebView + Room + 双进程服务）
- `app/src/main/assets/` —— 前端复用模块（writing-core.js 识别算法、android-bridge.js 适配壳、index.html）
- `.github/workflows/build.yml` —— CI 自动编译配置
- `上传与编译教程.txt` —— GitHub 桌面端上传与下载 APK 步骤
- `代码自检校验报告.txt` —— 三轮代码自检与缺陷修复记录
- `文字模块接入指南.md` —— 前端模块复用规范（手写识别 / 弹窗 / 判定阈值）

## 使用

1. 用 GitHub 桌面端将本仓库推送至 GitHub
2. 在 GitHub Actions 查看编译进度，下载 `app-release-unsigned.apk`
3. 手机安装后开启「无障碍服务」即可自启，亮屏累计达标后弹出手写听写弹窗
