// Node 24 SQLite, in memory only. Run from the repository root.
// Check actual migration SQL against exported Room schemas with a pending queue.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { DatabaseSync } from 'node:sqlite';

const schemaRoot = 'apps/android-pos/app/schemas/com.icecreampost.pos.data.local.database.CoolerzDatabase';
const source = await readFile('apps/android-pos/app/src/main/java/com/icecreampost/pos/data/local/database/CoolerzDatabase.kt', 'utf8');
const target = JSON.parse(await readFile(`${schemaRoot}/15.json`, 'utf8')).database;
const samples = {
  business_days: { id: 'day', stallId: 'stall', deviceId: 'device', cashierId: 'cashier', businessDate: '2026-10-12', openedAt: '2026-10-12T00:00:00Z', closedAt: null },
  products: { id: 'powder', stallId: 'stall', name: 'Powder fixture', category: 'Ingredients', productType: 'raw', baseUnit: 'g', unit: 'g', isSellable: 0, packSize: 1000, conversionRate: 1, unitsInStock: 2500 },
  transactions: { id: 'sale', stallId: 'stall', deviceId: 'device', cashierId: 'cashier', businessDayId: 'day', receiptNumber: 'PENDING-001', status: 'completed', totalCents: 4000, cogsCents: 833, syncStatus: 'pending', isSynced: 0 },
  transaction_items: { id: 'item', transactionId: 'sale', productId: 'powder', productName: 'Powder fixture', quantity: 1, lineTotalCents: 4000 },
  inventory_ledger: { id: 'movement', stallId: 'stall', businessDayId: 'day', productId: 'powder', quantityDelta: -45, costTotalCents: 833, movementType: 'sale', referenceId: 'sale', isSynced: 0 },
  app_session: { id: 'current', userId: 'cashier', stallId: 'stall', deviceId: 'device', role: 'cashier', sessionToken: 'fixture-token', isActivated: 1 },
};

function migrationBlock(from, to) {
  const start = source.indexOf(`val MIGRATION_${from}_${to} =`);
  assert.ok(start >= 0, `Missing migration ${from}→${to}`);
  const next = source.indexOf('val MIGRATION_', start + 4);
  return source.slice(start, next < 0 ? undefined : next);
}

function migrationSql(from, to) {
  const block = migrationBlock(from, to);
  const calls = [...block.matchAll(/db\.execSQL\("((?:\\.|[^"\\])*)"\)/g)];
  assert.ok(calls.length > 0, `No SQL in migration ${from}→${to}`);
  return calls.map((match) => JSON.parse(`"${match[1]}"`));
}

for (const scenario of [3, 4, 11, 12, 13, 14, 'cloud4']) {
  const cloud = scenario === 'cloud4';
  const startingVersion = cloud ? 3 : scenario;
  const db = new DatabaseSync(':memory:');
  try {
    const initial = JSON.parse(await readFile(`${schemaRoot}/${startingVersion}.json`, 'utf8')).database;
    for (const entity of initial.entities) {
      db.exec(entity.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
      for (const index of entity.indices ?? []) db.exec(index.createSql.replaceAll('${TABLE_NAME}', entity.tableName));
    }
    const before = new Map();
    for (const [table, sample] of Object.entries(samples)) {
      const entity = initial.entities.find((item) => item.tableName === table);
      if (!entity) continue;
      const columns = entity.fields.map((field) => field.columnName);
      const values = entity.fields.map((field) => Object.hasOwn(sample, field.columnName) ? sample[field.columnName]
        : !field.notNull ? null : field.affinity === 'TEXT' ? '2026-10-12T01:00:00Z' : 0);
      db.prepare(`INSERT INTO "${table}" (${columns.map((name) => `"${name}"`).join(',')}) VALUES (${columns.map(() => '?').join(',')})`).run(...values);
      before.set(table, db.prepare(`SELECT * FROM "${table}"`).get());
    }
    if (cloud) {
      db.exec('ALTER TABLE products ADD COLUMN imageUrl TEXT');
      db.exec("UPDATE products SET imageUrl='https://example.invalid/preserved.png'");
      before.set('products', db.prepare('SELECT * FROM products').get());
    }
    db.exec('BEGIN');
    let version = cloud ? 4 : startingVersion;
    if (version === 3) {
      for (const sql of migrationSql(3, 4)) db.exec(sql);
      version = 4;
    }
    if (version === 4) {
      const hasRecipes = db.prepare('PRAGMA table_info(products)').all().some((column) => column.name === 'productType');
      const bridge = migrationBlock(4, 15).replace(/if \(!hasRecipes\) \{([\s\S]*?)^\s*\}/gm, (_, body) => hasRecipes ? '' : body);
      for (const call of bridge.matchAll(/db\.execSQL\("((?:\\.|[^"\\])*)"\)|MIGRATION_(\d+)_(\d+)\.migrate\(db\)/g)) {
        if (call[1]) db.exec(JSON.parse(`"${call[1]}"`));
        else for (const sql of migrationSql(Number(call[2]), Number(call[3]))) db.exec(sql);
      }
    } else {
      for (; version < 15; version++) for (const sql of migrationSql(version, version + 1)) db.exec(sql);
    }
    db.exec('COMMIT');
    for (const entity of target.entities) {
      const columns = db.prepare(`PRAGMA table_info("${entity.tableName}")`).all();
      assert.deepEqual(columns.map((column) => column.name).sort(), entity.fields.map((field) => field.columnName).sort(), `${entity.tableName} columns`);
      for (const field of entity.fields) {
        const actual = columns.find((column) => column.name === field.columnName);
        assert.equal(actual.type, field.affinity, `${entity.tableName}.${field.columnName} type`);
        assert.equal(Boolean(actual.notnull), Boolean(field.notNull), `${entity.tableName}.${field.columnName} nullability`);
      }
      const indices = db.prepare(`PRAGMA index_list("${entity.tableName}")`).all();
      for (const index of entity.indices ?? []) assert.ok(indices.some((actual) => actual.name === index.name), `Missing index ${index.name}`);
    }
    for (const [table, original] of before) {
      const actual = db.prepare(`SELECT * FROM "${table}"`).get();
      for (const [column, value] of Object.entries(original)) assert.equal(actual[column], value, `${table}.${column} lost queued data`);
    }
    assert.equal(db.prepare('SELECT tracksPowderPacks FROM products').get().tracksPowderPacks, 0);
    assert.equal(db.prepare('SELECT sealedPacksDelta FROM inventory_ledger').get().sealedPacksDelta, 0);
    assert.equal(db.prepare('SELECT businessDayId FROM transactions').get().businessDayId, 'day');
    assert.equal(db.prepare('SELECT businessDayId FROM inventory_ledger').get().businessDayId, 'day');
    console.log(`PASS: Room ${cloud ? 'cloud version 4' : startingVersion}→15 preserves queued sale, stock, operating day, session, and existing photo; schema matches version 15`);
  } finally { db.close(); }
}
