// @ts-check
//
// Static safety checks for the red E2E runner.
// Run: node --test scripts/run-red-e2e.test.js

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const scriptPath = path.join(__dirname, 'run-red-e2e.sh');
const script = fs.existsSync(scriptPath) ? fs.readFileSync(scriptPath, 'utf8') : '';

test('red E2E runner targets the red-e2e Maven profile and writes logs under logs/', () => {
  assert.match(script, /\.\/mvnw/);
  assert.match(script, /-Pred-e2e/);
  assert.match(script, /logs\/backend-red-e2e\.log/);
  assert.match(script, /tee "\$LOG_FILE"/);
});

test('red E2E runner preserves caller-provided Maven arguments', () => {
  assert.match(script, /"\$@"/);
});
