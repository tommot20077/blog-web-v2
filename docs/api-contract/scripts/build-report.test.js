// @ts-check
//
// TDD tests for build-report.js classifier.
// Run: node --test docs/api-contract/scripts/build-report.test.js

const test = require('node:test');
const assert = require('node:assert');
const { classifyOperationDiff, isNoiseField, buildReport } = require('./build-report');

// --- Noise filter ---

test('isNoiseField: ignores info/tags/operationID/extensions/license/version/description', () => {
  for (const f of ['info', 'tags', 'operationID', 'extensions', 'license', 'version', 'description']) {
    assert.strictEqual(isNoiseField(f), true, `${f} should be noise`);
  }
});

test('isNoiseField: real schema/parameter changes are NOT noise', () => {
  for (const f of ['parameters', 'requestBody', 'responses']) {
    assert.strictEqual(isNoiseField(f), false, `${f} should NOT be noise`);
  }
});

// --- Operation diff classifier ---

test('classifyOperationDiff: pure noise (only extensions/tags/operationID) → returns empty driftDetails', () => {
  const opDiff = {
    extensions: { added: ['x-source'] },
    tags: { deleted: ['article-controller'] },
    operationID: { from: 'getX', to: 'serviceX_getX' },
  };
  const out = classifyOperationDiff(opDiff);
  assert.strictEqual(out.driftDetails.length, 0);
});

test('classifyOperationDiff: content-type only differs by */* vs application/json → noise', () => {
  const opDiff = {
    responses: {
      modified: {
        '200': {
          content: { added: ['application/json'], deleted: ['*/*'] },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  assert.strictEqual(out.driftDetails.length, 0, 'content-type swap alone should be ignored');
});

test('classifyOperationDiff: integer/int32 vs number is reported as low-severity', () => {
  const opDiff = {
    parameters: {
      modified: {
        query: {
          page: { schema: { type: { added: ['number'], deleted: ['integer'] }, format: { from: 'int32', to: '' } } },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  // We expect this to be reported but marked low-severity.
  assert.ok(out.driftDetails.some((d) => d.severity === 'low'), 'int/number drift exists as low severity');
});

test('classifyOperationDiff: parameter required field change is high-severity', () => {
  const opDiff = {
    parameters: {
      modified: {
        query: {
          page: { required: { from: false, to: true } },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  assert.ok(out.driftDetails.some((d) => d.severity === 'high'), 'required flip is high severity');
});

test('classifyOperationDiff: requestBody required fields added is high severity', () => {
  const opDiff = {
    requestBody: {
      content: {
        modified: {
          'application/json': {
            schema: { required: { added: ['summary', 'coverImageUrl'] } },
          },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  assert.ok(out.driftDetails.some((d) => d.severity === 'high'), 'new required fields are high severity');
});

test('classifyOperationDiff: parameter deletion → high severity', () => {
  const opDiff = {
    parameters: { deleted: { query: ['categorySlug'] } },
  };
  const out = classifyOperationDiff(opDiff);
  assert.ok(out.driftDetails.some((d) => d.severity === 'high' && d.kind === 'parameter-deleted'));
});

// --- Top-level buildReport sanity ---

test('buildReport: produces markdown with all section headers', () => {
  const md = buildReport({
    oasdiffReal: { paths: {} },
    oasdiffMock: { paths: {} },
    antiPattern: [],
    warnings: [],
    unwrapped: [],
    backendOps: [],
    frontendOps: [],
    previouslyDeferred: [],
  });
  assert.ok(md.includes('# API Contract Gap Report'));
  assert.ok(md.includes('## Summary'));
  assert.ok(md.includes('## Required Fixes'));
  assert.ok(md.includes('## Schema Drift'));
  assert.ok(md.includes('## Backend-only Endpoints'));
  assert.ok(md.includes('## Frontend-only Endpoints'));
  assert.ok(md.includes('## Anti-patterns'));
  assert.ok(md.includes('## Generator Warnings'));
  assert.ok(md.includes('## Resolved Since 2026-05-09'));
});

test('buildReport: backend-only path matching a previously-deferred feature lands in Resolved section', () => {
  const md = buildReport({
    oasdiffReal: {},
    oasdiffMock: {},
    antiPattern: [],
    warnings: [],
    unwrapped: [],
    backendOps: ['GET /api/v1/articles/{x}/bookmark'],
    frontendOps: ['GET /api/v1/articles/{x}/bookmark'],
    previouslyDeferred: ['bookmark'],
  });
  // The path contains "bookmark" and the frontend now has it → goes to Resolved
  const resolvedSection = md.split('## Resolved Since 2026-05-09')[1] || '';
  assert.ok(resolvedSection.includes('bookmark'), 'bookmark should appear in Resolved section');
});

test('buildReport: deterministic — same inputs produce identical output', () => {
  const inputs = {
    oasdiffReal: { paths: { deleted: ['/api/v1/admin/x'] } },
    oasdiffMock: {},
    antiPattern: [{ file: 'src/components/X.vue', line: 10, method: 'get', urlSnippet: '/y', category: 'component-direct' }],
    warnings: [{ file: 'src/api/real/x.ts', line: 5, function: 'foo', code: 'unresolved-path' }],
    unwrapped: [],
    backendOps: ['DELETE /api/v1/admin/x'],
    frontendOps: [],
    previouslyDeferred: [],
  };
  const a = buildReport(inputs);
  const b = buildReport(JSON.parse(JSON.stringify(inputs)));
  assert.strictEqual(a, b);
});
