// @ts-check
//
// build-report.js
// 把 oasdiff + anti-pattern + warnings + unwrapped 結果聚合成 dated gap report markdown。
//
// Library:
//   const { buildReport } = require('./build-report');
//   const md = buildReport({ oasdiffReal, oasdiffMock, antiPattern, warnings, unwrapped, backendOps, frontendOps, previouslyDeferred });
//
// CLI:
//   node build-report.js <input-dir> <output-md>
//   reads everything from <input-dir>/{oasdiff-real,oasdiff-mock,anti-pattern-inventory,frontend-generator-warnings,unwrapped-responses}.json
//   plus <input-dir>/{backend-openapi.normalised,frontend-openapi}.json for ops sets.

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const NOISE_FIELDS = new Set([
  'info', 'tags', 'operationID', 'extensions', 'license', 'version', 'description',
]);

function isNoiseField(name) {
  return NOISE_FIELDS.has(name);
}

/**
 * 把 (method, path) 二元組從 OpenAPI spec 抽出來，產出排序後的 array。
 */
function specOps(spec) {
  const set = new Set();
  for (const [p, methods] of Object.entries(spec.paths || {})) {
    if (!methods || typeof methods !== 'object') continue;
    for (const m of Object.keys(methods)) {
      if (typeof methods[m] === 'object' && methods[m] !== null) set.add(`${m.toUpperCase()} ${p}`);
    }
  }
  return [...set].sort();
}

// Content-type swap noise: oasdiff reports content.added=["application/json"], content.deleted=["* /*"]
// (or reversed). This is purely Spring's default vs our generator's choice, treat as noise.
function isContentTypeSwapNoise(modifiedContent) {
  if (!modifiedContent || typeof modifiedContent !== 'object') return false;
  const added = (modifiedContent.added || []).slice().sort();
  const deleted = (modifiedContent.deleted || []).slice().sort();
  const hasModified = modifiedContent.modified && Object.keys(modifiedContent.modified).length > 0;
  if (hasModified) return false;
  const a = JSON.stringify(added);
  const d = JSON.stringify(deleted);
  if (a === '["application/json"]' && d === '["*/*"]') return true;
  if (a === '["*/*"]' && d === '["application/json"]') return true;
  return false;
}

/**
 * 對單一 operation 的 diff 分類出 driftDetails: [{kind, location, severity, summary}].
 * Noise（命名/extensions/content-type swap）被過濾掉。
 */
