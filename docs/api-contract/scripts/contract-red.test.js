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

function responseEnvelope(operation) {
  const response = operation.responses?.['200'] ?? operation.responses?.['201']
  const json = response?.content?.['application/json']?.schema
  return JSON.stringify(json ?? {})
}

describe('contract red checks', () => {
  it('frontend and backend expose exactly the same operation keys', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))

    expect([...frontend.keys()].sort()).toEqual([...backend.keys()].sort())
  })

  it('shared successful responses keep a data envelope', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const mismatches = []

    for (const [key, backendOperation] of backend) {
      const frontendOperation = frontend.get(key)
      if (!frontendOperation) continue
      const backendEnvelope = responseEnvelope(backendOperation)
      const frontendEnvelope = responseEnvelope(frontendOperation)
      if (!backendEnvelope.includes('data') || !frontendEnvelope.includes('data')) {
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
})
