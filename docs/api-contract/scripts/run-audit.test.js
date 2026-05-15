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

test('PowerShell audit starts backend when readiness is down and stops only its own process', () => {
  assert.match(ps1, /Start-BackendIfNeeded/);
  assert.match(ps1, /Start-Process[\s\S]+spring-boot:run/);
  assert.match(ps1, /\$script:BackendStartedByScript\s*=\s*\$true/);
  assert.match(ps1, /finally\s*\{[\s\S]*Stop-StartedBackend[\s\S]*\}/);
});

test('PowerShell backend cleanup stops the Maven and Spring Boot child process tree', () => {
  assert.match(ps1, /Stop-ProcessTree/);
  assert.match(ps1, /Win32_Process/);
  assert.match(ps1, /ParentProcessId/);
  assert.match(ps1, /Stop-ProcessTree\s+\$script:BackendProcess\.Id/);
});

test('PowerShell audit enforces generator warning ratio before diffing', () => {
  assert.match(ps1, /\$MaxGeneratorWarningRatio/);
  assert.match(ps1, /Assert-GeneratorWarningRatio/);
  assert.match(ps1, /frontend-generator-warnings\.json/);
  assert.match(ps1, /warning ratio[\s\S]+exceeds/);
});

test('PowerShell audit falls back to npm oasdiff-js when Docker oasdiff fails', () => {
  assert.match(ps1, /Invoke-Oasdiff/);
  assert.match(ps1, /docker\s+run[\s\S]+tufin\/oasdiff/);
  assert.match(ps1, /npx[\s\S]+@oasdiff-js\/oasdiff-js/);
});

test('Shell audit has the same lifecycle, warning-ratio, and oasdiff fallback gates', () => {
  assert.match(sh, /start_backend_if_needed/);
  assert.match(sh, /spring-boot:run/);
  assert.match(sh, /trap cleanup_backend EXIT/);
  assert.match(sh, /check_generator_warning_ratio/);
  assert.match(sh, /run_oasdiff/);
  assert.match(sh, /@oasdiff-js\/oasdiff-js/);
});

test('bash audit checks backend readiness instead of aggregate health', () => {
  assert.match(sh, /\$BACKEND_BASE\/actuator\/health\/readiness/);
  assert.doesNotMatch(sh, /\$BACKEND_BASE\/actuator\/health"/);
});

test('audit runners pass their own script identity to the report builder', () => {
  assert.match(ps1, /\$env:AUDIT_SCRIPT\s*=\s*'run-audit\.ps1'/);
  assert.match(sh, /AUDIT_SCRIPT=run-audit\.sh\s+node docs\/api-contract\/scripts\/build-report\.js/);
});