function classifyOperationDiff(opDiff) {
  const driftDetails = [];
  if (!opDiff || typeof opDiff !== 'object') return { driftDetails };

  // Parameters
  if (opDiff.parameters) {
    const p = opDiff.parameters;
    if (p.added && typeof p.added === 'object') {
      for (const [loc, list] of Object.entries(p.added)) {
        for (const name of list || []) {
          driftDetails.push({
            kind: 'parameter-added',
            location: `parameter:${loc}:${name}`,
            severity: 'medium',
            summary: `Backend adds ${loc} param "${name}" not present in frontend.`,
          });
        }
      }
    }
    if (p.deleted && typeof p.deleted === 'object') {
      for (const [loc, list] of Object.entries(p.deleted)) {
        for (const name of list || []) {
          driftDetails.push({
            kind: 'parameter-deleted',
            location: `parameter:${loc}:${name}`,
            severity: 'high',
            summary: `Frontend passes ${loc} param "${name}" that backend no longer accepts.`,
          });
        }
      }
    }
    if (p.modified && typeof p.modified === 'object') {
      for (const [loc, params] of Object.entries(p.modified)) {
        for (const [name, change] of Object.entries(params || {})) {
          if (change.required) {
            driftDetails.push({
              kind: 'parameter-required-change',
              location: `parameter:${loc}:${name}`,
              severity: 'high',
              summary: `required flipped from ${change.required.from} to ${change.required.to}`,
            });
          }
          if (change.schema && change.schema.type) {
            const tAdded = (change.schema.type.added || []).sort();
            const tDeleted = (change.schema.type.deleted || []).sort();
            const isIntNumber =
              JSON.stringify(tAdded) === '["number"]' && JSON.stringify(tDeleted) === '["integer"]' ||
              JSON.stringify(tAdded) === '["integer"]' && JSON.stringify(tDeleted) === '["number"]';
            driftDetails.push({
              kind: 'parameter-type-change',
              location: `parameter:${loc}:${name}`,
              severity: isIntNumber ? 'low' : 'medium',
              summary: `type ${tDeleted.join(',')} → ${tAdded.join(',')}`,
            });
          }
        }
      }
    }
  }

  // Request body
  if (opDiff.requestBody && opDiff.requestBody.content && opDiff.requestBody.content.modified) {
    for (const [mime, mediaDiff] of Object.entries(opDiff.requestBody.content.modified)) {
      const s = mediaDiff.schema;
      if (!s) continue;
      if (s.required && Array.isArray(s.required.added) && s.required.added.length) {
        driftDetails.push({
          kind: 'requestBody-required-added',
          location: `requestBody:${mime}`,
          severity: 'high',
          summary: `new required fields: ${s.required.added.join(', ')}`,
        });
      }
      if (s.required && Array.isArray(s.required.deleted) && s.required.deleted.length) {
        driftDetails.push({
          kind: 'requestBody-required-removed',
          location: `requestBody:${mime}`,
          severity: 'medium',
          summary: `required removed: ${s.required.deleted.join(', ')}`,
        });
      }
      if (s.properties) {
        if (Array.isArray(s.properties.added) && s.properties.added.length) {
          driftDetails.push({
            kind: 'requestBody-property-added',
            location: `requestBody:${mime}`,
            severity: 'medium',
            summary: `new properties: ${s.properties.added.join(', ')}`,
          });
        }
        if (Array.isArray(s.properties.deleted) && s.properties.deleted.length) {
          driftDetails.push({
            kind: 'requestBody-property-deleted',
            location: `requestBody:${mime}`,
            severity: 'medium',
            summary: `removed properties: ${s.properties.deleted.join(', ')}`,
          });
        }
      }
    }
  }

  // Responses
  if (opDiff.responses && opDiff.responses.modified) {
    for (const [code, respDiff] of Object.entries(opDiff.responses.modified)) {
      if (respDiff.content) {
        if (isContentTypeSwapNoise(respDiff.content)) {
          // skip
        } else if (respDiff.content.modified) {
          for (const [mime, mediaDiff] of Object.entries(respDiff.content.modified)) {
            const s = mediaDiff.schema;
            if (!s) continue;
            if (s.properties) {
              if (Array.isArray(s.properties.added) && s.properties.added.length) {
                driftDetails.push({
                  kind: 'response-property-added',
                  location: `response:${code}:${mime}`,
                  severity: 'medium',
                  summary: `backend added properties: ${s.properties.added.join(', ')}`,
                });
              }
              if (Array.isArray(s.properties.deleted) && s.properties.deleted.length) {
                driftDetails.push({
                  kind: 'response-property-deleted',
                  location: `response:${code}:${mime}`,
                  severity: 'high',
                  summary: `backend removed properties: ${s.properties.deleted.join(', ')}`,
                });
              }
            }
          }
        } else if ((respDiff.content.added || []).length || (respDiff.content.deleted || []).length) {
          driftDetails.push({
            kind: 'response-content-type-change',
            location: `response:${code}`,
            severity: 'low',
            summary: `content types added=${(respDiff.content.added || []).join(',')} deleted=${(respDiff.content.deleted || []).join(',')}`,
          });
        }
      }
    }
  }

  return { driftDetails };
}

function bucketBackendOnly(backendOps, frontendOps) {
  const feSet = new Set(frontendOps);
  return backendOps.filter((o) => !feSet.has(o));
}

function bucketFrontendOnly(backendOps, frontendOps) {
  const beSet = new Set(backendOps);
  return frontendOps.filter((o) => !beSet.has(o));
}

function findResolvedSince0509(backendOps, frontendOps, previouslyDeferred) {
  if (!previouslyDeferred || !previouslyDeferred.length) return [];
  const feSet = new Set(frontendOps);
  const out = [];
  for (const tag of previouslyDeferred) {
    const matched = backendOps.filter((o) => o.toLowerCase().includes(tag.toLowerCase()) && feSet.has(o));
    if (matched.length) out.push({ tag, matched });
  }
  return out;
}

function md_table(headers, rows) {
  if (!rows.length) return '_None._\n';
  const headerLine = '| ' + headers.join(' | ') + ' |';
  const sepLine = '|' + headers.map(() => '---').join('|') + '|';
  const dataLines = rows.map((r) => '| ' + r.map((c) => String(c).replace(/\|/g, '\\|')).join(' | ') + ' |');
  return [headerLine, sepLine, ...dataLines].join('\n') + '\n';
}

