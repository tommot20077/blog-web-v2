# Frontend API Request Alignment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add frontend real service wrappers for backend runtime APIs that currently lack wrappers, and clean stale frontend API references.

**Architecture:** Implement focused `src/api/real/*Service.ts` files that call the existing `apiClient` with paths from `api-reference/openapi.json`. Each service gets targeted Vitest coverage before implementation. Documentation and E2E cleanup are separate tasks so request wrapper changes stay isolated from text/test-maintenance changes.

**Tech Stack:** Vue 3, TypeScript, Vitest, axios-backed `apiClient`, Playwright E2E files, OpenAPI JSON.

---

## File Structure

Frontend repository root: `/mnt/d/end/workspace/vue/blog-web-v2-front-end`

Backend planning/evidence root: `/mnt/d/end/workspace/java/blog-web-v2`

Create in frontend repo:

- `src/api/real/bookmarkService.ts`
- `src/api/real/bookmarkService.test.ts`
- `src/api/real/highlightService.ts`
- `src/api/real/highlightService.test.ts`
- `src/api/real/readingProgressService.ts`
- `src/api/real/readingProgressService.test.ts`
- `src/api/real/versionPreferenceService.ts`
- `src/api/real/versionPreferenceService.test.ts`
- `src/api/real/articleVersionService.ts`
- `src/api/real/articleVersionService.test.ts`
- `src/api/real/seriesService.ts`
- `src/api/real/seriesService.test.ts`

Modify in frontend repo:

- `diff.md`
- `runbook-integration.md`
- E2E files that hard-code `http://localhost:9010`, replacing that literal with a shared environment-backed `BACKEND` constant where practical.

Evidence logs in backend repo:

- `logs/frontend-api-request-alignment-*.log`

## Task 1: Bookmark Service

**Files:**
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/bookmarkService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/bookmarkService.ts`

- [ ] **Step 1: Write the failing test**

Create `src/api/real/bookmarkService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { bookmarkService } from './bookmarkService'

vi.mock('../apiClient')

describe('real bookmarkService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('bookmark 呼叫 POST /articles/{uuid}/bookmark', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(undefined)
    await bookmarkService.bookmark('article-uuid')
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/articles/article-uuid/bookmark')
  })

  it('unbookmark 呼叫 DELETE /articles/{uuid}/bookmark', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)
    await bookmarkService.unbookmark('article-uuid')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/articles/article-uuid/bookmark')
  })

  it('getMyBookmarks 呼叫 GET /users/me/bookmarks with pagination', async () => {
    const page = { records: [], total: 0, current: 1, size: 20, pages: 0 }
    vi.mocked(apiClient.get).mockResolvedValue(page)

    const res = await bookmarkService.getMyBookmarks(1, 20)

    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/users/me/bookmarks', {
      params: { page: 1, size: 20 },
    })
    expect(res).toEqual(page)
  })
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run from frontend repo:

```bash
mkdir -p logs
npm test -- src/api/real/bookmarkService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-bookmark-red.log
```

Expected: FAIL because `./bookmarkService` does not exist.

- [ ] **Step 3: Implement bookmark service**

Create `src/api/real/bookmarkService.ts`:

```ts
import apiClient from '../apiClient'
import type { BackendPageResult } from '../utils'

export interface BookmarkArticleSummary {
  uuid: string
  title: string
  summary?: string | null
  coverImageUrl?: string | null
  authorUuid?: string
  authorNickname?: string
  status?: string
  viewCount?: number
  likeCount?: number
  commentCount?: number
  publishedAt?: string | null
  slug?: string
  bookmarked?: boolean
  lastReadProgress?: number | null
}

export const bookmarkService = {
  async bookmark(articleUuid: string): Promise<void> {
    await apiClient.post(`/api/v1/articles/${articleUuid}/bookmark`)
  },

  async unbookmark(articleUuid: string): Promise<void> {
    await apiClient.delete(`/api/v1/articles/${articleUuid}/bookmark`)
  },

  async getMyBookmarks(page: number, size: number): Promise<BackendPageResult<BookmarkArticleSummary>> {
    return apiClient.get<unknown, BackendPageResult<BookmarkArticleSummary>>('/api/v1/users/me/bookmarks', {
      params: { page, size },
    })
  },
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
npm test -- src/api/real/bookmarkService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-bookmark-green.log
```

