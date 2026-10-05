# 家庭药箱

一款离线优先的 Android 家庭药箱应用，用于记录药品批次、包装有效期和库存，整理临期与补货提醒。内置商品条码资料可离线查询；可选共享服务和第三方条码 API 只用于补充包装身份信息。

> 本项目以 **Vibe Coding** 方式协作开发：维护者提出需求并确认关键行为，AI 辅助方案整理、代码实现、调试和审查。AI 生成的内容可能有错误；以源码、自动化检查和实际设备表现为准，欢迎提交问题和改进建议。

## 功能

- 扫码或手动录入药品，可按批次记录 `YYYY-MM` 或 `YYYY-MM-DD` 有效期和包装数量。
- 显示临期、过期和库存不足项目，提供药箱排序、补货清单和本机通知。
- 条码资料在设备本机查找。联网查询由用户自行配置认证；查询结果仍需按包装核对。
- 可选共享条码身份资料。药箱库存、批次有效期、位置和 API 认证保存在本机，不上传到共享目录。
- 通过 Android 系统文件选择器手动备份和恢复药箱。
- 在设置中手动检查 GitHub 正式版本，有新版本时打开发布页面，由用户选择下载和安装。

应用用于家庭记录，不提供诊断、剂量建议、药品真伪判断或购药服务。条码命中不代表官方核验结果。

## 构建

需要 JDK 17、Android SDK Platform 35 和 Android Build Tools 35.0.0。仓库包含 Gradle Wrapper。

Android 本地构建与检查：

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug lintPreview lintRelease assembleRelease -PsplitApks=true
```

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug lintPreview lintRelease assembleRelease -PsplitApks=true
```

Cloudflare Worker 的本地测试：

```bash
cd cloudflare
pnpm install --frozen-lockfile
pnpm test
```

### 安装与检查更新

正式版从 `1.0.0`（Android `versionCode 13`）开始。常见的 64 位 Android 手机可选择 `arm64-v8a.apk`；不确定架构时选择体积较大的 `universal.apk`。两个安装包功能和内置资料相同。

“设置 → 检查更新”读取本仓库最新正式 Release。版本按数字逐段比较，只有正式版本和完整安装包均可用时才提示更新；没有发布记录、请求限流或网络失败会分别说明。点击“查看新版本”打开固定仓库的发布页面，不自动下载或安装。

更新检查仅向 GitHub 公开接口发送一次无认证请求，不提交药箱记录、API 认证或共享访客令牌，也不计入条码 API 次数。GitHub 在部分网络环境下可能无法连接，可稍后重试或直接访问仓库 Releases。

## 资料来源与许可证

客户端内置条码资料根据 [EricLiuCN/barcode](https://github.com/EricLiuCN/barcode) 项目的 2023-09-07 数据快照整理。感谢上游项目维护者与贡献者收集、整理并分享资料。详见 [资料说明](CATALOG.md)。

本项目应用代码采用 Apache-2.0 许可证，许可文本见 [LICENSE](LICENSE)。条码资料来源、日期和范围见 [资料说明](CATALOG.md)。

## 隐私与安全

内置条码资料基于 2023 年 9 月的数据快照，无法覆盖之后上市的产品和包装变化。为减少重复查询，应用连接由开发者维护的 Cloudflare Workers + D1 共享条码目录；它复用已收录资料，并逐步补充新的包装信息，作为内置资料之外的查询来源。

共享目录在服务可用的版本中默认开启，可在“设置 → 共享条码资料 → 使用共享资料”关闭。启用时，客户端会先查内置资料和共享目录；两者都未收录时才调用已启用的补充 API。API 返回的基础资料会自动缓存并加入共享目录；用户核对包装后手动补充的条码资料，也会在保存时提交，供其他用户查询。提交字段限于条码、名称、规格、包装单位、厂家、批准文号和来源标记，D1 同时保存系统生成的去重摘要和收录时间。资料仍须按实际包装核对，来源标记不代表官方审核或鉴定。

应用没有账号注册、广告追踪或用户行为分析功能，也不会把用户身份资料或私人药箱记录上传到共享目录：库存、批次、有效期、位置、备注和第三方 API 认证都留在手机。API 补充查询会把当前条码发给用户启用的对应服务；认证保存在本机加密区，不发送给 D1。服务为授权查询保存随机访客令牌的哈希和创建时间，并维护日限额计数，不保存姓名、手机号或账号资料。Worker 使用 Cloudflare 提供的连接 IP 作边缘限流，不写入 D1 或应用日志；Cloudflare 网络服务在处理连接时可能接触必要的网络元数据。

关闭共享后会停止新的共享目录查询和上传；尚未发送的条码资料留在本机，重新开启后可能继续补传。客户端、Worker 和数据库结构均公开，可查看 [共享客户端](app/src/main/java/app/medicinecabinet/data/ServerCacheClient.kt)、[设置界面](app/src/main/java/app/medicinecabinet/ui/components/ServerCacheSettingsCard.kt)、[Worker](cloudflare/src/index.js) 和 [D1 表结构](cloudflare/schema.sql)。

## 致谢

感谢 [MXNZP 商品条码查询服务](https://www.mxnzp.com/doc/detail?id=6) 和 [万维易源（阿里云云市场）](https://market.aliyun.com/detail/cmapi011032) 提供条码查询服务，帮助应用补充内置资料未收录的信息。第三方服务的额度、有效期和费用以服务商说明为准。欢迎推荐其他条码 API、提供可共享的资料来源或提交包装信息勘误，一起完善条码资料库。

## 反馈与贡献

请使用 GitHub Issues 描述复现步骤、设备 Android 版本和预期结果。提交代码前请阅读 [贡献说明](CONTRIBUTING.md)。请勿公开上传真实凭据、备份文件或可识别个人的药箱截图。