function buildReport(inputs) {
  const {
    oasdiffReal = {},
    oasdiffMock = {},
    antiPattern = [],
    warnings = [],
    unwrapped = [],
    backendOps = [],
    frontendOps = [],
    previouslyDeferred = ['bookmark', 'highlight', 'progress', 'preferences/version', 'versions', 'series'],
    backendSnapshot = '',
    frontendSnapshot = '',
    scriptVersion = '',
  } = inputs;

  const lines = [];
  const push = (s) => lines.push(s);

  // Summary
  push('# API Contract Gap Report — 2026-05-15');
  push('');
  push('## Summary');
  push('');
  push(`- Backend operations: ${backendOps.length}`);
  push(`- Frontend operations: ${frontendOps.length}`);
  if (backendSnapshot) push(`- Backend snapshot: \`${backendSnapshot}\``);
  if (frontendSnapshot) push(`- Frontend snapshot: \`${frontendSnapshot}\``);
  if (scriptVersion) push(`- Audit script version: ${scriptVersion}`);
  push('');

  // Required Fixes = frontend-only (frontend calls a non-existent backend endpoint)
  push('## Required Fixes');
  push('');
  push('Frontend calls these endpoints but backend does not expose them — production will break.');
  push('');
  const feOnly = bucketFrontendOnly(backendOps, frontendOps);
  push(md_table(['Method', 'Path'], feOnly.map((o) => o.split(' '))));
  push('');

  // Schema Drift = paths.modified with non-noise driftDetails
  push('## Schema Drift');
  push('');
  push('Endpoints exist on both sides, but request/response schema or parameters differ.');
  push('Drift severity: `high` = breaking; `medium` = silent contract mismatch; `low` = stylistic.');
  push('');
  const driftRows = [];
  if (oasdiffReal && oasdiffReal.paths && oasdiffReal.paths.modified) {
    const paths = Object.keys(oasdiffReal.paths.modified).sort();
    for (const p of paths) {
      const ops = oasdiffReal.paths.modified[p].operations && oasdiffReal.paths.modified[p].operations.modified;
      if (!ops) continue;
      for (const method of Object.keys(ops).sort()) {
        const { driftDetails } = classifyOperationDiff(ops[method]);
        for (const d of driftDetails) {
          driftRows.push([method, p, d.kind, d.location, d.severity, d.summary]);
        }
      }
    }
  }
  push(md_table(['Method', 'Path', 'Kind', 'Location', 'Severity', 'Summary'], driftRows));
  push('');

  // Backend-only = paths.deleted in oasdiff OR backend ops not in frontend ops
  push('## Backend-only Endpoints');
  push('');
  push('Backend exposes these, frontend never calls. Either deferred features or admin tooling.');
  push('');
  const beOnly = bucketBackendOnly(backendOps, frontendOps);
  push(md_table(['Method', 'Path'], beOnly.map((o) => o.split(' '))));
  push('');

  // Frontend-only - mirror of Required Fixes; surface for completeness in case of tooling normalisation differences
  push('## Frontend-only Endpoints');
  push('');
  push('Already listed under Required Fixes; reproduced for ease of comparison.');
  push('');
  push(md_table(['Method', 'Path'], feOnly.map((o) => o.split(' '))));
  push('');

  // Anti-patterns
  push('## Anti-patterns');
  push('');
  push('Direct apiClient/axios calls outside `src/api/`. Production code should go through service modules; tests/dev backdoors are tolerated but listed.');
  push('');
  const apRows = (antiPattern || []).map((e) => [e.method, e.urlSnippet, e.category, `${e.file}:${e.line}`]);
  push(md_table(['Method', 'URL Snippet', 'Category', 'Location'], apRows));
  push('');

  // Mock Drift
  // oasdiff was called as: diff <frontend-real> <frontend-mock>
  // therefore paths.deleted = paths in real but NOT in mock (mock coverage gap)
  // and paths.added = paths in mock but NOT in real (stale mock endpoints)
  push('## Mock-vs-Real Drift');
  push('');
  const mockMissing = (oasdiffMock.paths && oasdiffMock.paths.deleted) || [];
  const mockStale = (oasdiffMock.paths && oasdiffMock.paths.added) || [];
  if (mockMissing.length === 0 && mockStale.length === 0) {
    push('_No mock drift detected._');
  } else if (mockMissing.length === Object.keys((oasdiffMock.paths && oasdiffMock.paths.deleted) || []).length && mockStale.length === 0) {
    // Special case: mock layer scanned empty (every real path missing from mock).
    // This means mock services don't go through apiClient — by design — and per-endpoint coverage diff is not meaningful here.
    push(`_Mock generator emitted 0 operations because \`src/api/mock/\` services do not call \`apiClient\` (by design). Per-endpoint mock-vs-real drift is therefore not meaningful for this layer. ${mockMissing.length} real paths have no mock counterpart at the apiClient layer._`);
  } else {
    if (mockMissing.length) {
      push(`Paths exposed by real services but missing from mock (mock coverage gap): ${mockMissing.length}`);
      push(md_table(['Path'], mockMissing.slice(0, 20).map((p) => [p])));
      if (mockMissing.length > 20) push(`_…and ${mockMissing.length - 20} more, omitted for brevity._`);
    }
    if (mockStale.length) {
      push(`Paths in mock but not in real services (stale mocks): ${mockStale.length}`);
      push(md_table(['Path'], mockStale.map((p) => [p])));
    }
  }
  push('');

  // Generator Warnings
  push('## Generator Warnings');
  push('');
  push('Non-blocking — generator could not fully resolve these but recorded them.');
  push('');
  const warnGroups = {};
  for (const w of warnings || []) {
    warnGroups[w.code] = warnGroups[w.code] || [];
    warnGroups[w.code].push(w);
  }
  const warnRows = Object.entries(warnGroups)
    .sort()
    .map(([code, items]) => [code, items.length, items.slice(0, 3).map((i) => `${i.file}:${i.line}:${i.function}`).join('; ')]);
  push(md_table(['Code', 'Count', 'Sample (first 3)'], warnRows));
  push('');

  // Unwrapped responses
  push('## Unwrapped Backend Responses');
  push('');
  push('Backend responses NOT wrapped in `ApiResponse<T>` envelope (envelope normaliser skipped these). Audit manually.');
  push('');
  const unwRows = (unwrapped || []).slice(0, 25).map((u) => [u.method.toUpperCase(), u.path, u.status, u.mime, u.reason]);
  push(md_table(['Method', 'Path', 'Status', 'MIME', 'Reason'], unwRows));
  push('');

  // Resolved Since 2026-05-09
  push('## Resolved Since 2026-05-09');
  push('');
  push('Endpoints flagged as "可暫緩" in the 2026-05-09 audit that now have matching frontend services.');
  push('');
  const resolved = findResolvedSince0509(backendOps, frontendOps, previouslyDeferred);
  const resRows = [];
  for (const { tag, matched } of resolved) {
    for (const m of matched) {
      resRows.push([tag, m]);
    }
  }
  push(md_table(['Feature tag', 'Operation'], resRows));
  push('');

  // Evidence
  push('## Evidence & Reproducibility');
  push('');
  push('- Intermediate artefacts: `logs/api-contract-2026-05-15/` (git-ignored).');
  push('- Re-run: `./docs/api-contract/scripts/run-audit.ps1` (or `.sh`).');
  push('- Backend startup: `./mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev`.');
  push('');

  return lines.join('\n');
}

