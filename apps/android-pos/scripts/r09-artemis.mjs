#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../');
const defaultManifestPath = join(root, 'apps/android-pos/app/build/r09-candidate/manifest.json');
const defaultApkPath = join(root, 'apps/android-pos/app/build/r09-candidate/coolerz-pos-dev-r09-20261013.apk');
const evidenceRoot = join(root, 'apps/android-pos/.local/r09-artemis');
const holdPath = join(evidenceRoot, 'SYNC_HOLD.json');
const expectedProject = 'fhyqrgxwdqlyzxsnpthr';
const packageName = 'com.icecreampost.pos.dev';

const cases = {
  'R09-01': {
    required: [],
    profile: 'flash',
    prompt: `Inspect Coolerz POS (DEV) after the tester signed in locally. Confirm the signed-in screen is visible and no JSON-null or recovery error is shown. Do not type, request, or report any password. Report visible registration evidence, but do not infer device reuse solely from a successful sign-in. Report exact visible evidence and what remains unverified.`,
  },
  'R09-02': {
    required: [],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), first inspect the current signed-in stall, catalog, and inventory. Sync only if there is no pre-update queue that must be preserved for R09-10. Record visible product names, prices, recipe ingredients and quantities, and stock for one representative menu item. Look for any items from a different stall. Do not edit data. Report only values actually visible on the phone; explicitly say when recipes, stall identity, or cross-stall isolation cannot be verified. Do not infer them from a sync banner or from a short list. Identify the IMS values that still require comparison.`,
  },
  'R09-03': {
    required: ['menu_item', 'sale_tendered'],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), resume the existing open day if present; otherwise open today's day. Before adding an item, inspect Sync status. If the operating day has an error, a queued record needs attention, or any transaction is already pending, STOP and report the blocker without making a sale. Otherwise make exactly ONE development sale of one {{menu_item}}, with cash tendered {{sale_tendered}}. Before submitting, inspect the cart and stop if it contains unexpected items or an existing unfinished sale. Record receipt number, price, change, stock before/after, sync queue before/after, and any visible COGS. Attempt sync at most once; if it fails, STOP and report the error, without tapping Retry. Do not repeat checkout if its result is ambiguous. IMS revenue and COGS need independent review.`,
  },
  'R09-03R': {
    required: ['menu_item', 'sale_tendered'],
    profile: 'pro',
    prompt: `This is the clean same-device R09-03 day-resume regression retest after a separately approved app-data clear and Cashier sign-in. Before any sale, verify Coolerz POS (DEV) shows the existing Oct 13, 2026 operating day as OPEN and opened at 2:04 AM. Do not tap Open day or create a new day. Inspect Sync status and stop if the day differs, is closed, has an error, or any transaction is pending. Check the cart is empty. Only then save exactly ONE sale of one {{menu_item}} at ₱25.00 with cash tendered {{sale_tendered}}. Record receipt ID, total, change, ingredient stock before/after, and queue state. Sync at most once if needed; stop on ambiguity or error. Do not repeat checkout. IMS receipt uniqueness and day totals need independent review.`,
  },
  'R09-04': {
    required: ['deduction_amount', 'deduction_reason', 'deduction_affects_profit'],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), record exactly ONE revenue deduction of {{deduction_amount}} with reason {{deduction_reason}}. Set 'Additional expense in profit' to {{deduction_affects_profit}}. Inspect the summary before and after. Stop if an equivalent deduction already exists or save outcome is ambiguous; never retry blindly. Report expected cash and profit related values visible on phone. IMS comparison remains required.`,
  },
  'R09-05': {
    required: ['powder_product', 'receipt_packs'],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), receive exactly {{receipt_packs}} whole sealed pack(s) of tracked powder {{powder_product}} as a development stock receipt. Inspect starting total grams and sealed count first. If the app asks for quantity in another unit or the product is not tracked powder, stop without saving. After save and sync, inspect totals and queue. Do not repeat a receipt if save or sync is ambiguous. Report exact values for IMS comparison.`,
  },
  'R09-06': {
    required: ['powder_product'],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), inspect {{powder_product}} total grams, sealed packs, hopper grams, and any opening log. If a sealed pack is available, open exactly ONE pack. Verify sealed packs decrease by one, hopper increases by 1,000g, and total grams stays constant; COGS must not change. If save outcome is ambiguous, stop instead of repeating. Report exact before/after values and any negative hopper reminder.`,
  },
  'R09-07': {
    required: ['menu_item', 'offline_tendered'],
    profile: 'pro',
    prompt: `On the USB-connected Coolerz POS (DEV) phone, turn off Wi-Fi and mobile data. Confirm internet is offline. Save exactly TWO separate development sales of one {{menu_item}} each, using cash tendered {{offline_tendered}} for each. Record both receipt IDs and queue count. Force-stop and relaunch ONLY com.icecreampost.pos.dev; do not clear app data. Confirm both receipts and stock remain locally. Do not repeat either checkout if its save outcome is ambiguous. Leave the phone offline and report exact evidence.`,
  },
  'R09-08': {
    required: ['offline_receipt_1', 'offline_receipt_2'],
    profile: 'pro',
    prompt: `Reconnect internet on the USB-connected Coolerz POS (DEV) phone. Inspect receipts {{offline_receipt_1}} and {{offline_receipt_2}} before taking any sync action. If sign-in or reconnect already acknowledged both, STOP: report that the interrupted-upload retry was not exercised, and do not toggle connectivity or retry. If either is pending, sync once. During an active upload, briefly disable connectivity, restore it, and retry only the same pending receipts. Do not create sales or re-upload acknowledged receipts. Confirm queue reaches zero only after acknowledgment; any failure remains visible. Report each receipt's local status and whether a duplicate is visible. Independent IMS verification of exactly one server row per receipt remains required.`,
  },
  'R09-09': {
    required: [],
    profile: 'pro',
    prompt: `Inspect Coolerz POS (DEV) after the tester has arranged an actual expired cashier session. Check the documented offline-session behavior, force-stop and relaunch without clearing data, and inspect pending receipts. Do not manipulate system time, erase data, or create sales. Record visible evidence; if the session has not actually expired, report blocked rather than claiming a pass. The tester must reauthenticate locally before any upload check.`,
  },
  'R09-10P': {
    required: ['menu_item', 'sale_tendered'],
    profile: 'pro',
    prompt: `Prepare the R09-10 pending-queue update rehearsal on the currently installed compatible development build. Confirm the stall day is open and the sync queue is empty. Turn off Wi-Fi and mobile data and verify the phone is offline. Save exactly ONE sale of one {{menu_item}} with cash tendered {{sale_tendered}}. Record the receipt ID, stock before/after, and queue count. If a queue was already pending, the cart was not empty, or save outcome is ambiguous, STOP without another sale. Leave the phone offline; do not install, clear data, reconnect, or sync.`,
  },
  'R09-10': {
    required: ['update_receipt_id'],
    profile: 'pro',
    prompt: `After the tester has separately verified signing compatibility and installed the fixed candidate in place over the prior dev build, inspect Coolerz POS (DEV). Confirm prior queued receipt {{update_receipt_id}}, operating day, stock, and queue survived while the phone remains offline. Do not clear data, reinstall, reconnect, or create a sale. If the app requires online sign-in, report that UI inspection is gated by sign-in and stop; the tester will reauthenticate separately after the local queue is independently checked. If the queue was empty before update or installed signing compatibility was not established, report blocked. Later sync and IMS uniqueness require separate review.`,
  },
  'R09-11': {
    required: ['counted_cash'],
    profile: 'pro',
    prompt: `In Coolerz POS (DEV), inspect that all prior sale/receipt queues are acknowledged. Record receipt count, gross sales, COGS, deductions, waste, expected cash, and closing cash counted {{counted_cash}}. If any queue is pending or any amount is unclear, stop before closing. Otherwise close the operating day exactly once. Report the final visible totals and status for independent IMS reconciliation. Never retry close if outcome is ambiguous.`,
  },
};

