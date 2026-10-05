-- 只新增本服务的表。重复执行会报错，避免误将已有结构当成当前结构。
CREATE TABLE cabinet_clients (
  token_hash TEXT PRIMARY KEY NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE cabinet_catalog (
  barcode TEXT PRIMARY KEY NOT NULL CHECK(length(barcode) = 14),
  name TEXT NOT NULL CHECK(length(name) BETWEEN 1 AND 80),
  specification TEXT NOT NULL CHECK(length(specification) <= 120),
  package_unit TEXT NOT NULL CHECK(length(package_unit) BETWEEN 1 AND 8),
  manufacturer TEXT NOT NULL CHECK(length(manufacturer) <= 200),
  approval TEXT NOT NULL CHECK(length(approval) <= 100),
  source TEXT NOT NULL CHECK(source IN ('mxnzp','aliyun','manual')),
  content_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE TABLE cabinet_conflicts (
  barcode TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  candidate_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  PRIMARY KEY(barcode, content_hash)
);
CREATE TABLE cabinet_daily_budget (
  budget_key TEXT PRIMARY KEY NOT NULL,
  used INTEGER NOT NULL CHECK(used >= 0)
);
CREATE TABLE cabinet_capacity (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  clients INTEGER NOT NULL,
  products INTEGER NOT NULL,
  conflicts INTEGER NOT NULL
);
INSERT INTO cabinet_capacity VALUES (1,0,0,0);
-- 容量检查与增加计数和插入处于同一事务，并发请求不能绕过上限。
CREATE TRIGGER cabinet_clients_capacity BEFORE INSERT ON cabinet_clients
WHEN (SELECT clients FROM cabinet_capacity WHERE id=1) >= 10000
BEGIN SELECT RAISE(ABORT, 'cabinet_capacity_reached'); END;
CREATE TRIGGER cabinet_clients_count AFTER INSERT ON cabinet_clients
BEGIN UPDATE cabinet_capacity SET clients=clients+1 WHERE id=1; END;
CREATE TRIGGER cabinet_catalog_capacity BEFORE INSERT ON cabinet_catalog
WHEN (SELECT products FROM cabinet_capacity WHERE id=1) >= 100000
BEGIN SELECT RAISE(ABORT, 'cabinet_capacity_reached'); END;
CREATE TRIGGER cabinet_catalog_count AFTER INSERT ON cabinet_catalog
BEGIN UPDATE cabinet_capacity SET products=products+1 WHERE id=1; END;
CREATE TRIGGER cabinet_conflicts_capacity BEFORE INSERT ON cabinet_conflicts
WHEN (SELECT conflicts FROM cabinet_capacity WHERE id=1) >= 10000
BEGIN SELECT RAISE(ABORT, 'cabinet_capacity_reached'); END;
CREATE TRIGGER cabinet_conflicts_count AFTER INSERT ON cabinet_conflicts
BEGIN UPDATE cabinet_capacity SET conflicts=conflicts+1 WHERE id=1; END;