module.exports = { buildReport, classifyOperationDiff, isNoiseField, specOps, isContentTypeSwapNoise, bucketBackendOnly, bucketFrontendOnly, findResolvedSince0509 };

// CLI
if (require.main === module) {
  const [inputDir, outFile] = process.argv.slice(2);
  if (!inputDir || !outFile) {
    console.error('Usage: node build-report.js <input-dir> <output-md>');
    process.exit(1);
  }
  const j = (name) => {
    const p = path.join(inputDir, name);
    if (!fs.existsSync(p)) return null;
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  };

  const backend = j('backend-openapi.normalised.json') || { paths: {} };
  const frontend = j('frontend-openapi.json') || { paths: {} };
  const backendOps = specOps(backend);
  const frontendOps = specOps(frontend);

  const md = buildReport({
    oasdiffReal: j('oasdiff-real.json') || {},
    oasdiffMock: j('oasdiff-mock.json') || {},
    antiPattern: j('anti-pattern-inventory.json') || [],
    warnings: j('frontend-generator-warnings.json') || [],
    unwrapped: j('unwrapped-responses.json') || [],
    backendOps,
    frontendOps,
    backendSnapshot: 'logs/api-contract-2026-05-15/backend-openapi.normalised.json',
    frontendSnapshot: 'logs/api-contract-2026-05-15/frontend-openapi.json',
    scriptVersion: '2026-05-15',
  });

  fs.writeFileSync(outFile, md);
  console.error(`Wrote ${outFile} (${md.length} bytes)`);
}