function usage() {
  console.log('Usage: node apps/android-pos/scripts/r09-artemis.mjs plan [CASE] [--config PATH]');
  console.log('       node apps/android-pos/scripts/r09-artemis.mjs verify [--manifest PATH --apk PATH]');
  console.log('       node apps/android-pos/scripts/r09-artemis.mjs run CASE --config PATH --artemis PATH --execute [--manifest PATH --apk PATH] [--rerun]');
  console.log('       node apps/android-pos/scripts/r09-artemis.mjs status');
}

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i < 0 ? undefined : process.argv[i + 1];
}

function readConfig() {
  const path = argValue('--config');
  return path ? JSON.parse(readFileSync(resolve(path), 'utf8')) : {};
}

function render(id, config) {
  const test = cases[id];
  const missing = test.required.filter(key => config[key] === undefined || config[key] === null || String(config[key]).trim() === '');
  if (missing.length) throw new Error(`${id} needs config: ${missing.join(', ')}`);
  return test.prompt.replace(/{{([a-z0-9_]+)}}/g, (_, key) => String(config[key]));
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024, ...options });
  if (result.error) throw result.error;
  return result;
}

function preflight() {
  const selectedManifest = argValue('--manifest');
  const selectedApk = argValue('--apk');
  if (Boolean(selectedManifest) !== Boolean(selectedApk)) throw new Error('Provide both --manifest and --apk for another build.');
  const manifestPath = selectedManifest ? resolve(selectedManifest) : defaultManifestPath;
  const apkPath = selectedApk ? resolve(selectedApk) : defaultApkPath;
  if (!existsSync(manifestPath) || !existsSync(apkPath)) throw new Error('R09 candidate APK or manifest is missing. Do not substitute another build.');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  if (manifest.package !== packageName || manifest.developmentProject !== expectedProject) throw new Error('Candidate is not the expected DEV package/project.');
  const hash = createHash('sha256').update(readFileSync(apkPath)).digest('hex').toUpperCase();
  if (hash !== manifest.apkSha256.toUpperCase()) throw new Error('Candidate APK hash does not match manifest.');
  const devices = run('adb', ['devices', '-l']);
  if (devices.status !== 0) throw new Error(`ADB unavailable: ${devices.stderr || devices.stdout}`);
  const ready = devices.stdout.split(/\r?\n/).filter(line => /^\S+\s+device\b/.test(line));
  if (ready.length !== 1) throw new Error(`Expected exactly one authorized Android device; found ${ready.length}. Run adb devices -l.`);
  const serial = ready[0].split(/\s+/)[0];
  const model = run('adb', ['-s', serial, 'shell', 'getprop', 'ro.product.model']).stdout.trim();
  const android = run('adb', ['-s', serial, 'shell', 'getprop', 'ro.build.version.release']).stdout.trim();
  const installed = run('adb', ['-s', serial, 'shell', 'pm', 'path', packageName]);
  if (installed.status !== 0 || !installed.stdout.includes('package:')) throw new Error(`${packageName} is not installed. Preserve any old queue and verify signing compatibility before installing.`);
  const baseApk = installed.stdout.split(/\r?\n/).find(line => line.startsWith('package:') && line.endsWith('/base.apk'))?.slice('package:'.length);
  if (!baseApk) throw new Error('Could not locate the installed base APK for checksum verification.');
  const installedHash = run('adb', ['-s', serial, 'shell', 'sha256sum', baseApk]);
  const actualHash = installedHash.stdout.trim().match(/^([0-9a-f]{64})\s/i)?.[1]?.toUpperCase();
  if (installedHash.status !== 0 || actualHash !== hash) throw new Error(`Installed APK does not match the selected build. Expected ${hash}, found ${actualHash || 'unavailable'}.`);
  return { serial, model, android, apkSha256: hash, package: packageName, developmentProject: expectedProject };
}

