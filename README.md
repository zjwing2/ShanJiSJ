# 闪记SJ（ShanJi SJ）

一个本地优先的 Markdown 笔记 App（Android），在 [MoeMemosAndroid](https://github.com/mudkipme/MoeMemosAndroid) 基础上改造。

所有笔记以 `.md` 文件落在你自己选的文件夹里（SAF 双向镜像），不依赖任何服务器也能用。

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

当前 `versionName 2.0.20` / `versionCode 64`（见 `app/build.gradle`）。

## 许可证

本项目继承上游许可：**GNU General Public License v3.0**（见 `LICENSE`）。
上游版权归 MoeMemosAndroid 作者所有，本仓库仅在其上做改造。
