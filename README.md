# 闪记SJ（ShanJi SJ）

一个本地优先的 Markdown 笔记 App（Android），在 [MoeMemosAndroid](https://github.com/mudkipme/MoeMemosAndroid) 基础上改造。

所有笔记以 `.md` 文件落在你自己选的文件夹里（SAF 双向镜像），不依赖任何服务器也能用。

## 下载

- **最新 APK（v2.1.0，debug 版，约 57 MB）**：[ShanJiSJ-v2.1.0-debug.apk](https://github.com/zjwing2/ShanJiSJ/releases/download/v2.1.0/ShanJiSJ-v2.1.0-debug.apk)
- 发布页（含历史版本与说明）：<https://github.com/zjwing2/ShanJiSJ/releases/tag/v2.1.0>

> 安装提示：Android 需在「设置 → 安全 → 安装未知应用」中，对用来打开 APK 的浏览器 / 文件管理器授权，方可安装。

## 与上游的主要差别

- **Markdown 文件夹镜像**：笔记以 `.md` 落盘（YAML front matter + 正文），应用与文件夹双向同步，两边改动都能看到。
- **链接读取正文**：撰写页粘贴网址后点「读取正文」，抓取正文转 Markdown 追加到笔记；被站点拦截时降级保存标题 + 摘要，链接始终保留。
- **灵感卡片只露标题**：列表卡片标题最多 3 行；标题层级统一，卡片五级、内页四级。
- **长文详情页渐进渲染**：首屏只渲染前 24 行，底部「继续展开」分批加载；图片进入视口才发请求，加载前是固定高度占位——抓下来的长文（几百行、几十张图）打开不再掉帧。
- **标签防污染**：正文里 URL 的 `#` 片段不会被误判成标签。
- **界面中文化**：沿用上游多语言结构，补齐中文文案。

## 构建

```bash
bash build.sh assembleDebug     # 出 APK
bash build.sh testDebugUnitTest # 跑单元测试
```

需要 JDK 17+ 与 Android SDK（Gradle 会自行下载依赖）。

## 版本

当前 `versionName 2.1.0` / `versionCode 20100`（见 `app/build.gradle`）。

版本号只手工维护 `versionName` 一处，`versionCode` 由它推算：

```
versionCode = major * 10000 + minor * 100 + patch
```

即 2.1.0 → 20100、2.2.0 → 20200、3.0.0 → 30000，两者永远不会不同步。

## 发布流程

版本号与更新日志由 [release-please](https://github.com/googleapis/release-please) 自动维护，
它依据[约定式提交](https://www.conventionalcommits.org/zh-hans/)从提交信息推断该升哪一位：

| 提交信息 | 含义 | 版本变化 |
| --- | --- | --- |
| `feat: …` | 向下兼容的新功能 | 2.1.0 → 2.2.0 |
| `fix: …` | 向下兼容的问题修复 | 2.2.0 → 2.2.1 |
| `feat!: …` 或正文含 `BREAKING CHANGE:` | 不兼容的破坏性改动 | 2.2.1 → 3.0.0 |
| `chore: …` / `docs: …` / `ci: …` / `test: …` | 无用户可见变化 | 不发版 |

日常流程：

1. 正常提交（提交信息带上前缀）并推送到 `main`。
2. 机器人自动开一个 **Release PR**，里面已经改好版本号、写好本次更新日志；版本不合适可以直接在 PR 里改。
3. 检查无误后 **Merge 这个 PR** —— 此刻才真正打 tag（如 `v2.2.0`）、建 Release。
4. 紧接着 `.github/workflows/release-please.yml` 会自动构建 debug APK 并挂到该 Release 下，无需手工上传。

> 注意：不要手工修改 `app/build.gradle` 里的版本号与 `CHANGELOG.md`，它们现在由机器人接管。
> 需要发预览版时，手工打 `v2.2.0-beta.1` 这类 tag 并在 Release 上勾选 "Set as a pre-release" 即可。

## 许可证

本项目继承上游许可：**GNU General Public License v3.0**（见 `LICENSE`）。
上游版权归 MoeMemosAndroid 作者所有，本仓库仅在其上做改造。
