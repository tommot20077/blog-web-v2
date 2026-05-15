// @ts-check
//
// Static safety checks for run-audit.ps1.
// Run: node --test docs/api-contract/scripts/run-audit.test.js

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const ps1 = fs.readFileSync(path.join(__dirname, 'run-audit.ps1'), 'utf8');

test('PowerShell audit invokes the frontend generator directly so CLI options are preserved', () => {
  assert.match(ps1, /npx\s+tsx\s+scripts\/generate-frontend-openapi\.ts/);
  assert.doesNotMatch(ps1, /npm\s+run\s+audit:openapi\s+--/);
});

test('PowerShell audit checks backend readiness instead of aggregate health', () => {
  assert.match(ps1, /\$BackendBase\/actuator\/health\/readiness/);
  assert.doesNotMatch(ps1, /\$BackendBase\/actuator\/health"\)/);
});