Expected: PASS.

## Task 2: Highlight And Reading Progress Services

**Files:**
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/highlightService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/highlightService.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/readingProgressService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/readingProgressService.ts`

- [ ] **Step 1: Write failing highlight tests**

Create `src/api/real/highlightService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { highlightService } from './highlightService'

vi.mock('../apiClient')

describe('real highlightService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list 呼叫 GET /articles/{uuid}/highlights', async () => {
    const highlights = [{ uuid: 'h1', snippet: 'text', color: '#FFEB3B' }]
    vi.mocked(apiClient.get).mockResolvedValue(highlights)
    const res = await highlightService.list('article-uuid')
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/articles/article-uuid/highlights')
    expect(res).toEqual(highlights)
  })

  it('create 呼叫 POST /articles/{uuid}/highlights', async () => {
    const request = { snippet: 'text', prefix: '', suffix: '', color: '#FFEB3B', note: 'note' }
    const response = { uuid: 'h1', ...request }
    vi.mocked(apiClient.post).mockResolvedValue(response)
    const res = await highlightService.create('article-uuid', request)
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/articles/article-uuid/highlights', request)
    expect(res).toEqual(response)
  })

  it('update 呼叫 PUT /highlights/{uuid}', async () => {
    const request = { color: '#00FF00', note: 'updated' }
    vi.mocked(apiClient.put).mockResolvedValue({ uuid: 'h1', ...request })
    await highlightService.update('h1', request)
    expect(apiClient.put).toHaveBeenCalledWith('/api/v1/highlights/h1', request)
  })

  it('delete 呼叫 DELETE /highlights/{uuid}', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)
    await highlightService.delete('h1')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/highlights/h1')
  })
})
```

- [ ] **Step 2: Write failing reading progress tests**

Create `src/api/real/readingProgressService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { readingProgressService } from './readingProgressService'

vi.mock('../apiClient')

describe('real readingProgressService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('get 呼叫 GET /articles/{uuid}/progress', async () => {
    const progress = { progress: 0.5, lastHeading: 'intro', updatedAt: '2026-05-09T00:00:00Z' }
    vi.mocked(apiClient.get).mockResolvedValue(progress)
    const res = await readingProgressService.get('article-uuid')
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/articles/article-uuid/progress')
    expect(res).toEqual(progress)
  })

  it('update 呼叫 PUT /articles/{uuid}/progress', async () => {
    const request = { progress: 0.75, lastHeading: 'section-2' }
    vi.mocked(apiClient.put).mockResolvedValue(undefined)
    await readingProgressService.update('article-uuid', request)
    expect(apiClient.put).toHaveBeenCalledWith('/api/v1/articles/article-uuid/progress', request)
  })
})
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
npm test -- src/api/real/highlightService.test.ts src/api/real/readingProgressService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-reading-red.log
```

Expected: FAIL because service modules do not exist.

- [ ] **Step 4: Implement highlight service**

Create `src/api/real/highlightService.ts`:

```ts
import apiClient from '../apiClient'