function hasArtemisCredential(artemisPath) {
  const names = ['GEMINI_API_KEY', 'GOOGLE_API_KEY', 'OPENAI_API_KEY', 'ANTHROPIC_API_KEY', 'OPEN_ROUTER_API_KEY', 'XAI_API_KEY'];
  if (names.some(name => process.env[name]?.trim())) return true;
  const envPath = resolve(artemisPath, '.env');
  if (!existsSync(envPath)) return false;
  const entries = readFileSync(envPath, 'utf8').split(/\r?\n/);
  return entries.some(line => {
    const match = line.match(/^\s*([A-Z_]+)\s*=\s*(.*)$/);
    return match && names.includes(match[1]) && Boolean(match[2].trim());
  });
}

function saveAttempt(base, record, output, screenshot) {
  writeFileSync(`${base}.json`, JSON.stringify(record, null, 2) + '\n');
  writeFileSync(`${base}.txt`, output);
  if (screenshot) writeFileSync(`${base}.png`, screenshot);
  return base;
}

async function collectArtemisEvidence(artemisPath, output, base) {
  const sessionId = output.match(/Session:\s*([0-9a-f-]{36})/i)?.[1];
  if (!sessionId) return { evidenceNote: 'Artemis session ID was not printed.' };
  const trace = resolve(artemisPath, 'traces', sessionId);
  const notes = join(trace, 'notes');
  const report = existsSync(notes) ? readdirSync(notes).find(name => name.endsWith('_report.md')) : undefined;
  if (report) writeFileSync(`${base}.artemis-report.md`, readFileSync(join(notes, report)));
  let stepsCaptured = false;
  try {
    const response = await fetch(`http://127.0.0.1:8000/api/sessions/${sessionId}/steps`,
      { signal: AbortSignal.timeout(5000) });
    if (response.ok) {
      const steps = await response.json();
      writeFileSync(`${base}.steps.json`, JSON.stringify(steps.map(step => ({
        step_number: step.step_number, summary: step.summary, action_taken: step.action_taken,
      })), null, 2) + '\n');
      stepsCaptured = true;
    }
  } catch { /* The local daemon may be unavailable after a run; its trace remains. */ }
  return { sessionId, artemisTrace: trace, reportCaptured: Boolean(report), stepsCaptured };
}

