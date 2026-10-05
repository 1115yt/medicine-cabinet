/** 共享基础资料接口：不接受药箱隐私数据，也不提供管理操作。 */
const MAX_BODY = 8192;
const FIELDS = ['barcode', 'name', 'specification', 'packageUnit', 'manufacturer', 'approval', 'source'];
const LIMITS = { name: 80, specification: 120, packageUnit: 8, manufacturer: 200, approval: 100 };
const SOURCES = new Set(['mxnzp', 'aliyun', 'manual']);
const encoder = new TextEncoder();

class RequestError extends Error {
  constructor(status, code) { super(code); this.status = status; }
}

function response(status, data) {
  return Response.json(data, { status, headers: {
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
    ...(status === 429 ? { 'Retry-After': '60' } : {})
  } });
}

export function normalizeBarcode(value) {
  if (typeof value !== 'string' || !/^(\d{8}|\d{12,14})$/.test(value)) return null;
  const digits = [...value].map(Number);
  let sum = 0;
  for (let i = digits.length - 2, weight = 3; i >= 0; i--, weight = 4 - weight) sum += digits[i] * weight;
  return (10 - sum % 10) % 10 === digits.at(-1) ? value.padStart(14, '0') : null;
}

export function validateProduct(value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).length !== FIELDS.length || FIELDS.some(key => !Object.hasOwn(value, key))) {
    throw new RequestError(400, 'invalid_fields');
  }
  const barcode = normalizeBarcode(value.barcode);
  if (!barcode) throw new RequestError(400, 'invalid_barcode');
  const result = { barcode };
  for (const [key, max] of Object.entries(LIMITS)) {
    const text = value[key];
    if (typeof text !== 'string' || text.length > max || /[\u0000-\u001f\u007f]/.test(text) ||
        (['name', 'packageUnit'].includes(key) && !text.trim())) throw new RequestError(400, 'invalid_product');
    result[key] = text.trim();
  }
  if (!SOURCES.has(value.source)) throw new RequestError(400, 'invalid_source');
  return { ...result, source: value.source };
}

async function digest(text) {
  const bytes = await crypto.subtle.digest('SHA-256', encoder.encode(text));
  return [...new Uint8Array(bytes)].map(value => value.toString(16).padStart(2, '0')).join('');
}

function newToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

async function limited(binding, key) {
  if (!binding || typeof binding.limit !== 'function') throw new RequestError(503, 'service_not_configured');
  if (!(await binding.limit({ key })).success) throw new RequestError(429, 'rate_limited');
}

async function readBody(request, empty = false) {
  const length = request.headers.get('Content-Length');
  if (length !== null && (!/^\d+$/.test(length) || Number(length) > MAX_BODY)) throw new RequestError(413, 'body_too_large');
  const reader = request.body?.getReader();
  if (!reader) return '';
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY || (empty && size > 0)) {
        await reader.cancel();
        throw new RequestError(empty ? 400 : 413, empty ? 'unexpected_body' : 'body_too_large');
      }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
}

async function claimBudget(db, kind, configuredLimit, fallback) {
  const limit = configuredLimit === undefined ? fallback : Number(configuredLimit);
  if (!Number.isInteger(limit) || limit < 1 || limit > fallback) throw new RequestError(503, 'invalid_budget');
  const key = `${kind}:${new Date().toISOString().slice(0, 10)}`;
  const row = await db.prepare(`INSERT INTO cabinet_daily_budget(budget_key,used) VALUES(?,1)
    ON CONFLICT(budget_key) DO UPDATE SET used=used+1 WHERE used < ? RETURNING used`).bind(key, limit).first();
  if (!row) throw new RequestError(429, 'daily_budget_reached');
}

async function authenticate(request, db) {
  const authorization = request.headers.get('Authorization') || '';
  const match = /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(authorization);
  if (!match) throw new RequestError(401, 'unauthorized');
  const hash = await digest(match[1]);
  if (!await db.prepare('SELECT token_hash FROM cabinet_clients WHERE token_hash=?').bind(hash).first()) {
    throw new RequestError(401, 'unauthorized');
  }
  return hash;
}

async function enroll(request, env, ip) {
  if (env.ENROLLMENT_ENABLED !== 'true') throw new RequestError(403, 'enrollment_disabled');
  await limited(env.ENROLL_RATE, `enroll:${ip}`);
  await readBody(request, true);
  await claimBudget(env.DB, 'clients', env.DAILY_CLIENT_LIMIT, 200);
  const token = newToken();
  await env.DB.prepare('INSERT INTO cabinet_clients(token_hash,created_at) VALUES(?,?)')
    .bind(await digest(token), new Date().toISOString()).run();
  return response(201, { token });
}

