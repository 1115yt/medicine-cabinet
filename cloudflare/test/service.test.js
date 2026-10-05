import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { validateProduct, normalizeBarcode } from '../src/index.js';

// 使用当前 Wrangler 自带的官方 Workers/D1 本地运行时，绝不连接远端数据库。
const require = createRequire(import.meta.url);
const { Miniflare, convertV4MiniflareOptions } = createRequire(require.resolve('wrangler'))('miniflare');
const product = { barcode: '04006381333931', name: '测试条码资料', specification: '10片',
  packageUnit: '盒', manufacturer: '虚构测试厂家', approval: '', source: 'manual' };
let mf, db, token;
function options(overrides = {}) {
  return convertV4MiniflareOptions({ modules: true, scriptPath: fileURLToPath(new URL('../src/index.js', import.meta.url)),
    compatibilityDate: '2026-10-04', d1Databases: ['DB'],
    bindings: { ENROLLMENT_ENABLED: 'true', WRITES_ENABLED: 'true', DAILY_CLIENT_LIMIT: '200', DAILY_WRITE_LIMIT: '1000' },
    ratelimits: Object.fromEntries(['IP_RATE', 'ENROLL_RATE', 'WRITE_RATE'].map((name, i) =>
      [name, { namespace_id: String(913800 + i), simple: { limit: 1000, period: 60 } }])), ...overrides });
}
async function request(path, method = 'GET', data, auth = token, extraHeaders = {}) {
  return mf.dispatchFetch(`https://catalog.example${path}`, { method, headers: {
    ...(auth ? { Authorization: `Bearer ${auth}` } : {}),
    ...(data !== undefined ? { 'Content-Type': 'application/json' } : {}), ...extraHeaders
  }, ...(data !== undefined ? { body: typeof data === 'string' ? data : JSON.stringify(data) } : {}) });
}
before(async () => {
  mf = new Miniflare(options());
  db = await mf.getD1Database('DB');
  const schema = (await readFile(new URL('../schema.sql', import.meta.url), 'utf8')).replace(/^--.*$/gm, '').trim();
  // D1 exec 按行处理；建表和带 BEGIN/END 的触发器须作为完整语句提交。
  await db.batch(schema.split(/\n(?=CREATE|INSERT)/).map(sql => db.prepare(sql.trim())));
  const result = await request('/v1/clients', 'POST', undefined, null);
  assert.equal(result.status, 201);
  token = (await result.json()).token;
});
after(async () => { await mf?.dispose(); });

