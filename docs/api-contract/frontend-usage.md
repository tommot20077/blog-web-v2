# Frontend API Usage

## Source

- Frontend repository: `/mnt/d/end/workspace/vue/blog-web-v2-front-end`
- Real service scan: `logs/api-contract-frontend-real-usage.raw.txt`
- E2E direct usage scan: `logs/api-contract-frontend-e2e-usage.raw.txt`
- Runtime backend endpoint source: `logs/api-contract-runtime-endpoints.txt`
- API client base URL: `src/api/apiClient.ts` uses `VITE_API_BASE_URL || 'http://localhost:8080'`.
- E2E backend base URL: `e2e/global-setup.ts` uses `VITE_API_BASE_URL || 'http://localhost:9010'`.

## Real Service Usage

| Method | Path | Frontend service | Runtime OpenAPI Status | Notes |
|---|---|---|---|---|
| DELETE | `/api/v1/articles/{uuid}` | `myArticlesService.ts` | matched | Delete own draft/article. |
| DELETE | `/api/v1/articles/{articleUuid}/like` | `articleLikeService.ts` | matched | Article unlike. |
| DELETE | `/api/v1/comments/{uuid}` | `commentService.ts` | matched | Delete comment. |
| DELETE | `/api/v1/comments/{uuid}/like` | `commentService.ts` | matched | Comment unlike. |
| DELETE | `/api/v1/files/{id}` | `fileService.ts` | matched | Delete uploaded file. |
| DELETE | `/api/v1/search/history` | `searchService.ts` | matched | Clear search history. |
| DELETE | `/api/v1/tags/{id}/follow` | `tagService.ts` | matched | Unfollow tag. |
| DELETE | `/api/v1/users/me` | `userService.ts` | matched | Delete account. |
| GET | `/api/v1/admin/articles/pending` | `adminService.ts` | matched | Admin pending list. |
| GET | `/api/v1/articles` | `articleService.ts` | matched | Published article list. |
| GET | `/api/v1/articles/me` | `myArticlesService.ts` | matched | Current user's articles. |
| GET | `/api/v1/articles/slug/{slug}` | `articleService.ts` | matched | Article detail by slug. |
| GET | `/api/v1/articles/{uuid}` | `articleService.ts` | matched | Article detail by UUID. |
| GET | `/api/v1/articles/{articleUuid}/comments` | `commentService.ts` | matched | Comment list. |
| GET | `/api/v1/articles/{uuid}/edit` | `editorService.ts` | matched | Editor load existing article. |
| GET | `/api/v1/auth/verify-email` | `authService.ts` | matched | Email verification. |
| GET | `/api/v1/categories` | `categoryService.ts` | matched | Category list. |
| GET | `/api/v1/categories/{slug}` | `categoryService.ts` | matched | Category detail. |
| GET | `/api/v1/files/{id}` | `fileService.ts` | matched | File metadata. |
| GET | `/api/v1/recommend/related/{articleUuid}` | `recommendService.ts` | matched | Related articles. |
| GET | `/api/v1/recommend/trending` | `recommendService.ts` | matched | Trending articles. |
| GET | `/api/v1/search` | `searchService.ts` | matched | Search results. |
| GET | `/api/v1/search/history` | `searchService.ts` | matched | Search history. |
| GET | `/api/v1/search/suggest` | `searchService.ts` | matched | Search suggestions. |
| GET | `/api/v1/tags/hot` | `tagService.ts` | matched | Hot tags. |
| GET | `/api/v1/tags/suggest` | `tagSuggestService.ts` | matched | Tag suggestions. |
| GET | `/api/v1/tags/{slug}` | `tagService.ts` | matched | Tag detail. |
| GET | `/api/v1/users/me` | `authService.ts` | matched | Current user profile. |
| GET | `/api/v1/users/me/files` | `fileService.ts` | matched | Current user's files. |
| GET | `/api/v1/users/me/quota` | `quotaService.ts` | matched | Upload quota. |
| PATCH | `/api/v1/users/me/profile` | `userService.ts` | matched | Update profile. |
| POST | `/api/v1/articles` | `editorService.ts` | matched | Create article. |
| POST | `/api/v1/articles/{articleUuid}/comments` | `commentService.ts` | matched | Create comment. |
| POST | `/api/v1/articles/{articleUuid}/like` | `articleLikeService.ts` | matched | Article like. |
| POST | `/api/v1/articles/{uuid}/publish` | `adminService.ts` | matched | Publish article. |
| POST | `/api/v1/articles/{uuid}/reject` | `adminService.ts` | matched | Reject article. |
| POST | `/api/v1/articles/{uuid}/submit` | `myArticlesService.ts` | matched | Submit for review. |
| POST | `/api/v1/auth/forgot-password` | `authService.ts` | matched | Request password reset. |
| POST | `/api/v1/auth/login` | `authService.ts` | matched | Login. |
| POST | `/api/v1/auth/logout` | `authService.ts` | matched | Logout. |
| POST | `/api/v1/auth/refresh` | `authService.ts` | matched | Refresh token. |
| POST | `/api/v1/auth/register` | `authService.ts` | matched | Register. |
| POST | `/api/v1/auth/resend-verification` | `authService.ts` | matched | Resend verification email. |
| POST | `/api/v1/auth/reset-password` | `authService.ts` | matched | Reset password. |
| POST | `/api/v1/comments/{uuid}/like` | `commentService.ts` | matched | Comment like. |
| POST | `/api/v1/files/upload` | `fileService.ts` | matched | Upload file. |
| POST | `/api/v1/tags/{id}/follow` | `tagService.ts` | matched | Follow tag. |
| POST | `/api/v1/users/me/change-password` | `userService.ts` | matched | Change password. |
| PUT | `/api/v1/articles/{uuid}` | `editorService.ts` | matched | Update article. |
| PUT | `/api/v1/comments/{uuid}` | `commentService.ts` | matched | Edit comment. |

