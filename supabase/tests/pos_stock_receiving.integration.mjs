import { readFile, readdir } from 'node:fs/promises';
import { PGlite } from '@electric-sql/pglite';
import { pgcrypto } from '@electric-sql/pglite/contrib/pgcrypto';
// Run from the repository root after `pnpm install --frozen-lockfile`.
// This test uses in-memory PostgreSQL; it never connects to Supabase.
const db = new PGlite({ extensions: { pgcrypto } });
try {
  await db.exec(`create role anon; create role authenticated; create role service_role; create schema extensions;
    create schema storage;
    create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
    create table storage.objects(id uuid default gen_random_uuid(),bucket_id text,name text);
    create function storage.foldername(name text) returns text[] language sql as $$ select string_to_array(name,'/') $$;
  `);
  const files = (await readdir('supabase/migrations')).filter(f => f.endsWith('.sql')).sort();
  for (const file of files) {
    if (file === '202609300002_fix_pos_inventory_movement_enum.sql') continue;
    const sql = await readFile('supabase/migrations/' + file, 'utf8');
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
  const regressionFiles = ['stockable_product_classification.sql', 'set_stock_on_hand.sql', 'password_login_limits.sql', 'r06_replay_and_reset.sql', 'r06_catalog_pages.sql', 'r06_sale_snapshots.sql', 'r08_development_contract.sql', 'powder_pack_inventory.sql', 'product_images.sql'];
  if (process.argv.includes('--extended')) regressionFiles.push('recipes_and_closings.sql');
  if (process.argv.includes('--activation')) regressionFiles.push('activation_alignment.sql', 'separate_revenue_deductions.sql', 'system_admin_data_reset.sql', 'admin_close_open_business_day.sql');
  for (const file of ['app_authorization.sql', 'admin_passkey_assurance.sql', 'pos_device_proof_and_logout.sql', 'ims_submission_idempotency.sql', ...regressionFiles]) {
    await db.exec(await readFile('supabase/tests/' + file, 'utf8'));
    console.log('PASS: database regression ' + file);
  }
} catch (error) {
  console.error(`FAIL: ${error.code ?? ''} ${error.message}`);
  process.exitCode = 1;
} finally { await db.close(); }
