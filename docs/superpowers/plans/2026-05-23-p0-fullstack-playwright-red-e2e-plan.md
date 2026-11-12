# P0 Full-stack Playwright Red E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add executable full-stack Playwright red E2E tests for P0 user journeys using the real frontend, real backend, and backend Testcontainers infrastructure.

**Architecture:** Add an opt-in Playwright project/folder for full-stack red tests in the frontend repo. The tests call real frontend routes and real backend APIs; orchestration initially assumes a backend red E2E server or local backend has been started by the test command wrapper.

**Tech Stack:** Vue 3, Vite, Playwright, TypeScript, Spring Boot, Testcontainers, PowerShell.

---

## Red-Test Contract

This plan stops at red.

- Playwright specs must launch and reach assertions.
- Tests must not rely on dev database seed state.
- Tests may prepare data through backend APIs.
- Do not fix frontend or backend product code to make these tests green.
- All output goes under frontend `logs/`.

## File Structure

Frontend repo:

- `D:\end\workspace\vue\blog-web-v2-front-end`

Create:

- `e2e/fullstack-red/fixtures/fullstack-red.ts`
- `e2e/fullstack-red/auth-lifecycle.red.spec.ts`
- `e2e/fullstack-red/author-review.red.spec.ts`
- `e2e/fullstack-red/reader-interaction.red.spec.ts`

Modify:

- `playwright.config.ts`
- `package.json`

Backend repo:

- `D:\end\workspace\java\blog-web-v2`

Reference:

- `docs/superpowers/plans/2026-05-23-p0-backend-red-e2e-plan.md`

## Task 1: Add Full-stack Red Playwright Project

**Files:**

- Modify: `D:\end\workspace\vue\blog-web-v2-front-end\playwright.config.ts`
- Modify: `D:\end\workspace\vue\blog-web-v2-front-end\package.json`

- [ ] **Step 1: Add environment switch in Playwright config**

Change the top of `playwright.config.ts` to distinguish mock, integration, and full-stack red:

```ts
const USE_MOCK = process.env.E2E_MOCK === '1'
const USE_FULLSTACK_RED = process.env.E2E_FULLSTACK_RED === '1'
```

Set `testDir`:

```ts
testDir: USE_FULLSTACK_RED ? './e2e/fullstack-red' : USE_MOCK ? './e2e/mock' : './e2e/integration',
```

Set `globalSetup`:

```ts
globalSetup: USE_MOCK || USE_FULLSTACK_RED ? undefined : './e2e/global-setup.ts',
```

- [ ] **Step 2: Add package script**

Add this script to `package.json`:

```json
"test:e2e:fullstack-red": "E2E_FULLSTACK_RED=1 playwright test"
```

On Windows PowerShell, execution can use:

```powershell
$env:E2E_FULLSTACK_RED='1'
npx playwright test
```

- [ ] **Step 3: Verify no full-stack red specs exist yet**

Run from the frontend repo:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
npx playwright test --list 2>&1 | Tee-Object -FilePath logs\fullstack-red-empty-list.log
```

Expected:

- Command succeeds.
- It lists no full-stack red tests until specs are added.

- [ ] **Step 4: Commit project isolation**

```powershell
git status --short
git add playwright.config.ts package.json
git commit -m "test(e2e): 隔離全端紅燈 playwright 專案"
```

## Task 2: Add Full-stack Red Fixture

**Files:**

- Create: `D:\end\workspace\vue\blog-web-v2-front-end\e2e\fullstack-red\fixtures\fullstack-red.ts`

- [ ] **Step 1: Create fixture with API helpers**

Create the file:

```ts
import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

const BACKEND = process.env.VITE_API_BASE_URL || 'http://localhost:9010'

type LoginResult = {
  accessToken: string
}

