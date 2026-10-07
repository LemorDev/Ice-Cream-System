// Read-only PostgREST smoke test. It sends anonymous requests only, so every
// protected RPC must exist but deny access. Positive role behavior is covered
// by r08_development_contract.sql on the verified development project.
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

const expectedRef = 'fhyqrgxwdqlyzxsnpthr';
const linkedRef = (await readFile('supabase/.temp/project-ref', 'utf8')).trim();
if (linkedRef !== expectedRef) throw new Error('CLI is not linked to the development project.');

const envText = await readFile('apps/web-ims/.env.development.local', 'utf8');
const env = Object.fromEntries(envText.split(/\r?\n/).filter((line) => /^[A-Za-z_][A-Za-z0-9_]*=/.test(line))
  .map((line) => { const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1).trim().replace(/^['"]|['"]$/g, '')]; }));
const url = new URL(env.VITE_SUPABASE_URL);
if (url.protocol !== 'https:' || url.hostname !== `${expectedRef}.supabase.co` || !env.VITE_SUPABASE_ANON_KEY) {
  throw new Error('The development URL or publishable key is missing or points to another project.');
}

const calls = [
  ['get_pos_products_page', {}],
  ['get_pos_recipes_page', {}],
  ['get_pos_inventory_ledger_page_v2', {}],
  ['push_pos_transaction_v2', { p_transaction: {} }],
  ['push_pos_inventory_entry_v2', { p_entry: {} }],
  ['push_revenue_deduction_v2', { p_deduction: {} }],
  ['push_pos_sale_reversal', { p_reversal: {} }],
  ['push_daily_store_closing_v2', { p_closing: {} }],
  ['get_ims_financial_snapshot', { p_stall_id: randomUUID() }],
];

for (const [name, body] of calls) {
  const response = await fetch(new URL(`/rest/v1/rpc/${name}`, url), {
    method: 'POST',
    headers: {
      apikey: env.VITE_SUPABASE_ANON_KEY,
      Authorization: `Bearer ${env.VITE_SUPABASE_ANON_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  });
  const result = await response.json().catch(() => ({}));
  if (![401, 403].includes(response.status) || result.code !== '42501') {
    throw new Error(`${name}: expected protected RPC denial, got HTTP ${response.status} / ${result.code ?? 'no code'}`);
  }
  console.log(`PASS: ${name} exists and denies anonymous access`);
}