## E2E Direct Usage

| Method | Path | Evidence | Runtime OpenAPI Status | Notes |
|---|---|---|---|---|
| GET | `/actuator/health` | `e2e/global-setup.ts` | matched outside OpenAPI | Health check is not part of `/v3/api-docs`. |
| POST | `/api/v1/auth/register` | `e2e/global-setup.ts`, integration specs | matched | Seed users and test users. |
| POST | `/api/v1/auth/login` | `e2e/global-setup.ts`, integration specs | matched | Seed/test login. |
| POST | `/api/v1/auth/refresh` | `auth-token-refresh.spec.ts` route assertions | matched | Interceptor/refresh coverage. |
| POST | `/api/v1/auth/logout` | `auth-logout.spec.ts`, `auth-token-refresh.spec.ts` | matched | Logout assertions. |
| GET | `/api/v1/categories` | `global-setup.ts`, integration specs | matched | Seed category/article setup. |
| POST | `/api/v1/admin/categories` | `global-setup.ts` | matched | E2E category seeding uses new runtime `/api/v1/admin` prefix. |
| GET | `/api/v1/articles` | `global-setup.ts`, integration specs | matched | Article list and seed existence checks. |
| POST | `/api/v1/articles` | `global-setup.ts`, integration specs | matched | Seed/test article creation. |
| GET | `/api/v1/articles/me` | `auth-token-refresh.spec.ts` | matched | Auth refresh route assertions. |
| GET | `/api/v1/articles/{uuid}` | `article-slug-api.spec.ts` | matched | UUID detail validation. |
| GET | `/api/v1/articles/slug/{slug}` | `article-slug-api.spec.ts` | matched | Slug detail validation. |
| GET | `/api/v1/articles/{uuid}/edit` | `author-writes-article.spec.ts`, `admin-review.spec.ts` | matched | Editor/admin review validation. |
| POST | `/api/v1/articles/{uuid}/submit` | `global-setup.ts`, integration specs | matched | Review workflow setup. |
| POST | `/api/v1/articles/{uuid}/publish` | `global-setup.ts`, integration specs | matched | Publish workflow setup. |
| DELETE | `/api/v1/articles/{uuid}` | multiple integration specs | matched | Cleanup. |
| POST | `/api/v1/articles/{articleUuid}/like` | `article-like.spec.ts` | matched | Like scenario. |
| POST | `/api/v1/articles/{articleUuid}/comments` | comment integration specs | matched | Comment setup. |
| GET | `/api/v1/search` | `search-advanced.spec.ts` | matched | Search workflow. |
| GET | `/api/v1/search/history` | `search-advanced.spec.ts` | matched | Search history workflow. |
| DELETE | `/api/v1/search/history` | `search-advanced.spec.ts` | matched | Search history cleanup. |
| GET | `/api/v1/tags/suggest` | `global-setup.ts`, `tag-suggest.spec.ts` | matched | Tag suggestion checks. |
| POST | `/api/v1/files/upload` | `author-file-upload.spec.ts` | matched | File upload. |
| DELETE | `/api/v1/files/{id}` | `author-file-upload.spec.ts` | matched | File cleanup. |
| GET | `/api/v1/users/me` | integration specs | matched | Profile/session checks. |
| PATCH | `/api/v1/users/me/profile` | settings specs | matched | Profile update. |
| POST | `/api/v1/users/me/change-password` | `settings.spec.ts` | matched | Password change. |
| DELETE | `/api/v1/users/me` | `settings-delete-account.spec.ts` | matched | Account deletion. |
| GET | `/api/v1/users/me/files` | `author-file-upload.spec.ts` | matched | User file list. |
| GET | `/api/v1/users/me/quota` | `author-file-upload.spec.ts` route assertions | matched | Upload quota UI assertion. |
| GET | `/api/v1/admin/articles/pending` | `admin-review.spec.ts` route stub | matched | Stubbed route still uses runtime path. |

## Front-End Usage Notes

| Finding | Evidence | Classification | Action |
|---|---|---|---|
| Real service endpoints currently match runtime OpenAPI | 50 production `src/api/real/*.ts` calls in `logs/api-contract-frontend-real-usage.raw.txt` map to runtime endpoints. | matched | No path fix required in current real service code for these calls. |
| E2E and app default backend ports differ | `apiClient.ts` defaults to `http://localhost:8080`; `e2e/global-setup.ts` defaults to `http://localhost:9010`. | doc-mismatch | Align documentation/config expectation before running E2E without explicit `VITE_API_BASE_URL`. |
| Some E2E tests hard-code `http://localhost:9010` | `article-slug-api.spec.ts`, `settings-delete-account.spec.ts`, `author-file-upload.spec.ts`, `author-writes-article.spec.ts`. | doc-mismatch | Prefer shared `BACKEND` setting or document the fixed dev backend port. |
| Several runtime APIs have no real service wrapper yet | Bookmark, highlight, reading progress, version preference, article versions, and series endpoints appear in runtime OpenAPI but not `src/api/real`. | frontend-missing | Treat as deferred UI/service coverage unless these features are in current front-end scope. |
