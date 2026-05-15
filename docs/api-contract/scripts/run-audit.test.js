// @ts-check
//
// Static safety checks for run-audit runners.
// Run: node --test docs/api-contract/scripts/run-audit.test.js

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const ps1 = fs.readFileSync(path.join(__dirname, 'run-audit.ps1'), 'utf8');
const sh = fs.readFileSync(path.join(__dirname, 'run-audit.sh'), 'utf8');

test('PowerShell audit invokes the frontend generator directly so CLI options are preserved', () => {
  assert.match(ps1, /npx\s+tsx\s+scripts\/generate-frontend-openapi\.ts/);
  assert.doesNotMatch(ps1, /npm\s+run\s+audit:openapi\s+--/);
});

test('PowerShell audit checks backend readiness instead of aggregate health', () => {
  assert.match(ps1, /\$BackendBase\/actuator\/health\/readiness/);
  assert.doesNotMatch(ps1, /\$BackendBase\/actuator\/health"\)/);
});

test('bash audit checks backend readiness instead of aggregate health', () => {
  assert.match(sh, /\$BACKEND_BASE\/actuator\/health\/readiness/);
  assert.doesNotMatch(sh, /\$BACKEND_BASE\/actuator\/health"/);
});

test('audit runners pass their own script identity to the report builder', () => {
  assert.match(ps1, /\$env:AUDIT_SCRIPT\s*=\s*'run-audit\.ps1'/);
  assert.match(sh, /AUDIT_SCRIPT=run-audit\.sh\s+node docs\/api-contract\/scripts\/build-report\.js/);
});