const action = process.argv[2];
const id = process.argv[3]?.startsWith('R09-') ? process.argv[3] : undefined;
try {
  if (action === 'status') {
    console.log(`Evidence directory: ${evidenceRoot}`);
    if (existsSync(holdPath)) console.log(`Mutating cases paused: ${holdPath}`);
    if (!existsSync(evidenceRoot)) console.log('No attempts recorded.');
    else {
      const attempts = readdirSync(evidenceRoot).filter(name => name.endsWith('.json') && /-R09-\d\dP?\.json$/.test(name));
      if (!attempts.length) console.log('No attempts recorded.');
      for (const name of attempts.sort()) {
        const record = JSON.parse(readFileSync(join(evidenceRoot, name), 'utf8'));
        console.log(`${record.case} ${record.startedAt} ${record.finalVerdict || record.verdict} exit=${record.commandExitCode ?? 'unknown'} ${record.sessionId || ''}`.trim());
      }
    }
  } else if (action === 'verify') {
    const device = preflight();
    console.log(JSON.stringify(device, null, 2));
  } else if (action === 'plan') {
    const config = readConfig();
    for (const caseId of id ? [id] : Object.keys(cases)) {
      if (!cases[caseId]) throw new Error(`Unknown case ${caseId}`);
      console.log(`\n${caseId} [${cases[caseId].profile}]`);
      try { console.log(render(caseId, config)); }
      catch (error) { console.log(`Needs input: ${error.message}`); }
    }
  } else if (action === 'run') {
    if (!id || !cases[id]) throw new Error('Specify one valid R09 case.');
    if (!process.argv.includes('--execute')) throw new Error('Add --execute after reviewing the plan and the phone state.');
    if (existsSync(holdPath) && !['R09-01', 'R09-02', 'R09-09'].includes(id)) {
      throw new Error(`Mutating R09 cases are paused by ${holdPath}. Reconcile the pending receipt and operating day before removing this hold.`);
    }
    const artemis = argValue('--artemis');
    if (!artemis || !existsSync(resolve(artemis, 'pyproject.toml'))) throw new Error('--artemis must point to an Artemis checkout with pyproject.toml.');
    if (!hasArtemisCredential(artemis)) throw new Error('Artemis needs a model provider key in its local .env. Do not put the key in this test config.');
    const prompt = render(id, readConfig());
    const device = preflight();
    mkdirSync(evidenceRoot, { recursive: true });
    const prior = readdirSync(evidenceRoot).some(name => name.endsWith(`-${id}.json`));
    if (prior && !process.argv.includes('--rerun')) throw new Error(`${id} has a prior attempt. Review its evidence before using --rerun.`);
    const base = join(evidenceRoot, `${new Date().toISOString().replace(/[:.]/g, '-')}-${id}`);
    const startedAt = new Date().toISOString();
    const record = { case: id, startedAt, device, profile: cases[id].profile,
      verdict: 'IN_PROGRESS', note: 'An attempted action may exist even if the runner exits unexpectedly. Inspect phone and IMS before any rerun.' };
    writeFileSync(`${base}.json`, JSON.stringify(record, null, 2) + '\n');
    console.log(`Running ${id} on ${device.model} (${device.serial}); candidate SHA-256 ${device.apkSha256}`);
    const artemisExe = resolve(artemis, '.venv/Scripts/artemis.exe');
    const command = existsSync(artemisExe) ? artemisExe : 'uv';
    const args = existsSync(artemisExe)
      ? ['run', prompt, '--profile', cases[id].profile, '--device-serial', device.serial]
      : ['run', 'artemis', 'run', prompt, '--profile', cases[id].profile, '--device-serial', device.serial];
    const result = spawnSync(command, args,
      { cwd: resolve(artemis), encoding: 'utf8', maxBuffer: 20 * 1024 * 1024, timeout: 30 * 60 * 1000 });
    const shot = spawnSync('adb', ['-s', device.serial, 'exec-out', 'screencap', '-p'], { maxBuffer: 20 * 1024 * 1024 });
    const screenshot = shot.status === 0 && shot.stdout?.length ? shot.stdout : undefined;
    const output = `${result.stdout || ''}\n${result.stderr || ''}`;
    const artemisEvidence = await collectArtemisEvidence(artemis, output, base);
    Object.assign(record, { finishedAt: new Date().toISOString(),
      commandExitCode: result.status, commandError: result.error?.message, signal: result.signal,
      verdict: result.status === 0 && !result.error ? 'PENDING_HUMAN_REVIEW' : 'AUTOMATION_FAILED',
      screenshotCaptured: Boolean(screenshot), ...artemisEvidence,
      note: 'CLI completion does not establish checklist pass. Review transcript, phone, and IMS; record receipt IDs in the checklist.' });
    saveAttempt(base, record, output, screenshot);
    console.log(`Saved ${base}.json and .txt${screenshot ? ' plus .png' : ''}; checklist result remains pending review.`);
    if (result.status !== 0 || result.error) process.exitCode = 1;
  } else {
    usage();
    process.exitCode = 2;
  }
} catch (error) {
  console.error(error.message);
  usage();
  process.exitCode = 2;
}
