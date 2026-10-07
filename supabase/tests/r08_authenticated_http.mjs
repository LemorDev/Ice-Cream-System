// Development-only PostgREST contract. Creates synthetic accounts and rows,
// exercises the same RPC headers used by POS/IMS, and removes them in finally.
// No real password or account token is read or printed.
import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomBytes, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const expectedRef = 'fhyqrgxwdqlyzxsnpthr';
const linkedRef = (await readFile('supabase/.temp/project-ref', 'utf8')).trim();
if (linkedRef !== expectedRef) throw new Error('CLI is not linked to development.');
const envText = await readFile('apps/web-ims/.env.development.local', 'utf8');
const env = Object.fromEntries(envText.split(/\r?\n/).filter((line) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(line))
  .map((line) => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim().replace(/^['"]|['"]$/g, '')]; }));
const url = new URL(env.VITE_SUPABASE_URL);
if (url.protocol !== 'https:' || url.hostname !== `${expectedRef}.supabase.co` ||
  env.VITE_APP_ENV !== 'development' || !env.VITE_SUPABASE_ANON_KEY) {
  throw new Error('Development HTTP configuration does not match the linked project.');
}

const ids = Object.fromEntries(['stallA','stallB','cashierA','cashierB','ownerA',
  'deviceA','deviceB','dayA','rawA','serveA','rawB','saleA','itemA','movementA']
  .map((name) => [name, randomUUID()]));
const tokenA = randomBytes(32).toString('hex');
const tokenB = randomBytes(32).toString('hex');
const ownerToken = randomBytes(32).toString('hex');
const hardwareA = `r08-${ids.deviceA}`;
const hardwareB = `r08-${ids.deviceB}`;
const temp = await mkdtemp(join(tmpdir(), 'r08-http-'));
const cli = resolve('node_modules/supabase/dist/supabase.js');
const sqlFile = join(temp, 'query.sql');
let setupAttempted = false;
let cleanupError = null;

async function query(sql) {
  await writeFile(sqlFile, sql, 'utf8');
  const call = spawnSync(process.execPath, [cli, 'db', 'query', '--linked',
    '--project-ref', expectedRef, '--file', sqlFile], {
    cwd: process.cwd(), encoding: 'utf8', timeout: 60000,
    env: { ...process.env, SUPABASE_TELEMETRY_DISABLED: '1' },
  });
  if (call.error || call.status !== 0) {
    const detail = `${call.error?.message ?? ''} ${call.stderr ?? ''} ${call.stdout ?? ''}`
      .replaceAll(tokenA, '[test token]').replaceAll(tokenB, '[test token]')
      .replaceAll(ownerToken, '[test token]').slice(-1600);
    throw new Error(`Development SQL query failed: ${detail}`);
  }
}

async function rpc(name, body, token, hardware) {
  const response = await fetch(new URL(`/rest/v1/rpc/${name}`, url), {
    method: 'POST', signal: AbortSignal.timeout(15000),
    headers: {
      apikey: env.VITE_SUPABASE_ANON_KEY,
      Authorization: `Bearer ${env.VITE_SUPABASE_ANON_KEY}`,
      'X-Session-Token': token,
      ...(hardware ? { 'X-Device-Id': hardware } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  return { status: response.status, data: await response.json().catch(() => ({})) };
}

function check(condition, message) { if (!condition) throw new Error(message); }

const setup = `do $$ begin
  insert into public.stalls(id,name,code) values
    ('${ids.stallA}','R08 HTTP A','R08A-${ids.stallA.slice(0,8)}'),
    ('${ids.stallB}','R08 HTTP B','R08B-${ids.stallB.slice(0,8)}');
  insert into public.app_users(id,stall_id,email,display_name,role,password_hash) values
    ('${ids.cashierA}','${ids.stallA}','${ids.cashierA}@example.invalid','R08 cashier A','cashier','unused'),
    ('${ids.cashierB}','${ids.stallB}','${ids.cashierB}@example.invalid','R08 cashier B','cashier','unused'),
    ('${ids.ownerA}','${ids.stallA}','${ids.ownerA}@example.invalid','R08 owner A','owner','unused');
  insert into public.owner_stall_access(user_id,stall_id) values('${ids.ownerA}','${ids.stallA}');
  insert into public.app_sessions(user_id,token_hash,expires_at) values
    ('${ids.cashierA}',encode(digest('${tokenA}','sha256'),'hex'),now()+interval '1 hour'),
    ('${ids.cashierB}',encode(digest('${tokenB}','sha256'),'hex'),now()+interval '1 hour'),
    ('${ids.ownerA}',encode(digest('${ownerToken}','sha256'),'hex'),now()+interval '1 hour');
  insert into public.devices(id,stall_id,device_name,activation_code_hash,hardware_id) values
    ('${ids.deviceA}','${ids.stallA}','R08 HTTP device A','r08-${ids.deviceA}','${hardwareA}'),
    ('${ids.deviceB}','${ids.stallB}','R08 HTTP device B','r08-${ids.deviceB}','${hardwareB}');
  insert into public.products(id,stall_id,sku,name,unit,cost_price,pack_size,
    conversion_rate,is_sellable,product_type,base_unit) values
    ('${ids.rawA}','${ids.stallA}','RAW','R08 raw A','g',50,100,1,false,'raw','g'),
    ('${ids.serveA}','${ids.stallA}','SERVE','R08 serve A','piece',0,1,1,true,'sellable','piece'),
    ('${ids.rawB}','${ids.stallB}','RAW','R08 raw B','g',50,100,1,false,'raw','g');
  insert into public.product_recipes(stall_id,parent_product_id,ingredient_product_id,quantity)
    values('${ids.stallA}','${ids.serveA}','${ids.rawA}',10);
  insert into public.business_days(id,stall_id,device_id,cashier_id,business_date,opened_at)
    values('${ids.dayA}','${ids.stallA}','${ids.deviceA}','${ids.cashierA}',
      (now() at time zone 'Asia/Manila')::date,now()-interval '1 hour');
  insert into public.inventory_ledger(stall_id,product_id,quantity_delta,movement_type)
    values('${ids.stallA}','${ids.rawA}',100,'opening_balance'),
      ('${ids.stallB}','${ids.rawB}',100,'opening_balance');
end $$;`;

const cleanup = `begin;
  delete from public.sale_components where transaction_id='${ids.saleA}';
  delete from private.pos_sale_uploads where transaction_id='${ids.saleA}';
  delete from public.transaction_items where transaction_id='${ids.saleA}';
  delete from public.inventory_ledger where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.transactions where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.business_days where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.product_recipes where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.products where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.owner_stall_access where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.app_sessions where user_id in ('${ids.cashierA}','${ids.cashierB}','${ids.ownerA}');
  delete from public.devices where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.app_users where stall_id in ('${ids.stallA}','${ids.stallB}');
  delete from public.stalls where id in ('${ids.stallA}','${ids.stallB}');
commit;`;

try {
  setupAttempted = true;
  await query(setup);
  const productsA = await rpc('get_pos_products_page', {}, tokenA, hardwareA);
  check(productsA.status === 200 && Array.isArray(productsA.data) && productsA.data.length === 2 &&
    productsA.data.every((p) => p.stall_id === ids.stallA), 'Cashier A catalog did not stay in stall A.');
  const productsB = await rpc('get_pos_products_page', {}, tokenB, hardwareB);
  check(productsB.status === 200 && Array.isArray(productsB.data) && productsB.data.length === 1 &&
    productsB.data[0].stall_id === ids.stallB, 'Cashier B catalog did not stay in stall B.');
  console.log('PASS: authenticated POS pages isolate two stalls');

  const sale = {
    id: ids.saleA, stall_id: ids.stallA, business_day_id: ids.dayA,
    device_id: ids.deviceA, receipt_number: `R08-${ids.saleA}`, status: 'completed',
    subtotal: 50, total_amount: 50, cash_received: 50, change_amount: 0,
    occurred_at: new Date().toISOString(),
    items: [{ id: ids.itemA, product_id: ids.serveA, product_name: 'R08 serve A',
      quantity: 1, unit_price: 50, line_total: 50 }],
    components: [{ id: ids.movementA, product_id: ids.rawA, quantity: 10, cost_total: 5 }],
  };
  const first = await rpc('push_pos_transaction_v2', { p_transaction: sale }, tokenA, hardwareA);
  check(first.status === 200 && first.data.status === 'accepted' &&
    first.data.transaction_id === ids.saleA, 'First POS sale was not accepted.');
  const retry = await rpc('push_pos_transaction_v2', { p_transaction: sale }, tokenA, hardwareA);
  check(retry.status === 200 && retry.data.status === 'duplicate', 'Exact POS replay was not a duplicate.');
  const conflict = await rpc('push_pos_transaction_v2',
    { p_transaction: { ...sale, total_amount: 51 } }, tokenA, hardwareA);
  check(conflict.status === 409 && conflict.data.code === '23505', 'Changed sale replay was accepted.');
  console.log('PASS: authenticated POS sale, exact replay, and conflict');

  const owner = await rpc('get_ims_financial_snapshot', { p_stall_id: ids.stallA }, ownerToken);
  check(owner.status === 200 && owner.data.transactions?.length === 1 &&
    owner.data.transaction_items?.length === 1 && owner.data.sale_components?.length === 1 &&
    Number(owner.data.transactions[0].cogs) === 5,
    'Owner HTTP snapshot did not match the POS sale.');
  const other = await rpc('get_ims_financial_snapshot', { p_stall_id: ids.stallB }, ownerToken);
  check([401, 403].includes(other.status) && other.data.code === '42501',
    'Owner A could access stall B.');
  const cashier = await rpc('get_ims_financial_snapshot', { p_stall_id: ids.stallA }, tokenA, hardwareA);
  check([401, 403].includes(cashier.status) && cashier.data.code === '42501',
    'Cashier received Owner financial data.');
  console.log('PASS: Owner HTTP snapshot matches POS and enforces roles');
} finally {
  if (setupAttempted) {
    try { await query(cleanup); console.log('PASS: development HTTP test rows removed'); }
    catch (error) { cleanupError = error; }
  }
  await rm(temp, { recursive: true, force: true });
  if (cleanupError) throw cleanupError;
}