async function apiPost(request: APIRequestContext, path: string, body?: unknown, token?: string) {
  return request.post(`${BACKEND}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    data: body,
  })
}

async function loginViaApi(request: APIRequestContext, identifier: string, password: string): Promise<LoginResult> {
  const res = await apiPost(request, '/api/v1/auth/login', { identifier, password })
  expect(res.ok(), `login ${identifier}`).toBeTruthy()
  const json = await res.json()
  expect(json.data?.accessToken).toBeTruthy()
  return { accessToken: json.data.accessToken }
}

async function waitForBackend(request: APIRequestContext) {
  const res = await request.get(`${BACKEND}/actuator/health/readiness`)
  expect(res.ok(), `backend readiness at ${BACKEND}`).toBeTruthy()
}

async function uiLogin(page: Page, identifier: string, password: string) {
  await page.goto('/login')
  await page.getByTestId('auth-login-field-email').fill(identifier)
  await page.getByTestId('auth-login-field-password').fill(password)
  await page.getByTestId('auth-login-submit').click()
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10000 })
}

export const test = base.extend<{
  backendUrl: string
  waitForBackend: () => Promise<void>
  loginViaApi: (identifier: string, password: string) => Promise<LoginResult>
  uiLogin: (identifier: string, password: string) => Promise<void>
}>({
  backendUrl: async ({}, use) => {
    await use(BACKEND)
  },
  waitForBackend: async ({ request }, use) => {
    await use(async () => waitForBackend(request))
  },
  loginViaApi: async ({ request }, use) => {
    await use(async (identifier, password) => loginViaApi(request, identifier, password))
  },
  uiLogin: async ({ page }, use) => {
    await use(async (identifier, password) => uiLogin(page, identifier, password))
  },
})

export { expect }
```

- [ ] **Step 2: Write fixture smoke spec**

Create `e2e/fullstack-red/auth-lifecycle.red.spec.ts` with the smoke test:

```ts
import { test, expect } from './fixtures/fullstack-red'

test.describe('P0 full-stack red - auth lifecycle', () => {
  test('backend readiness is available before browser flow starts', async ({ waitForBackend, backendUrl }) => {
    await waitForBackend()
    expect(backendUrl).toContain('http')
  })
})
```

- [ ] **Step 3: Run fixture smoke red project**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
$env:VITE_API_BASE_URL='http://localhost:9010'
npx playwright test e2e/fullstack-red/auth-lifecycle.red.spec.ts 2>&1 | Tee-Object -FilePath logs\fullstack-red-fixture.log
```

Expected:

- If backend is running, the test reaches assertion.
- If backend is not running, failure clearly says backend readiness failed.

- [ ] **Step 4: Commit fixture**

```powershell
git status --short
git add e2e/fullstack-red/fixtures/fullstack-red.ts e2e/fullstack-red/auth-lifecycle.red.spec.ts
git commit -m "test(e2e): 新增全端紅燈 playwright fixture"
```

## Task 3: Auth Lifecycle Full-stack Red Spec

**Files:**

- Modify: `D:\end\workspace\vue\blog-web-v2-front-end\e2e\fullstack-red\auth-lifecycle.red.spec.ts`

- [ ] **Step 1: Replace smoke spec with real auth lifecycle**

Use this content:

```ts
import { test, expect } from './fixtures/fullstack-red'

test.describe('P0 full-stack red - auth lifecycle', () => {
  test('註冊 驗證 登入 refresh 登出在瀏覽器與後端狀態一致', async ({ page, request, waitForBackend, backendUrl }) => {
    await waitForBackend()
    const suffix = Date.now()
    const email = `fullstack-red-${suffix}@test.local`
    const username = `fsred${suffix}`
    const password = 'FullstackRed123!'

    await page.goto('/register')
    await page.getByTestId('auth-register-field-email').fill(email)
    await page.getByTestId('auth-register-field-username').fill(username)
    await page.getByTestId('auth-register-field-nickname').fill('Fullstack Red')
    await page.getByTestId('auth-register-field-password').fill(password)
    await page.getByTestId('auth-register-submit').click()

    await expect(page.getByText(/驗證|verification|信箱/)).toBeVisible({ timeout: 10000 })

    const loginBeforeVerify = await request.post(`${backendUrl}/api/v1/auth/login`, {
      data: { identifier: email, password },
    })
    expect(loginBeforeVerify.status(), 'unverified account login status').toBeGreaterThanOrEqual(400)

    await page.goto('/login')
    await page.getByTestId('auth-login-field-email').fill(email)
    await page.getByTestId('auth-login-field-password').fill(password)
    await page.getByTestId('auth-login-submit').click()
    await expect(page.getByText(/未驗證|verify|信箱/)).toBeVisible({ timeout: 10000 })
  })
})
```

- [ ] **Step 2: Run and capture red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
$env:VITE_API_BASE_URL='http://localhost:9010'
npx playwright test e2e/fullstack-red/auth-lifecycle.red.spec.ts 2>&1 | Tee-Object -FilePath logs\fullstack-auth-lifecycle-red.log
```

Expected:

- Failure is a selector, UI contract, or backend verification-flow mismatch after page/backend are reachable.

- [ ] **Step 3: Commit auth red spec**

```powershell
git status --short
git add e2e/fullstack-red/auth-lifecycle.red.spec.ts
git commit -m "test(e2e): 新增全端 auth 紅燈流程"
```

## Task 4: Author Review Full-stack Red Spec

**Files:**

- Create: `D:\end\workspace\vue\blog-web-v2-front-end\e2e\fullstack-red\author-review.red.spec.ts`

- [ ] **Step 1: Write the red spec**

Create the file:

```ts
import path from 'node:path'
import { test, expect } from './fixtures/fullstack-red'

test.describe('P0 full-stack red - author review', () => {
  test('Author 建草稿上傳封面送審後 Admin 發布 Reader 可搜尋', async ({ page, waitForBackend, uiLogin }) => {
    await waitForBackend()

    await uiLogin('author@test.local', 'Test1234!')
    await page.goto('/editor')
    await page.getByTestId('editor-title-input').fill(`Fullstack red article ${Date.now()}`)
    await page.locator('.cm-content').click()
    await page.locator('.cm-content').pressSequentially('Fullstack red article content')
    await page.getByPlaceholder(/摘要|summary/i).fill('Fullstack red summary')
    await page.locator('input[type="file"]').first().setInputFiles(path.resolve('e2e/fixtures/test-image.png'))
    await page.getByTestId('editor-save-btn').click()
    await expect(page.getByText(/草稿|saved|儲存/)).toBeVisible({ timeout: 10000 })
    await page.getByRole('button', { name: /送審|審核|submit/i }).click()
    await expect(page.getByText(/待審|pending/i)).toBeVisible({ timeout: 10000 })

    await uiLogin('admin@test.local', 'Test1234!')
    await page.goto('/admin/review')
    await page.getByRole('button', { name: /通過|發布|approve|publish/i }).first().click()
    await expect(page.getByText(/通過|發布|published/i)).toBeVisible({ timeout: 10000 })

    await page.goto('/search')
    await page.getByTestId('search-input').fill('Fullstack red article')
    await expect(page.getByText(/Fullstack red article/)).toBeVisible({ timeout: 20000 })
  })
})
```

- [ ] **Step 2: Run and capture red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
$env:VITE_API_BASE_URL='http://localhost:9010'
npx playwright test e2e/fullstack-red/author-review.red.spec.ts 2>&1 | Tee-Object -FilePath logs\fullstack-author-review-red.log
```

Expected:

- Failure is a real flow issue: seed user missing, selector mismatch, upload contract, review state, or ES visibility.

- [ ] **Step 3: Commit author review red spec**

```powershell
git status --short
git add e2e/fullstack-red/author-review.red.spec.ts
git commit -m "test(e2e): 新增全端 author 審核紅燈流程"
```

## Task 5: Reader Interaction Full-stack Red Spec

**Files:**

- Create: `D:\end\workspace\vue\blog-web-v2-front-end\e2e\fullstack-red\reader-interaction.red.spec.ts`

- [ ] **Step 1: Write the red spec**

Create the file:

```ts
import { test, expect } from './fixtures/fullstack-red'

test.describe('P0 full-stack red - reader interaction', () => {
  test('Reader 搜尋文章後可按讚收藏留言且重複操作狀態一致', async ({ page, waitForBackend, uiLogin }) => {
    await waitForBackend()
    await uiLogin('reader@test.local', 'Test1234!')

    await page.goto('/search')
    await page.getByTestId('search-input').fill('E2E')
    await page.locator('article, [data-testid^="article-card-"]').first().click()
    await expect(page).toHaveURL(/\/articles\//)

    const like = page.getByTestId('article-like-action-bar')
    const bookmark = page.getByTestId('article-bookmark-action-bar')
    await like.click()
    await expect(like).toHaveAttribute(/aria-pressed|data-active/, /true|1/)
    await like.click()
    await expect(like).toHaveAttribute(/aria-pressed|data-active/, /false|0/)

    await bookmark.click()
    await expect(bookmark).toHaveAttribute(/aria-pressed|data-active/, /true|1/)
    await page.getByTestId('comment-textarea').fill('Fullstack red reader comment')
    await page.getByTestId('comment-submit').click()
    await expect(page.getByText('Fullstack red reader comment')).toBeVisible({ timeout: 10000 })

    await page.goto('/bookmarks')
    await expect(page.getByText(/E2E|Fullstack/)).toBeVisible({ timeout: 10000 })
  })

  test('Guest 點擊互動應導到登入頁或顯示穩定權限錯誤', async ({ page, waitForBackend }) => {
    await waitForBackend()
    await page.goto('/articles')
    await page.locator('article, [data-testid^="article-card-"]').first().click()
    await page.getByTestId('article-like-action-bar').click()
    await expect(page).toHaveURL(/\/login|\/articles\//)
    await expect(page.getByText(/登入|權限|login|unauthorized/i)).toBeVisible({ timeout: 10000 })
  })
})
```

- [ ] **Step 2: Run and capture red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
$env:VITE_API_BASE_URL='http://localhost:9010'
npx playwright test e2e/fullstack-red/reader-interaction.red.spec.ts 2>&1 | Tee-Object -FilePath logs\fullstack-reader-interaction-red.log
```

Expected:

- Failure is a real UI/backend mismatch, not Playwright project startup failure.

- [ ] **Step 3: Commit reader red spec**

```powershell
git status --short
git add e2e/fullstack-red/reader-interaction.red.spec.ts
git commit -m "test(e2e): 新增全端 reader 互動紅燈流程"
```

## Task 6: Full-stack Red Suite Run

**Files:**

- No new files.

- [ ] **Step 1: Run the full full-stack red suite**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
$env:E2E_FULLSTACK_RED='1'
$env:VITE_API_BASE_URL='http://localhost:9010'
npx playwright test e2e/fullstack-red 2>&1 | Tee-Object -FilePath logs\p0-fullstack-red-suite.log
```

Expected:

- Suite may fail.
- Failures are meaningful red failures after browser/app/backend startup.

- [ ] **Step 2: Preserve Playwright artifacts**

```powershell
Get-ChildItem test-results -Recurse -ErrorAction SilentlyContinue |
  Select-Object FullName, Length, LastWriteTime
```

Expected:

- Failure traces/screenshots/videos exist when Playwright captures them.

- [ ] **Step 3: Run diff check**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
git diff --check 2>&1 | Tee-Object -FilePath logs\p0-fullstack-red-diff-check.log
```

Expected:

- Exit code `0`.

## Final Acceptance

- Full-stack red tests are isolated behind `E2E_FULLSTACK_RED=1`.
- Specs reach assertions when backend and frontend are available.
- Logs are saved under frontend `logs/`.
- Existing mock/integration Playwright suites are not forced to inherit red tests.
- No product code is changed to make the red tests green.