export interface HighlightItem {
  uuid: string
  snippet: string
  prefix?: string
  suffix?: string
  color: string
  note?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface CreateHighlightRequest {
  snippet: string
  prefix?: string
  suffix?: string
  color: string
  note?: string | null
}

export interface UpdateHighlightRequest {
  color?: string
  note?: string | null
}

export const highlightService = {
  async list(articleUuid: string): Promise<HighlightItem[]> {
    return apiClient.get<unknown, HighlightItem[]>(`/api/v1/articles/${articleUuid}/highlights`)
  },

  async create(articleUuid: string, request: CreateHighlightRequest): Promise<HighlightItem> {
    return apiClient.post<unknown, HighlightItem>(`/api/v1/articles/${articleUuid}/highlights`, request)
  },

  async update(uuid: string, request: UpdateHighlightRequest): Promise<HighlightItem> {
    return apiClient.put<unknown, HighlightItem>(`/api/v1/highlights/${uuid}`, request)
  },

  async delete(uuid: string): Promise<void> {
    await apiClient.delete(`/api/v1/highlights/${uuid}`)
  },
}
```

- [ ] **Step 5: Implement reading progress service**

Create `src/api/real/readingProgressService.ts`:

```ts
import apiClient from '../apiClient'

export interface ReadingProgress {
  progress: number
  lastHeading?: string | null
  updatedAt?: string
}

export interface UpdateReadingProgressRequest {
  progress: number
  lastHeading?: string | null
}

export const readingProgressService = {
  async get(articleUuid: string): Promise<ReadingProgress> {
    return apiClient.get<unknown, ReadingProgress>(`/api/v1/articles/${articleUuid}/progress`)
  },

  async update(articleUuid: string, request: UpdateReadingProgressRequest): Promise<void> {
    await apiClient.put(`/api/v1/articles/${articleUuid}/progress`, request)
  },
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
npm test -- src/api/real/highlightService.test.ts src/api/real/readingProgressService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-reading-green.log
```

Expected: PASS.

## Task 3: Version Preference And Article Version Services

**Files:**
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/versionPreferenceService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/versionPreferenceService.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/articleVersionService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/articleVersionService.ts`

- [ ] **Step 1: Write failing version preference tests**

Create `src/api/real/versionPreferenceService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { versionPreferenceService } from './versionPreferenceService'

vi.mock('../apiClient')

describe('real versionPreferenceService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('get 呼叫 GET /me/preferences/version', async () => {
    const config = { enabled: { value: true, source: 'USER' } }
    vi.mocked(apiClient.get).mockResolvedValue(config)
    const res = await versionPreferenceService.get()
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/me/preferences/version')
    expect(res).toEqual(config)
  })

  it('update 呼叫 PUT /me/preferences/version', async () => {
    const request = { enabled: true, retain: 20, intervalSeconds: 60, diffChars: 200 }
    vi.mocked(apiClient.put).mockResolvedValue({ enabled: { value: true, source: 'USER' } })
    await versionPreferenceService.update(request)
    expect(apiClient.put).toHaveBeenCalledWith('/api/v1/me/preferences/version', request)
  })

  it('reset 呼叫 DELETE /me/preferences/version/{key}', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue({ enabled: { value: true, source: 'DEFAULT' } })
    await versionPreferenceService.reset('enabled')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/me/preferences/version/enabled')
  })
})
```

- [ ] **Step 2: Write failing article version tests**

Create `src/api/real/articleVersionService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { articleVersionService } from './articleVersionService'

vi.mock('../apiClient')

describe('real articleVersionService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list 呼叫 GET /articles/{uuid}/versions with params', async () => {
    const page = { records: [], total: 0, current: 1, size: 20, pages: 0 }
    vi.mocked(apiClient.get).mockResolvedValue(page)
    const res = await articleVersionService.list('article-uuid', { type: 'AUTO', page: 1, size: 20 })
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions', {
      params: { type: 'AUTO', page: 1, size: 20 },
    })
    expect(res).toEqual(page)
  })

  it('getDetail 呼叫 GET /articles/{uuid}/versions/{versionUuid}', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ uuid: 'version-uuid' })
    await articleVersionService.getDetail('article-uuid', 'version-uuid')
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions/version-uuid')
  })

  it('createManual 呼叫 POST /articles/{uuid}/versions/manual', async () => {
    const request = { note: 'save point' }
    vi.mocked(apiClient.post).mockResolvedValue({ uuid: 'version-uuid' })
    await articleVersionService.createManual('article-uuid', request)
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions/manual', request)
  })

  it('delete 呼叫 DELETE /articles/{uuid}/versions/{versionUuid}', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)
    await articleVersionService.delete('article-uuid', 'version-uuid')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions/version-uuid')
  })

  it('promote 呼叫 POST /articles/{uuid}/versions/{versionUuid}/promote', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ uuid: 'version-uuid' })
    await articleVersionService.promote('article-uuid', 'version-uuid')
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions/version-uuid/promote')
  })

  it('restore 呼叫 POST /articles/{uuid}/versions/{versionUuid}/restore', async () => {
    vi.mocked(apiClient.post).mockResolvedValue(undefined)
    await articleVersionService.restore('article-uuid', 'version-uuid')
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/articles/article-uuid/versions/version-uuid/restore')
  })
})
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
npm test -- src/api/real/versionPreferenceService.test.ts src/api/real/articleVersionService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-version-red.log
```

Expected: FAIL because service modules do not exist.

- [ ] **Step 4: Implement version preference service**

Create `src/api/real/versionPreferenceService.ts`:

```ts
import apiClient from '../apiClient'

export interface PreferenceField<T> {
  value: T
  source: string
}

export interface VersionPreferenceConfig {
  enabled?: PreferenceField<boolean>
  retain?: PreferenceField<number>
  intervalSeconds?: PreferenceField<number>
  diffChars?: PreferenceField<number>
}

export interface UpdateVersionPreferenceRequest {
  enabled?: boolean
  retain?: number
  intervalSeconds?: number
  diffChars?: number
}

export const versionPreferenceService = {
  async get(): Promise<VersionPreferenceConfig> {
    return apiClient.get<unknown, VersionPreferenceConfig>('/api/v1/me/preferences/version')
  },

  async update(request: UpdateVersionPreferenceRequest): Promise<VersionPreferenceConfig> {
    return apiClient.put<unknown, VersionPreferenceConfig>('/api/v1/me/preferences/version', request)
  },

  async reset(key: string): Promise<VersionPreferenceConfig> {
    return apiClient.delete<unknown, VersionPreferenceConfig>(`/api/v1/me/preferences/version/${key}`)
  },
}
```

- [ ] **Step 5: Implement article version service**

Create `src/api/real/articleVersionService.ts`:

```ts
import apiClient from '../apiClient'
import type { BackendPageResult } from '../utils'

export interface ArticleVersionSummary {
  uuid: string
  type: string
  note?: string | null
  createdAt?: string
  authorId?: number
  contentLength?: number
}

export interface ArticleVersionDetail extends ArticleVersionSummary {
  title?: string
  slug?: string
  content?: string
  summary?: string | null
  categoryId?: number | null
  coverImageUrl?: string | null
  status?: string
  tags?: string[]
}

export interface ArticleVersionListParams {
  type?: string
  page?: number
  size?: number
}

export interface CreateManualSnapshotRequest {
  note?: string
}

export const articleVersionService = {
  async list(
    articleUuid: string,
    params: ArticleVersionListParams = {},
  ): Promise<BackendPageResult<ArticleVersionSummary>> {
    return apiClient.get<unknown, BackendPageResult<ArticleVersionSummary>>(
      `/api/v1/articles/${articleUuid}/versions`,
      { params },
    )
  },

  async getDetail(articleUuid: string, versionUuid: string): Promise<ArticleVersionDetail> {
    return apiClient.get<unknown, ArticleVersionDetail>(
      `/api/v1/articles/${articleUuid}/versions/${versionUuid}`,
    )
  },

  async createManual(articleUuid: string, request: CreateManualSnapshotRequest = {}): Promise<ArticleVersionDetail> {
    return apiClient.post<unknown, ArticleVersionDetail>(
      `/api/v1/articles/${articleUuid}/versions/manual`,
      request,
    )
  },

  async delete(articleUuid: string, versionUuid: string): Promise<void> {
    await apiClient.delete(`/api/v1/articles/${articleUuid}/versions/${versionUuid}`)
  },

  async promote(articleUuid: string, versionUuid: string): Promise<ArticleVersionDetail> {
    return apiClient.post<unknown, ArticleVersionDetail>(
      `/api/v1/articles/${articleUuid}/versions/${versionUuid}/promote`,
    )
  },

  async restore(articleUuid: string, versionUuid: string): Promise<void> {
    await apiClient.post(`/api/v1/articles/${articleUuid}/versions/${versionUuid}/restore`)
  },
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
npm test -- src/api/real/versionPreferenceService.test.ts src/api/real/articleVersionService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-version-green.log
```

Expected: PASS.

## Task 4: Series Service

**Files:**
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/seriesService.test.ts`
- Create: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/seriesService.ts`

- [ ] **Step 1: Write failing series tests**

Create `src/api/real/seriesService.test.ts`:

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import apiClient from '../apiClient'
import { seriesService } from './seriesService'

vi.mock('../apiClient')

describe('real seriesService', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list 呼叫 GET /series with params', async () => {
    const page = { records: [], total: 0, current: 1, size: 20, pages: 0 }
    vi.mocked(apiClient.get).mockResolvedValue(page)
    const res = await seriesService.list({ page: 1, size: 20 })
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/series', {
      params: { page: 1, size: 20 },
    })
    expect(res).toEqual(page)
  })

  it('get 呼叫 GET /series/{slug}', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ uuid: 'series-uuid', slug: 'vue' })
    await seriesService.get('vue')
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/series/vue')
  })

  it('create 呼叫 POST /series', async () => {
    const request = { title: 'Vue Series', slug: 'vue-series', description: 'desc' }
    vi.mocked(apiClient.post).mockResolvedValue({ uuid: 'series-uuid', ...request })
    await seriesService.create(request)
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/series', request)
  })

  it('update 呼叫 PUT /series/{uuid}', async () => {
    const request = { title: 'Updated', slug: 'updated' }
    vi.mocked(apiClient.put).mockResolvedValue({ uuid: 'series-uuid', ...request })
    await seriesService.update('series-uuid', request)
    expect(apiClient.put).toHaveBeenCalledWith('/api/v1/series/series-uuid', request)
  })

  it('delete 呼叫 DELETE /series/{uuid}', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)
    await seriesService.delete('series-uuid')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/series/series-uuid')
  })

  it('addArticle 呼叫 PUT /series/{uuid}/articles/{articleUuid}', async () => {
    vi.mocked(apiClient.put).mockResolvedValue(undefined)
    await seriesService.addArticle('series-uuid', 'article-uuid', { position: 2 })
    expect(apiClient.put).toHaveBeenCalledWith(
      '/api/v1/series/series-uuid/articles/article-uuid',
      { position: 2 },
    )
  })

  it('removeArticle 呼叫 DELETE /series/{uuid}/articles/{articleUuid}', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue(undefined)
    await seriesService.removeArticle('series-uuid', 'article-uuid')
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/series/series-uuid/articles/article-uuid')
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

```bash
npm test -- src/api/real/seriesService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-series-red.log
```

Expected: FAIL because `./seriesService` does not exist.

- [ ] **Step 3: Implement series service**

Create `src/api/real/seriesService.ts`:

```ts
import apiClient from '../apiClient'
import type { BackendPageResult } from '../utils'

export interface AuthorSummary {
  uuid?: string
  nickname?: string
  avatarUrl?: string | null
}

export interface SeriesSummary {
  uuid: string
  title: string
  slug: string
  description?: string | null
  coverImageUrl?: string | null
  author?: AuthorSummary
  articleCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface SeriesDetail extends SeriesSummary {
  articles?: unknown[]
  myProgress?: {
    readCount?: number
    totalCount?: number
    nextUnreadArticleUuid?: string | null
  }
}

export interface SeriesListParams {
  page?: number
  size?: number
}

export interface CreateSeriesRequest {
  title: string
  slug: string
  description?: string | null
  coverImageUrl?: string | null
}

export interface UpdateSeriesRequest {
  title?: string
  slug?: string
  description?: string | null
  coverImageUrl?: string | null
}

export interface AddArticleToSeriesRequest {
  position: number
}

export const seriesService = {
  async list(params: SeriesListParams = {}): Promise<BackendPageResult<SeriesSummary>> {
    return apiClient.get<unknown, BackendPageResult<SeriesSummary>>('/api/v1/series', { params })
  },

  async get(slug: string): Promise<SeriesDetail> {
    return apiClient.get<unknown, SeriesDetail>(`/api/v1/series/${slug}`)
  },

  async create(request: CreateSeriesRequest): Promise<SeriesSummary> {
    return apiClient.post<unknown, SeriesSummary>('/api/v1/series', request)
  },

  async update(uuid: string, request: UpdateSeriesRequest): Promise<SeriesSummary> {
    return apiClient.put<unknown, SeriesSummary>(`/api/v1/series/${uuid}`, request)
  },

  async delete(uuid: string): Promise<void> {
    await apiClient.delete(`/api/v1/series/${uuid}`)
  },

  async addArticle(uuid: string, articleUuid: string, request: AddArticleToSeriesRequest): Promise<void> {
    await apiClient.put(`/api/v1/series/${uuid}/articles/${articleUuid}`, request)
  },

  async removeArticle(uuid: string, articleUuid: string): Promise<void> {
    await apiClient.delete(`/api/v1/series/${uuid}/articles/${articleUuid}`)
  },
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
npm test -- src/api/real/seriesService.test.ts 2>&1 | tee logs/frontend-api-request-alignment-series-green.log
```

Expected: PASS.

## Task 5: E2E Backend URL Cleanup

**Files:**
- Modify only E2E files under `/mnt/d/end/workspace/vue/blog-web-v2-front-end/e2e/` that currently hard-code `http://localhost:9010`.

- [ ] **Step 1: Capture current hard-coded URL usage**

```bash
rg -n "http://localhost:9010" e2e 2>&1 | tee logs/frontend-api-request-alignment-e2e-url-red.log
```

Expected: output lists hard-coded E2E URLs.

- [ ] **Step 2: Replace hard-coded literals with local BACKEND constants**

For each affected E2E file, add this near imports if a `BACKEND` constant is not already present:

```ts
const BACKEND = process.env.VITE_API_BASE_URL || 'http://localhost:9010'
```

Then replace direct literals:

```ts
'http://localhost:9010/api/v1/auth/login'
`http://localhost:9010/api/v1/articles/${uuid}`
```

with:

```ts
`${BACKEND}/api/v1/auth/login`
`${BACKEND}/api/v1/articles/${uuid}`
```

Do not change endpoint paths or test assertions.

- [ ] **Step 3: Verify no E2E hard-coded URL remains**

```bash
set -o pipefail
rg -n "http://localhost:9010" e2e 2>&1 | tee logs/frontend-api-request-alignment-e2e-url-green.log
test "${PIPESTATUS[0]}" -eq 1
```

Expected: no output and `rg` exits `1`.

- [ ] **Step 4: Run a TypeScript check for changed E2E files**

```bash
npm run build 2>&1 | tee logs/frontend-api-request-alignment-e2e-build.log
```

Expected: PASS. If unrelated existing build failures appear, record the failure and run targeted Vitest tests from Tasks 1-4 instead; do not change UI code to fix unrelated build failures.

## Task 6: Frontend Documentation Cleanup

**Files:**
- Modify: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/diff.md`
- Modify: `/mnt/d/end/workspace/vue/blog-web-v2-front-end/runbook-integration.md`

- [ ] **Step 1: Capture stale doc references**

```bash
rg -n "/api/admin|pending/count" diff.md runbook-integration.md 2>&1 | tee logs/frontend-api-request-alignment-docs-red.log
```

Expected: output includes stale references.

- [ ] **Step 2: Update current contract wording**

In both docs:

- Replace current-path mentions of `/api/admin/...` with `/api/v1/admin/...`.
- Remove `pending/count` as a current endpoint.
- If a historical note is necessary, phrase it as old removed documentation, not a live endpoint.
- Keep the statement that pending count is derived from `GET /api/v1/admin/articles/pending?page=1&size=1` and response `total`.

- [ ] **Step 3: Verify stale docs are gone**

```bash
set -o pipefail
rg -n "/api/admin|pending/count" diff.md runbook-integration.md 2>&1 | tee logs/frontend-api-request-alignment-docs-green.log
test "${PIPESTATUS[0]}" -eq 1
```

Expected: no output and `rg` exits `1`.

## Task 7: Full Targeted Verification

**Files:**
- Read-only verification plus evidence logs.

- [ ] **Step 1: Run all new service tests**

```bash
npm test -- \
  src/api/real/bookmarkService.test.ts \
  src/api/real/highlightService.test.ts \
  src/api/real/readingProgressService.test.ts \
  src/api/real/versionPreferenceService.test.ts \
  src/api/real/articleVersionService.test.ts \
  src/api/real/seriesService.test.ts \
  2>&1 | tee logs/frontend-api-request-alignment-services-all.log
```

Expected: PASS.

- [ ] **Step 2: Verify no stale current API references remain in target areas**

```bash
set -o pipefail
rg -n "/api/admin|pending/count" diff.md runbook-integration.md src e2e 2>&1 | tee logs/frontend-api-request-alignment-stale-all.log
test "${PIPESTATUS[0]}" -eq 1
```

Expected: no output and `rg` exits `1`.

- [ ] **Step 3: Verify no E2E hard-coded backend URL remains**

```bash
set -o pipefail
rg -n "http://localhost:9010" e2e 2>&1 | tee logs/frontend-api-request-alignment-e2e-url-final.log
test "${PIPESTATUS[0]}" -eq 1
```

Expected: no output and `rg` exits `1`.

- [ ] **Step 4: Check frontend git status**

```bash
git status --short
```

Expected: only intended frontend files are modified/created.

## Task 8: Commit Frontend Alignment

**Files:**
- Stage only files created/modified by this plan in `/mnt/d/end/workspace/vue/blog-web-v2-front-end`.

- [ ] **Step 1: Review frontend diff**

```bash
git diff -- src/api/real diff.md runbook-integration.md e2e
git status --short
```

Expected: diff only includes new service wrappers/tests, E2E backend URL cleanup, and docs cleanup.

- [ ] **Step 2: Commit frontend changes**

```bash
git add \
  src/api/real/bookmarkService.ts \
  src/api/real/bookmarkService.test.ts \
  src/api/real/highlightService.ts \
  src/api/real/highlightService.test.ts \
  src/api/real/readingProgressService.ts \
  src/api/real/readingProgressService.test.ts \
  src/api/real/versionPreferenceService.ts \
  src/api/real/versionPreferenceService.test.ts \
  src/api/real/articleVersionService.ts \
  src/api/real/articleVersionService.test.ts \
  src/api/real/seriesService.ts \
  src/api/real/seriesService.test.ts \
  diff.md \
  runbook-integration.md \
  e2e/global-setup.ts \
  e2e/integration/admin-review.spec.ts \
  e2e/integration/article-like.spec.ts \
  e2e/integration/article-slug-api.spec.ts \
  e2e/integration/auth-token-refresh.spec.ts \
  e2e/integration/author-file-upload.spec.ts \
  e2e/integration/author-writes-article.spec.ts \
  e2e/integration/comment-crud.spec.ts \
  e2e/integration/comment-list.spec.ts \
  e2e/integration/editor-edit-existing.spec.ts \
  e2e/integration/end-to-end-sanity.spec.ts \
  e2e/integration/my-articles.spec.ts \
  e2e/integration/search-advanced.spec.ts \
  e2e/integration/settings-delete-account.spec.ts \
  e2e/integration/settings.spec.ts \
  e2e/integration/tag-suggest.spec.ts
git commit -m "feat(api): 補齊前端 real API 請求封裝"
```

Expected: commit succeeds on `feat/mock-data-phase1`.

- [ ] **Step 3: Commit backend plan if not already committed**

In backend repo:

```bash
git add docs/superpowers/plans/2026-05-09-frontend-api-request-alignment-plan.md
git commit -m "docs(api): 規劃前端 API 請求對齊"
```

Expected: commit succeeds on `feature/api-contract-audit`. Do not stage `response.log` or `logs/`.

## Task 9: Final Report

**Files:**
- Read-only final checks.

- [ ] **Step 1: Print commits and status**

```bash
git -C /mnt/d/end/workspace/java/blog-web-v2 rev-parse --short HEAD
git -C /mnt/d/end/workspace/java/blog-web-v2 log -1 --pretty=%s
git -C /mnt/d/end/workspace/java/blog-web-v2 status --short
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end rev-parse --short HEAD
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end log -1 --pretty=%s
git -C /mnt/d/end/workspace/vue/blog-web-v2-front-end status --short
```

Expected: backend latest commit is the plan commit, frontend latest commit is the implementation commit, and no unintended staged files remain.

- [ ] **Step 2: Report to Yuan**

Include:

- New service wrappers added.
- Tests run and result.
- Docs/E2E cleanup result.
- Frontend commit SHA.
- Backend plan commit SHA.
- Any remaining repo status risk, especially backend `response.log`.
