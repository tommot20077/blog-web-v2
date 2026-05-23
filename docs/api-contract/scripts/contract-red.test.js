import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const LOG_DIR = path.join(ROOT, 'logs', 'api-contract-red')
const BACKEND = path.join(LOG_DIR, 'backend-openapi.normalised.json')
const FRONTEND = path.join(LOG_DIR, 'frontend-openapi.json')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function operationMap(doc) {
  const result = new Map()
  for (const [apiPath, pathItem] of Object.entries(doc.paths ?? {})) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      if (pathItem[method]) {
        result.set(`${method.toUpperCase()} ${apiPath}`, pathItem[method])
      }
    }
  }
  return result
}

function responseSchema(operation) {
  const response = operation.responses?.['200'] ?? operation.responses?.['201']
  const content = response?.content ?? {}
  if (content['application/json']?.schema) return content['application/json'].schema
  const firstSchema = Object.values(content).find((entry) => entry?.schema)?.schema
  return firstSchema ?? null
}

function looksLikeApiResponseEnvelope(schema) {
  const json = JSON.stringify(schema ?? {})
  return json.includes('"code"') && json.includes('"message"') && json.includes('"data"')
}

describe('contract red checks', () => {
  it('frontend and backend expose exactly the same operation keys', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))

    expect([...frontend.keys()].sort()).toEqual([...backend.keys()].sort())
  })

  it('shared successful responses are unwrapped payload schemas', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const mismatches = []

    for (const [key, backendOperation] of backend) {
      const frontendOperation = frontend.get(key)
      if (!frontendOperation) continue
      const backendSchema = responseSchema(backendOperation)
      const frontendSchema = responseSchema(frontendOperation)
      if (!backendSchema && !frontendSchema) {
        continue
      }
      if (!backendSchema || !frontendSchema) {
        mismatches.push(`${key} (json-response-mismatch)`)
        continue
      }
      if (looksLikeApiResponseEnvelope(backendSchema) || looksLikeApiResponseEnvelope(frontendSchema)) {
        mismatches.push(key)
      }
    }

    expect(mismatches).toEqual([])
  })

  it('multipart upload contract remains aligned', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const key = 'POST /api/v1/files/upload'
    const backendBody = JSON.stringify(backend.get(key)?.requestBody ?? {})
    const frontendBody = JSON.stringify(frontend.get(key)?.requestBody ?? {})

    expect(backendBody).toContain('multipart/form-data')
    expect(frontendBody).toContain('multipart/form-data')
    expect(frontendBody).toContain('file')
  })

  it('article status enum contains workflow states used by UI journeys', () => {
    const backend = JSON.stringify(readJson(BACKEND))
    const required = ['DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED']
    const missing = required.filter((value) => !backend.includes(value))

    expect(missing).toEqual([])
  })

  it('P0 journey endpoints are all present in both contracts', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const required = [
      'POST /api/v1/auth/register',
      'GET /api/v1/auth/verify-email',
      'POST /api/v1/auth/login',
      'POST /api/v1/auth/refresh',
      'POST /api/v1/auth/logout',
      'POST /api/v1/articles',
      'PUT /api/v1/articles/{uuid}',
      'POST /api/v1/articles/{uuid}/submit',
      'POST /api/v1/articles/{uuid}/publish',
      'POST /api/v1/articles/{uuid}/reject',
      'GET /api/v1/admin/articles/pending',
      'GET /api/v1/articles',
      'GET /api/v1/articles/{uuid}',
      'GET /api/v1/search',
      'POST /api/v1/articles/{articleUuid}/like',
      'POST /api/v1/articles/{articleUuid}/bookmark',
      'GET /api/v1/users/me/bookmarks',
      'POST /api/v1/articles/{articleUuid}/comments',
    ]

    const missing = required.filter((key) => !backend.has(key) || !frontend.has(key))
    expect(missing).toEqual([])
  })
})
