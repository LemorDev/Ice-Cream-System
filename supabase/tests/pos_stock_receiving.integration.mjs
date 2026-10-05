import { readFile, readdir } from 'node:fs/promises';
// Run from the repository root. Install the isolated runtime first:
// npm install --prefix apps/android-pos/app/build/sql-test-runtime --no-audit --no-fund --package-lock=false @electric-sql/pglite@0.5.8
// This test uses in-memory PostgreSQL; it never connects to Supabase.
// pgcrypto is unavailable in PGlite: fixtures use native SHA-256 for token hashes
// and UUID bytes for test token generation. Password-login/crypto behavior is outside this test.
import { createRequire } from 'node:module';
const requireRuntime = createRequire(new URL('../../apps/android-pos/app/build/sql-test-runtime/package.json', import.meta.url));
const { PGlite } = requireRuntime('@electric-sql/pglite');
const db = new PGlite();
try {
  await db.exec(`create role anon; create role authenticated; create schema extensions;
    create function public.digest(value text, algorithm text) returns bytea language sql as
    $$ select sha256(convert_to(value, 'UTF8')) $$;
    create function public.digest(value bytea, algorithm text) returns bytea language sql as
    $$ select sha256(value) $$;
    create function public.gen_random_bytes(length integer) returns bytea language sql as
    $$ select substring(decode(replace(gen_random_uuid()::text,'-','') || replace(gen_random_uuid()::text,'-',''),'hex') from 1 for length) $$;
    create function public.gen_salt(algorithm text) returns text language sql as $$ select 'test-salt'::text $$;
    create function public.crypt(password text, salt text) returns text language sql as
    $$ select encode(sha256(convert_to(password, 'UTF8')), 'hex') $$;
  `);
  const files = (await readdir('supabase/migrations')).filter(f => f.endsWith('.sql')).sort();
  for (const file of files) {
    if (file === '202609300002_fix_pos_inventory_movement_enum.sql') continue;
    let sql = await readFile('supabase/migrations/' + file, 'utf8');
    sql = sql.replace('create extension if not exists pgcrypto;', '-- pgcrypto token hashing uses native SHA-256 in this test runtime.');
    try { await db.exec(sql); } catch(e) { throw new Error(`${file}: ${e.message}`, {cause:e}); }
  }
  if (process.argv.includes('--rerun-alignment')) {
    const alignment = await readFile('supabase/migrations/202610010002_pos_activation_and_deduction_alignment.sql', 'utf8');
    await db.exec(alignment);
    console.log('PASS: activation and deduction alignment migration can be reapplied');
  }
  const fixture = await readFile('supabase/tests/pos_stock_receiving.sql','utf8');
  try {
    await db.exec(fixture);
    throw new Error('Expected original receipt RPC to fail');
  } catch(e) {
    await db.exec('rollback');
    if (e.code !== '42804' || !e.message.includes('movement_type')) throw e;
    console.log('PASS: reproduced original receipt text-to-enum failure (42804)');
  }
  await db.exec(await readFile('supabase/migrations/202609300002_fix_pos_inventory_movement_enum.sql','utf8'));
  await db.exec(fixture);
  console.log('PASS: corrected receipt integration fixture (stock totals, repeat deliveries, retry idempotency, input validation, permissions)');
  const regressionFiles = ['stockable_product_classification.sql', 'set_stock_on_hand.sql'];
  if (process.argv.includes('--extended')) regressionFiles.push('recipes_and_closings.sql');
  if (process.argv.includes('--activation')) regressionFiles.push('activation_alignment.sql', 'separate_revenue_deductions.sql', 'system_admin_data_reset.sql', 'admin_close_open_business_day.sql');
  for (const file of regressionFiles) {
    await db.exec(await readFile('supabase/tests/' + file, 'utf8'));
    console.log('PASS: database regression ' + file);
  }
} catch (error) {
  console.error(`FAIL: ${error.code ?? ''} ${error.message}`);
  process.exitCode = 1;
} finally { await db.close(); }
