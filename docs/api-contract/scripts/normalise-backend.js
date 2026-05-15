// @ts-check
//
// normalise-backend.js
// 拆掉 backend OpenAPI 的 ApiResponse<T> 信封，讓 response schema 與前端 service
// 解包後的型別直接對齊（apiClient.ts response interceptor 已自動取 .data）。
//
// Usage as library:
//   const { normalise } = require('./normalise-backend');
//   const { spec, unwrappedResponses } = normalise(rawSpec);
//
// Usage as CLI:
//   node normalise-backend.js <input.json> <output.json> <unwrapped-log.json>

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const APIRESPONSE_PREFIX = 'ApiResponse';

/**
 * 從 $ref 字串解析 schema 名稱。
 * e.g. "#/components/schemas/Foo" -> "Foo"
 */
function refToName(ref) {
  if (typeof ref !== 'string') return null;
  const m = ref.match(/^#\/components\/schemas\/(.+)$/);
  return m ? m[1] : null;
}

/**
 * 收集 spec 中所有 $ref 引用的 schema 名稱。
 */
function collectRefs(node, acc) {
  if (!node || typeof node !== 'object') return;
  if (Array.isArray(node)) {
    for (const item of node) collectRefs(item, acc);
    return;
  }
  if (typeof node.$ref === 'string') {
    const name = refToName(node.$ref);
    if (name) acc.add(name);
  }
  for (const key of Object.keys(node)) {
    if (key === '$ref') continue;
    collectRefs(node[key], acc);
  }
}

/**
 * 主流程：對每個 response.content.*.schema 做信封拆封；移除未被引用的 ApiResponse*。
 * 不會 mutate 輸入 spec。
 */
function normalise(inputSpec) {
  // Deep clone to avoid mutating caller's spec.
  const spec = JSON.parse(JSON.stringify(inputSpec));
  const schemas = (spec.components && spec.components.schemas) || {};
  const unwrappedResponses = [];

  const paths = spec.paths || {};
  for (const [pathStr, pathItem] of Object.entries(paths)) {
    if (!pathItem || typeof pathItem !== 'object') continue;
    for (const method of Object.keys(pathItem)) {
      const op = pathItem[method];
      if (!op || typeof op !== 'object' || !op.responses) continue;
      for (const [status, resp] of Object.entries(op.responses)) {
        if (!resp || typeof resp !== 'object' || !resp.content) continue;
        for (const [mime, mediaObj] of Object.entries(resp.content)) {
          if (!mediaObj || typeof mediaObj !== 'object' || !mediaObj.schema) continue;
          const schema = mediaObj.schema;
          let envName = null;
          if (schema.$ref) {
            const name = refToName(schema.$ref);
            if (name && name.startsWith(APIRESPONSE_PREFIX)) envName = name;
          }
          if (envName) {
            const target = schemas[envName];
            if (target && target.properties && Object.prototype.hasOwnProperty.call(target.properties, 'data')) {
              mediaObj.schema = JSON.parse(JSON.stringify(target.properties.data));
            } else {
              unwrappedResponses.push({
                path: pathStr,
                method,
                status,
                mime,
                reason: `ApiResponse target missing properties.data: ${envName}`,
              });
            }
          } else {
            unwrappedResponses.push({
              path: pathStr,
              method,
              status,
              mime,
              reason: 'response schema is not a $ref to an ApiResponse* schema',
            });
          }
        }
      }
    }
  }

  // Remove any ApiResponse* schema entries no longer referenced anywhere.
  const refsAfter = new Set();
  collectRefs(spec.paths, refsAfter);
  collectRefs(spec.components && spec.components.schemas, refsAfter);
  if (spec.components && spec.components.schemas) {
    for (const name of Object.keys(spec.components.schemas)) {
      if (name.startsWith(APIRESPONSE_PREFIX) && !refsAfter.has(name)) {
        delete spec.components.schemas[name];
      }
    }
  }

  return { spec, unwrappedResponses };
}

module.exports = { normalise };

// CLI entry
if (require.main === module) {
  const [inFile, outFile, logFile] = process.argv.slice(2);
  if (!inFile || !outFile) {
    console.error('Usage: node normalise-backend.js <input.json> <output.json> [unwrapped-log.json]');
    process.exit(1);
  }
  const rawSpec = JSON.parse(fs.readFileSync(inFile, 'utf8'));
  const { spec, unwrappedResponses } = normalise(rawSpec);
  fs.writeFileSync(outFile, JSON.stringify(spec, null, 2));
  if (logFile) fs.writeFileSync(logFile, JSON.stringify(unwrappedResponses, null, 2));
  console.log(`Wrote ${outFile}; unwrapped responses: ${unwrappedResponses.length}`);
}
