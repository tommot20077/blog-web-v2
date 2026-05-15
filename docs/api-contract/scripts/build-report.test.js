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

test('classifyOperationDiff: required flip false→true means frontend stricter than backend → low severity', () => {
  // oasdiff(backend, frontend): required.from=backend's value, required.to=frontend's value
  // false→true: frontend marks required, backend says optional → frontend is stricter → safe.
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
  const d = out.driftDetails.find((x) => x.kind === 'parameter-required-change');
  assert.ok(d, 'should record the change');
  assert.strictEqual(d.severity, 'low', 'frontend stricter than backend is low severity');
});

test('classifyOperationDiff: required flip true→false means frontend looser than backend → high severity', () => {
  // true→false: backend requires it, frontend marks optional → frontend may omit a required field → breaking.
  const opDiff = {
    parameters: {
      modified: {
        query: {
          token: { required: { from: true, to: false } },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'parameter-required-change');
  assert.ok(d);
  assert.strictEqual(d.severity, 'high', 'frontend looser than backend is high severity');
});

test('classifyOperationDiff: requestBody required.added means frontend stricter → low severity', () => {
  // required.added = required on right (frontend) but not on left (backend) → frontend stricter.
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
  const d = out.driftDetails.find((x) => x.kind === 'requestBody-required-frontend-stricter');
  assert.ok(d);
  assert.strictEqual(d.severity, 'low');
});

test('classifyOperationDiff: requestBody required.deleted means frontend looser → high severity', () => {
  // required.deleted = required on left (backend) but not on right (frontend) → frontend looser.
  const opDiff = {
    requestBody: {
      content: {
        modified: {
          'application/json': {
            schema: { required: { deleted: ['title'] } },
          },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'requestBody-required-frontend-looser');
  assert.ok(d);
  assert.strictEqual(d.severity, 'high');
});

test('classifyOperationDiff: parameter-deleted of cookie param → low severity (browser handles HttpOnly cookies)', () => {
  const opDiff = {
    parameters: { deleted: { cookie: ['refreshToken'] } },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'parameter-not-emitted');
  assert.ok(d);
  assert.strictEqual(d.severity, 'low', 'cookie param drift is documentation-only');
});

test('classifyOperationDiff: parameter-deleted of query param → medium by default (no spec context)', () => {
  // Without backend-spec context we cannot tell if backend marks it required.
  // Default severity is medium so it surfaces but does not over-alarm.
  const opDiff = {
    parameters: { deleted: { query: ['categorySlug'] } },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'parameter-not-emitted');
  assert.ok(d);
  assert.strictEqual(d.severity, 'medium');
});

test('classifyOperationDiff: parameter-deleted escalates to high when backend marks it required', () => {
  // With backendOpSpec context we can escalate severity for required backend params.
  const opDiff = { parameters: { deleted: { query: ['q'] } } };
  const backendOpSpec = {
    parameters: [{ name: 'q', in: 'query', required: true, schema: { type: 'string' } }],
  };
  const out = classifyOperationDiff(opDiff, { backendOpSpec });
  const d = out.driftDetails.find((x) => x.kind === 'parameter-not-emitted');
  assert.ok(d);
  assert.strictEqual(d.severity, 'high');
});

test('classifyOperationDiff: response properties.added means frontend expects field backend does not return → high severity', () => {
  const opDiff = {
    responses: {
      modified: {
        '200': {
          content: {
            modified: {
              'application/json': {
                schema: { properties: { added: ['extraField'] } },
              },
            },
          },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'response-frontend-expects-missing');
  assert.ok(d);
  assert.strictEqual(d.severity, 'high');
});

test('classifyOperationDiff: response properties.deleted means backend returns field frontend ignores → low severity', () => {
  const opDiff = {
    responses: {
      modified: {
        '200': {
          content: {
            modified: {
              'application/json': {
                schema: { properties: { deleted: ['legacyField'] } },
              },
            },
          },
        },
      },
    },
  };
  const out = classifyOperationDiff(opDiff);
  const d = out.driftDetails.find((x) => x.kind === 'response-backend-only-field');
  assert.ok(d);
  assert.strictEqual(d.severity, 'low');
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
