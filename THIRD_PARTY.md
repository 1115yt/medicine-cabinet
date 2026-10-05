# 第三方组件与资料

本文记录当前源码树中已识别的第三方素材、数据来源和依赖入口。它不授予任何第三方资料的再分发权，也不替代发布前的许可证核查。

## 随应用打包的资料与素材

| 项目 | 用途 | 来源与许可状态 |
| --- | --- | --- |
| 商品/药品条码资料 | 离线包装身份查询，分片位于 `app/src/main/assets/medicine-catalog/` | 根据 [EricLiuCN/barcode](https://github.com/EricLiuCN/barcode) 的 2023-09-07 数据快照整理。感谢上游项目维护者与贡献者收集、整理并分享资料。详情见 [CATALOG.md](CATALOG.md)。 |
| Octicons `mark-github-16` | 设置页版本卡片中的 GitHub 图标，点击后打开 [维护者 GitHub 主页](https://github.com/1115yt) | 来源为 [Primer Octicons 图标文件](https://github.com/primer/octicons/blob/main/icons/mark-github-16.svg)，保留图标比例并转换为 Android 矢量资源。原 MIT 许可文本随应用保存在 `app/src/main/assets/licenses/octicons.txt`，仅覆盖 Octicons 素材，不覆盖本项目源码或条码资料。 |

## 构建依赖

Android 直接依赖及版本声明位于 `app/build.gradle.kts` 和根目录 `build.gradle.kts`；Cloudflare Worker 的 Wrangler 版本固定在 `cloudflare/package.json` 与锁文件中。依赖包本身不随本仓库打包，构建时由 Gradle、Maven 仓库和 pnpm 下载。

发布前应按实际解析到的直接与传递依赖版本核对许可证及必要的 NOTICE，尤其是 Google ML Kit 条码扫描组件。当前说明只列出依赖入口，不声称已经完成完整的传递依赖许可证清单。