async function append(request, env, actor) {
  if (env.WRITES_ENABLED !== 'true') throw new RequestError(403, 'writes_disabled');
  await limited(env.WRITE_RATE, `write:${actor}`);
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) {
    throw new RequestError(415, 'json_required');
  }
  let input;
  try { input = JSON.parse(await readBody(request)); }
  catch (error) { if (error instanceof RequestError) throw error; throw new RequestError(400, 'invalid_json'); }
  const product = validateProduct(input);
  // 来源不参与身份内容去重；来源是声明，不能证明资料已经核实。
  const hash = await digest(JSON.stringify(FIELDS.filter(key => key !== 'source').map(key => product[key])));
  const existing = await env.DB.prepare('SELECT content_hash FROM cabinet_catalog WHERE barcode=?').bind(product.barcode).first();
  if (existing?.content_hash === hash) return response(200, { status: 'unchanged', barcode: product.barcode });
  const duplicate = await env.DB.prepare('SELECT content_hash FROM cabinet_conflicts WHERE barcode=? AND content_hash=?')
    .bind(product.barcode, hash).first();
  if (duplicate) return response(202, { status: 'conflict_preserved', barcode: product.barcode });
  await claimBudget(env.DB, 'writes', env.DAILY_WRITE_LIMIT, 1000);
  const created = new Date().toISOString();
  // 两个插入和最终读回在同一批事务中：并发首次提交也不会覆盖或丢掉冲突候选。
  const results = await env.DB.batch([
    env.DB.prepare(`INSERT INTO cabinet_catalog(barcode,name,specification,package_unit,manufacturer,approval,source,content_hash,created_at)
      SELECT ?,?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM cabinet_catalog WHERE barcode=?)`)
      .bind(product.barcode, product.name, product.specification, product.packageUnit, product.manufacturer,
        product.approval, product.source, hash, created, product.barcode),
    env.DB.prepare(`INSERT INTO cabinet_conflicts(barcode,content_hash,candidate_json,created_at)
      SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM cabinet_catalog WHERE barcode=? AND content_hash != ?)
      AND NOT EXISTS(SELECT 1 FROM cabinet_conflicts WHERE barcode=? AND content_hash=?)`)
      .bind(product.barcode, hash, JSON.stringify(product), created, product.barcode, hash, product.barcode, hash),
    env.DB.prepare('SELECT content_hash FROM cabinet_catalog WHERE barcode=?').bind(product.barcode)
  ]);
  const conflict = results[2].results[0]?.content_hash !== hash;
  return response(conflict ? 202 : (results[0].meta.changes ? 201 : 200), {
    status: conflict ? 'conflict_preserved' : (results[0].meta.changes ? 'stored' : 'unchanged'), barcode: product.barcode
  });
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.search || request.headers.has('Origin')) throw new RequestError(400, 'unsupported_request');
      if (!env.DB || !env.IP_RATE || !env.ENROLL_RATE || !env.WRITE_RATE) throw new RequestError(503, 'service_not_configured');
      // 只采用 Cloudflare 边缘提供的地址作限流键，不信任客户端转发头；不入数据库或日志。
      const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
      await limited(env.IP_RATE, `ip:${ip}`);
      if (url.pathname === '/health' && request.method === 'GET') {
        if (!await env.DB.prepare('SELECT 1 AS ready FROM cabinet_capacity WHERE id=1').first()) {
          throw new RequestError(503, 'schema_not_ready');
        }
        return response(200, { status: 'ok', schema: 1 });
      }
      if (url.pathname === '/v1/clients' && request.method === 'POST') return await enroll(request, env, ip);
      if (!['GET', 'POST'].includes(request.method)) throw new RequestError(405, 'method_not_allowed');
      const actor = await authenticate(request, env.DB);
      if (url.pathname === '/v1/catalog/cache' && request.method === 'POST') return await append(request, env, actor);
      const match = /^\/v1\/catalog\/(\d{14})$/.exec(url.pathname);
      if (match && request.method === 'GET') {
        const code = normalizeBarcode(match[1]);
        if (!code) throw new RequestError(400, 'invalid_barcode');
        const product = await env.DB.prepare(`SELECT barcode,name,specification,package_unit AS packageUnit,
          manufacturer,approval,source FROM cabinet_catalog WHERE barcode=?`).bind(code).first();
        if (!product) throw new RequestError(404, 'not_found');
        return response(200, { ...product, verified: false });
      }
      throw new RequestError(404, 'route_not_found');
    } catch (error) {
      // 不返回 SQL、认证、商品资料或原始异常；容量与数据库故障均保留客户端的补传队列。
      return response(error instanceof RequestError ? error.status : 503,
        { error: error instanceof RequestError ? error.message : 'service_unavailable' });
    }
  }
};
