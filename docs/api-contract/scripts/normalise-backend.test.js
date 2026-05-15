// @ts-check
//
// TDD test suite for normalise-backend.js
// Run: node --test docs/api-contract/scripts/normalise-backend.test.js
//
// Tests cover:
//   1. ApiResponse<X> -> response schema becomes X
//   2. ApiResponseVoid -> response schema becomes null
//   3. Non-envelope response -> kept, logged into unwrappedResponses
//   4. Multi-layer ref ApiResponse<PageResultX> -> outer envelope stripped, inner PageResultX preserved
//   5. Unreferenced ApiResponse* entries removed from components.schemas

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { normalise } = require('./normalise-backend');

function makeSpec(extra = {}) {
  return {
    openapi: '3.1.0',
    info: { title: 't', version: '0' },
    paths: {},
    components: { schemas: {} },
    ...extra,
  };
}

test('1. ApiResponse<X>: response schema becomes properties.data', () => {
  const spec = makeSpec({
    paths: {
      '/x': {
        get: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/ApiResponseFoo' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        Foo: { type: 'object', properties: { id: { type: 'string' } } },
        ApiResponseFoo: {
          type: 'object',
          properties: {
            code: { type: 'string' },
            data: { $ref: '#/components/schemas/Foo' },
          },
        },
      },
    },
  });

  const { spec: out, unwrappedResponses } = normalise(spec);

  assert.deepStrictEqual(
    out.paths['/x'].get.responses['200'].content['application/json'].schema,
    { $ref: '#/components/schemas/Foo' },
  );
  assert.strictEqual(unwrappedResponses.length, 0);
});

test('2. ApiResponseVoid: response schema becomes null', () => {
  const spec = makeSpec({
    paths: {
      '/v': {
        delete: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/ApiResponseVoid' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        ApiResponseVoid: {
          type: 'object',
          properties: {
            code: { type: 'string' },
            data: { type: 'object' },
          },
        },
      },
    },
  });

  const { spec: out } = normalise(spec);
  assert.deepStrictEqual(
    out.paths['/v'].delete.responses['200'].content['application/json'].schema,
    { type: 'null' },
  );
});

test('2b. ApiResponse without properties.data becomes null instead of unwrapped drift', () => {
  const spec = makeSpec({
    paths: {
      '/void-like': {
        post: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/ApiResponseVoid' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        ApiResponseVoid: {
          type: 'object',
          properties: {
            code: { type: 'string' },
          },
        },
      },
    },
  });

  const { spec: out, unwrappedResponses } = normalise(spec);
  assert.deepStrictEqual(
    out.paths['/void-like'].post.responses['200'].content['application/json'].schema,
    { type: 'null' },
  );
  assert.strictEqual(unwrappedResponses.length, 0);
});

test('3. Non-envelope response is preserved and recorded in unwrappedResponses', () => {
  const spec = makeSpec({
    paths: {
      '/blob': {
        get: {
          responses: {
            '200': {
              content: { 'application/octet-stream': { schema: { type: 'string', format: 'binary' } } },
            },
          },
        },
      },
    },
  });

  const { spec: out, unwrappedResponses } = normalise(spec);
  assert.deepStrictEqual(
    out.paths['/blob'].get.responses['200'].content['application/octet-stream'].schema,
    { type: 'string', format: 'binary' },
  );
  assert.strictEqual(unwrappedResponses.length, 1);
  assert.match(unwrappedResponses[0].path, /^\/blob$/);
  assert.strictEqual(unwrappedResponses[0].method, 'get');
  assert.strictEqual(unwrappedResponses[0].status, '200');
});

test('4. Multi-layer: ApiResponse<PageResultX> -> response becomes PageResultX', () => {
  const spec = makeSpec({
    paths: {
      '/list': {
        get: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/ApiResponsePageResultArticle' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        Article: { type: 'object' },
        PageResultArticle: {
          type: 'object',
          properties: {
            items: { type: 'array', items: { $ref: '#/components/schemas/Article' } },
            total: { type: 'integer' },
          },
        },
        ApiResponsePageResultArticle: {
          type: 'object',
          properties: {
            code: { type: 'string' },
            data: { $ref: '#/components/schemas/PageResultArticle' },
          },
        },
      },
    },
  });

  const { spec: out } = normalise(spec);
  assert.deepStrictEqual(
    out.paths['/list'].get.responses['200'].content['application/json'].schema,
    { $ref: '#/components/schemas/PageResultArticle' },
  );
});

test('5. Unreferenced ApiResponse* schemas are removed from components.schemas', () => {
  const spec = makeSpec({
    paths: {
      '/x': {
        get: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/ApiResponseFoo' } } },
            },
          },
        },
      },
    },
    components: {
      schemas: {
        Foo: { type: 'object' },
        ApiResponseFoo: { type: 'object', properties: { data: { $ref: '#/components/schemas/Foo' } } },
        ApiResponseBar: { type: 'object', properties: { data: { type: 'string' } } },
      },
    },
  });

  const { spec: out } = normalise(spec);
  assert.ok(out.components.schemas.Foo, 'Foo should be preserved');
  assert.ok(!('ApiResponseFoo' in out.components.schemas), 'ApiResponseFoo should be removed');
  assert.ok(!('ApiResponseBar' in out.components.schemas), 'unused ApiResponseBar should also be removed');
});

test('6. Inline (non-$ref) ApiResponse-shaped schema is also unwrapped via name match (fallback: noop)', () => {
  // If schema is not a $ref, normaliser does nothing.
  const spec = makeSpec({
    paths: {
      '/inline': {
        get: {
          responses: {
            '200': {
              content: { 'application/json': { schema: { type: 'object', properties: { foo: { type: 'string' } } } } },
            },
          },
        },
      },
    },
  });

  const { spec: out, unwrappedResponses } = normalise(spec);
  assert.deepStrictEqual(
    out.paths['/inline'].get.responses['200'].content['application/json'].schema,
    { type: 'object', properties: { foo: { type: 'string' } } },
  );
  assert.strictEqual(unwrappedResponses.length, 1, 'inline response is logged as unwrapped');
});

test('7. CLI success output goes to stdout so PowerShell audit script does not treat it as an error', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'normalise-backend-cli-'));
  const input = path.join(dir, 'input.json');
  const output = path.join(dir, 'output.json');
  const unwrapped = path.join(dir, 'unwrapped.json');
  fs.writeFileSync(input, JSON.stringify(makeSpec(), null, 2));

  const result = spawnSync(
    process.execPath,
    [path.join(__dirname, 'normalise-backend.js'), input, output, unwrapped],
    { encoding: 'utf8' },
  );

  assert.strictEqual(result.status, 0, result.stderr);
  assert.match(result.stdout, /Wrote .*output\.json; unwrapped responses: 0/);
  assert.strictEqual(result.stderr, '');
  assert.ok(fs.existsSync(output));
  assert.ok(fs.existsSync(unwrapped));
});
