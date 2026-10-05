# Cloudflare D1 条码共享 Worker

该目录包含可选的条码身份资料查询与追加 Worker。客户端只发送条码、名称、规格、包装单位、厂家、批准文号和来源声明；不会上传个人库存、有效期、批号、位置或第三方 API 认证。

## 本地测试

```bash
pnpm install --frozen-lockfile
pnpm test
```

测试使用 Wrangler/Miniflare 和本地模拟 D1，不访问 Cloudflare 账号或生产服务。

## 自行部署

1. 按 Cloudflare 官方文档创建自己的 D1 数据库及 Workers 限流绑定。
2. 复制 `wrangler.example.jsonc` 为本机的 `wrangler.jsonc`，替换数据库名称、ID、限流空间和自有域名。个人配置文件已列入忽略规则。
3. 按 `schema.sql` 与 Cloudflare 文档检查并初始化你自己的数据库。
4. 使用官方 Wrangler 本机授权，在本机手动部署。仓库没有自动部署流程，不需要在 GitHub Actions 中提供 Cloudflare Token。

客户端发布版本使用的默认共享服务地址写在 Android 构建配置中。此仓库不提供或承诺第三方实例；部署自己的服务需要自行配置客户端构建参数并验证手机网络可达性。

匿名访客令牌仅限查询和追加，不能管理数据库；来源字段只是调用者声明。公开接口仍可能被滥用，应在自有 Cloudflare 账号内设置限流、额度和告警。
