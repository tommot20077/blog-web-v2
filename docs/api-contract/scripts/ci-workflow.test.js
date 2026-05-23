// @ts-check
//
// Static safety checks for CI workflow behavior.
// Run: node --test docs/api-contract/scripts/ci-workflow.test.js

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const workflow = fs.readFileSync(
  path.join(__dirname, '..', '..', '..', '.github', 'workflows', 'ci.yml'),
  'utf8'
);

test('E2E test reporter does not fail the whole job when GitHub check publication is flaky', () => {
  assert.match(
    workflow,
    /-\s+name:\s+上傳 E2E 測試報告[\s\S]*?if:\s+always\(\)[\s\S]*?continue-on-error:\s+true[\s\S]*?uses:\s+dorny\/test-reporter@v1/
  );
});