test('标准 GTIN 与字段限制，不接受私人药箱资料', () => {
  assert.equal(normalizeBarcode('4006381333931'), product.barcode);
  assert.equal(normalizeBarcode('4006381333932'), null);
  assert.equal(normalizeBarcode('https://example.com/trace'), null);
  for (const key of ['quantity', 'expiryDate', 'location', 'lotNumber', 'app_secret', 'token']) {
    assert.throws(() => validateProduct({ ...product, [key]: '私人值' }));
  }
  assert.throws(() => validateProduct({ ...product, name: 'a'.repeat(81) }));
  assert.throws(() => validateProduct({ ...product, manufacturer: 'bad\nfield' }));
  assert.throws(() => validateProduct({ ...product, source: 'unknown' }));
});
test('健康检查确认数据库初始化，仅返回公开状态', async () => {
  const result = await request('/health', 'GET', undefined, null);
  assert.equal(result.status, 200);
  assert.deepEqual(await result.json(), { status: 'ok', schema: 1 });
});
test('访客随机令牌只存摘要，未授权不能查写', async () => {
  assert.match(token, /^[A-Za-z0-9_-]{43}$/);
  const row = await db.prepare('SELECT token_hash FROM cabinet_clients').first();
  assert.match(row.token_hash, /^[a-f0-9]{64}$/);
  assert.notEqual(row.token_hash, token);
  assert.equal((await request(`/v1/catalog/${product.barcode}`, 'GET', undefined, null)).status, 401);
  assert.equal((await request('/v1/catalog/cache', 'POST', product, 'bad-token')).status, 401);
});
test('首次追加可由第二位访客查询；字段不含私人数据或凭据', async () => {
  const written = await request('/v1/catalog/cache', 'POST', product);
  assert.equal(written.status, 201);
  assert.equal((await written.json()).status, 'stored');
  const other = await request('/v1/clients', 'POST', undefined, null);
  const otherToken = (await other.json()).token;
  assert.notEqual(otherToken, token);
  const found = await request(`/v1/catalog/${product.barcode}`, 'GET', undefined, otherToken);
  assert.equal(found.status, 200);
  assert.deepEqual(await found.json(), { ...product, verified: false });
});
test('相同身份去重，服务来源不同也不会重复保存', async () => {
  const result = await request('/v1/catalog/cache', 'POST', { ...product, source: 'aliyun' });
  assert.equal(result.status, 200);
  assert.equal((await result.json()).status, 'unchanged');
  assert.equal((await db.prepare('SELECT products FROM cabinet_capacity').first()).products, 1);
});
test('冲突保留且重复冲突不增长，已有资料不覆盖', async () => {
  const changed = { ...product, name: '另一个测试候选' };
  for (let i = 0; i < 2; i++) {
    const result = await request('/v1/catalog/cache', 'POST', changed);
    assert.equal(result.status, 202);
    assert.equal((await result.json()).status, 'conflict_preserved');
  }
  assert.equal((await (await request(`/v1/catalog/${product.barcode}`)).json()).name, product.name);
  assert.equal((await db.prepare('SELECT conflicts FROM cabinet_capacity').first()).conflicts, 1);
});
test('并发不同候选保持唯一主记录，另一个完整保留', async () => {
  const code = '06902538005141';
  const results = await Promise.all(['候选甲', '候选乙'].map(name => request('/v1/catalog/cache', 'POST', { ...product, barcode: code, name })));
  assert.deepEqual(results.map(r => r.status).sort(), [201, 202]);
  assert.equal((await db.prepare('SELECT count(*) AS n FROM cabinet_catalog WHERE barcode=?').bind(code).first()).n, 1);
  assert.equal((await db.prepare('SELECT count(*) AS n FROM cabinet_conflicts WHERE barcode=?').bind(code).first()).n, 1);
});
test('注入文本仅作绑定数据；未知条码与无效校验位分开', async () => {
  const value = { ...product, barcode: '00012345678905', name: "测试'); DROP TABLE cabinet_catalog; --" };
  assert.equal((await request('/v1/catalog/cache', 'POST', value)).status, 201);
  assert.equal((await (await request(`/v1/catalog/${value.barcode}`)).json()).name, value.name);
  assert.equal((await request('/v1/catalog/00000000000000')).status, 404);
  assert.equal((await request('/v1/catalog/04006381333932')).status, 400);
});
test('拒绝隐私字段、过大内容、错误类型、访客注册附带正文及管理操作', async () => {
  assert.equal((await request('/v1/catalog/cache', 'POST', { ...product, expiryDate: '2030-01' })).status, 400);
  assert.equal((await request('/v1/catalog/cache', 'POST', '{')).status, 400);
  assert.equal((await request('/v1/catalog/cache', 'POST', 'a'.repeat(8193))).status, 413);
  assert.equal((await request('/v1/clients', 'POST', 'unexpected')).status, 400);
  assert.equal((await request(`/v1/catalog/${product.barcode}`, 'DELETE')).status, 405);
  assert.equal((await request('/v1/admin', 'POST', {})).status, 404);
  assert.equal((await request('/v1/catalog/cache', 'POST', product, token, { Origin: 'https://example.com' })).status, 400);
});
test('每日预算原子拒绝超限，拒绝后不插入内容', async () => {
  const key = `writes:${new Date().toISOString().slice(0, 10)}`;
  await db.prepare('UPDATE cabinet_daily_budget SET used=1000 WHERE budget_key=?').bind(key).run();
  const result = await request('/v1/catalog/cache', 'POST', { ...product, name: '超预算候选' });
  assert.equal(result.status, 429);
  assert.equal((await result.json()).error, 'daily_budget_reached');
  assert.equal((await db.prepare('SELECT count(*) AS n FROM cabinet_conflicts WHERE candidate_json LIKE ?').bind('%超预算候选%').first()).n, 0);
  await db.prepare('UPDATE cabinet_daily_budget SET used=0 WHERE budget_key=?').bind(key).run();
});
test('容量限制中止事务，错误不泄漏内部 SQL 或令牌', async () => {
  await db.prepare('UPDATE cabinet_capacity SET conflicts=10000 WHERE id=1').run();
  const result = await request('/v1/catalog/cache', 'POST', { ...product, name: '容量上限候选' });
  assert.equal(result.status, 503);
  assert.deepEqual(await result.json(), { error: 'service_unavailable' });
  await db.prepare('UPDATE cabinet_capacity SET conflicts=2 WHERE id=1').run();
});
test('边缘限流生效，缺少绑定与停止写入时关闭功能', async () => {
  const isolated = new Miniflare(options({ ratelimits: { IP_RATE: { namespace_id: '919901', simple: { limit: 1, period: 60 } },
    ENROLL_RATE: { namespace_id: '919902', simple: { limit: 1, period: 60 } },
    WRITE_RATE: { namespace_id: '919903', simple: { limit: 1, period: 60 } } } }));
  try {
    assert.equal((await isolated.dispatchFetch('https://catalog.example/v1/catalog/04006381333931')).status, 401);
    assert.equal((await isolated.dispatchFetch('https://catalog.example/v1/catalog/04006381333931')).status, 429);
  } finally { await isolated.dispose(); }
  const closed = new Miniflare(options({ bindings: { ENROLLMENT_ENABLED: 'false', WRITES_ENABLED: 'false' } }));
  try { assert.equal((await closed.dispatchFetch('https://catalog.example/v1/clients', { method: 'POST' })).status, 403); }
  finally { await closed.dispose(); }
  const unconfigured = new Miniflare(options({ ratelimits: {} }));
  try { assert.equal((await unconfigured.dispatchFetch('https://catalog.example/v1/clients', { method: 'POST' })).status, 503); }
  finally { await unconfigured.dispose(); }
});
