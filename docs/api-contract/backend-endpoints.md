# Backend Endpoints

## Source

- Primary: `logs/api-contract-openapi-runtime.json`
- Runtime endpoint list: `logs/api-contract-runtime-endpoints.txt`
- Controller cross-check: `logs/api-contract-controller-mappings.raw.txt`
- Backend profile: `dev`
- Runtime base URL: `http://localhost:9010`
- Runtime capture note: `spring.flyway.enabled=false` was used because normal dev startup hit a Flyway V13 checksum mismatch; see `logs/api-contract-backend-dev.log`.
- Test-only mappings from `src/test/java` are preserved in raw evidence but excluded from the production controller cross-check table.

## Runtime OpenAPI Endpoint Matrix

| Method | Path | OpenAPI operationId | Tags | Notes |
|---|---|---|---|---|
| DELETE | `/api/v1/admin/categories/{uuid}` | `deleteCategory` | admin-category-controller |  |
| DELETE | `/api/v1/admin/tags/{id}` | `deleteTag` | admin-tag-controller |  |
| DELETE | `/api/v1/articles/{articleUuid}/bookmark` | `unbookmark` | Bookmark |  |
| DELETE | `/api/v1/articles/{articleUuid}/like` | `unlike_1` | Article Like |  |
| DELETE | `/api/v1/articles/{articleUuid}/versions/{versionUuid}` | `delete_3` | Version |  |
| DELETE | `/api/v1/articles/{uuid}` | `deleteArticle` | article-controller |  |
| DELETE | `/api/v1/comments/{uuid}` | `delete_2` | Comment |  |
| DELETE | `/api/v1/comments/{uuid}/like` | `unlike` | Comment Like |  |
| DELETE | `/api/v1/files/{id}` | `deleteFile` | file-controller |  |
| DELETE | `/api/v1/highlights/{uuid}` | `delete_1` | Highlight |  |
| DELETE | `/api/v1/me/preferences/version/{key}` | `reset` | Version Preference |  |
| DELETE | `/api/v1/search/history` | `clearHistory` | search-controller |  |
| DELETE | `/api/v1/series/{uuid}` | `delete` | Series |  |
| DELETE | `/api/v1/series/{uuid}/articles/{articleUuid}` | `removeArticle` | Series |  |
| DELETE | `/api/v1/tags/{id}/follow` | `unfollowTag` | tag-controller |  |
| DELETE | `/api/v1/users/me` | `deleteAccount` | User |  |
| GET | `/api/v1/admin/articles/pending` | `getPendingArticles` | admin-article-controller |  |
| GET | `/api/v1/articles` | `getPublishedArticles` | article-controller |  |
| GET | `/api/v1/articles/me` | `getMyArticles` | article-controller |  |
| GET | `/api/v1/articles/slug/{slug}` | `getArticleBySlug` | article-controller |  |
| GET | `/api/v1/articles/{articleUuid}/comments` | `list_2` | Comment |  |
| GET | `/api/v1/articles/{articleUuid}/highlights` | `list_1` | Highlight |  |
| GET | `/api/v1/articles/{articleUuid}/progress` | `get_1` | Reading Progress |  |
| GET | `/api/v1/articles/{articleUuid}/versions` | `list_3` | Version |  |
| GET | `/api/v1/articles/{articleUuid}/versions/{versionUuid}` | `getDetail` | Version |  |
| GET | `/api/v1/articles/{uuid}` | `getArticle` | article-controller |  |
| GET | `/api/v1/articles/{uuid}/edit` | `getArticleForEdit` | article-controller |  |
| GET | `/api/v1/auth/verify-email` | `verifyEmail` | Auth |  |
| GET | `/api/v1/categories` | `getAllCategories` | category-controller |  |
| GET | `/api/v1/categories/{slug}` | `getCategoryBySlug` | category-controller |  |
| GET | `/api/v1/files/{id}` | `getFileMetadata` | file-controller |  |
| GET | `/api/v1/me/preferences/version` | `get` | Version Preference |  |
| GET | `/api/v1/recommend/related/{articleUuid}` | `getRelatedArticles` | recommend-controller |  |
| GET | `/api/v1/recommend/trending` | `getTrendingArticles` | recommend-controller |  |
| GET | `/api/v1/search` | `search` | search-controller |  |
| GET | `/api/v1/search/history` | `getHistory` | search-controller |  |
| GET | `/api/v1/search/suggest` | `suggest_1` | search-controller |  |
| GET | `/api/v1/series` | `list` | Series |  |
| GET | `/api/v1/series/{slug}` | `get_2` | Series |  |
| GET | `/api/v1/tags/hot` | `getHotTags` | tag-controller |  |
| GET | `/api/v1/tags/suggest` | `suggest` | tag-controller |  |
| GET | `/api/v1/tags/{slug}` | `getTagDetail` | tag-controller |  |
| GET | `/api/v1/users/me` | `getMe` | User |  |
| GET | `/api/v1/users/me/bookmarks` | `myBookmarks` | Bookmark |  |
| GET | `/api/v1/users/me/files` | `getUserFiles` | file-controller |  |
| GET | `/api/v1/users/me/quota` | `getQuota` | file-controller |  |
| PATCH | `/api/v1/users/me/profile` | `updateProfile` | User |  |
| POST | `/api/v1/admin/categories` | `createCategory` | admin-category-controller |  |
| POST | `/api/v1/admin/search/reindex` | `reindex` | admin-search-controller |  |
| POST | `/api/v1/articles` | `createArticle` | article-controller |  |
| POST | `/api/v1/articles/{articleUuid}/bookmark` | `bookmark` | Bookmark |  |
| POST | `/api/v1/articles/{articleUuid}/comments` | `create_2` | Comment |  |
| POST | `/api/v1/articles/{articleUuid}/highlights` | `create_1` | Highlight |  |
| POST | `/api/v1/articles/{articleUuid}/like` | `like_1` | Article Like |  |
| POST | `/api/v1/articles/{articleUuid}/versions/manual` | `createManual` | Version |  |
| POST | `/api/v1/articles/{articleUuid}/versions/{versionUuid}/promote` | `promote` | Version |  |
| POST | `/api/v1/articles/{articleUuid}/versions/{versionUuid}/restore` | `restore` | Version |  |
| POST | `/api/v1/articles/{uuid}/publish` | `publishArticle` | article-controller |  |
| POST | `/api/v1/articles/{uuid}/reject` | `rejectArticle` | article-controller |  |
| POST | `/api/v1/articles/{uuid}/submit` | `submitForReview` | article-controller |  |
| POST | `/api/v1/auth/forgot-password` | `forgotPassword` | Auth |  |
| POST | `/api/v1/auth/login` | `login` | Auth |  |
| POST | `/api/v1/auth/logout` | `logout` | Auth |  |
| POST | `/api/v1/auth/refresh` | `refresh` | Auth |  |
| POST | `/api/v1/auth/register` | `register` | Auth |  |
| POST | `/api/v1/auth/resend-verification` | `resendVerification` | Auth |  |
| POST | `/api/v1/auth/reset-password` | `resetPassword` | Auth |  |
| POST | `/api/v1/comments/{uuid}/like` | `like` | Comment Like |  |
| POST | `/api/v1/files/upload` | `uploadFile` | file-controller |  |
| POST | `/api/v1/series` | `create` | Series |  |
| POST | `/api/v1/tags/{id}/follow` | `followTag` | tag-controller |  |
| POST | `/api/v1/users/me/change-password` | `changePassword` | User |  |
| PUT | `/api/v1/admin/categories/{uuid}` | `updateCategory` | admin-category-controller |  |
| PUT | `/api/v1/admin/tags/{id}` | `updateTag` | admin-tag-controller |  |
| PUT | `/api/v1/articles/{articleUuid}/progress` | `update_3` | Reading Progress |  |
| PUT | `/api/v1/articles/{uuid}` | `updateArticle` | article-controller |  |
| PUT | `/api/v1/comments/{uuid}` | `edit` | Comment |  |
| PUT | `/api/v1/highlights/{uuid}` | `update_2` | Highlight |  |
| PUT | `/api/v1/me/preferences/version` | `update_1` | Version Preference |  |
| PUT | `/api/v1/series/{uuid}` | `update` | Series |  |
| PUT | `/api/v1/series/{uuid}/articles/{articleUuid}` | `addArticle` | Series |  |

## Controller Cross-Check

| Controller | Base Path | Method Mapping | Effective Path | Runtime OpenAPI Status |
|---|---|---|---|---|
| `AdminCategoryController` | `/api/v1/admin/categories` | `DeleteMapping(/{uuid})` | `DELETE /api/v1/admin/categories/{uuid}` | matched |
| `AdminTagController` | `/api/v1/admin/tags` | `DeleteMapping(/{id})` | `DELETE /api/v1/admin/tags/{id}` | matched |
| `BookmarkController` | `/api/v1` | `DeleteMapping(/articles/{articleUuid}/bookmark)` | `DELETE /api/v1/articles/{articleUuid}/bookmark` | matched |
| `ArticleLikeController` | `/api/v1/articles/{articleUuid}/like` | `DeleteMapping()` | `DELETE /api/v1/articles/{articleUuid}/like` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `DeleteMapping(/{versionUuid})` | `DELETE /api/v1/articles/{articleUuid}/versions/{versionUuid}` | matched |
| `ArticleController` | `/api/v1/articles` | `DeleteMapping(/{uuid})` | `DELETE /api/v1/articles/{uuid}` | matched |
| `CommentController` | `/api/v1` | `DeleteMapping(/comments/{uuid})` | `DELETE /api/v1/comments/{uuid}` | matched |
| `CommentLikeController` | `/api/v1/comments/{uuid}/like` | `DeleteMapping()` | `DELETE /api/v1/comments/{uuid}/like` | matched |
| `FileController` | `-` | `DeleteMapping(/api/v1/files/{id})` | `DELETE /api/v1/files/{id}` | matched |
| `HighlightController` | `/api/v1` | `DeleteMapping(/highlights/{uuid})` | `DELETE /api/v1/highlights/{uuid}` | matched |
| `PreferenceController` | `/api/v1/me/preferences/version` | `DeleteMapping(/{key})` | `DELETE /api/v1/me/preferences/version/{key}` | matched |
| `SearchController` | `/api/v1/search` | `DeleteMapping(/history)` | `DELETE /api/v1/search/history` | matched |
| `SeriesController` | `/api/v1/series` | `DeleteMapping(/{uuid})` | `DELETE /api/v1/series/{uuid}` | matched |
| `SeriesController` | `/api/v1/series` | `DeleteMapping(/{uuid}/articles/{articleUuid})` | `DELETE /api/v1/series/{uuid}/articles/{articleUuid}` | matched |
| `TagController` | `/api/v1/tags` | `DeleteMapping(/{id}/follow)` | `DELETE /api/v1/tags/{id}/follow` | matched |
| `UserController` | `/api/v1/users` | `DeleteMapping(/me)` | `DELETE /api/v1/users/me` | matched |
| `AdminArticleController` | `/api/v1/admin/articles` | `GetMapping(/pending)` | `GET /api/v1/admin/articles/pending` | matched |
| `ArticleController` | `/api/v1/articles` | `GetMapping()` | `GET /api/v1/articles` | matched |
| `ArticleController` | `/api/v1/articles` | `GetMapping(/me)` | `GET /api/v1/articles/me` | matched |
| `ArticleController` | `/api/v1/articles` | `GetMapping(/slug/{slug})` | `GET /api/v1/articles/slug/{slug}` | matched |
| `CommentController` | `/api/v1` | `GetMapping(/articles/{articleUuid}/comments)` | `GET /api/v1/articles/{articleUuid}/comments` | matched |
| `HighlightController` | `/api/v1` | `GetMapping(/articles/{articleUuid}/highlights)` | `GET /api/v1/articles/{articleUuid}/highlights` | matched |
| `ReadingProgressController` | `/api/v1/articles/{articleUuid}/progress` | `GetMapping()` | `GET /api/v1/articles/{articleUuid}/progress` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `GetMapping()` | `GET /api/v1/articles/{articleUuid}/versions` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `GetMapping(/{versionUuid})` | `GET /api/v1/articles/{articleUuid}/versions/{versionUuid}` | matched |
| `ArticleController` | `/api/v1/articles` | `GetMapping(/{uuid})` | `GET /api/v1/articles/{uuid}` | matched |
| `ArticleController` | `/api/v1/articles` | `GetMapping(/{uuid}/edit)` | `GET /api/v1/articles/{uuid}/edit` | matched |
| `AuthController` | `/api/v1/auth` | `GetMapping(/verify-email)` | `GET /api/v1/auth/verify-email` | matched |
| `CategoryController` | `/api/v1/categories` | `GetMapping()` | `GET /api/v1/categories` | matched |
| `CategoryController` | `/api/v1/categories` | `GetMapping(/{slug})` | `GET /api/v1/categories/{slug}` | matched |
| `FileController` | `-` | `GetMapping(/api/v1/files/{id})` | `GET /api/v1/files/{id}` | matched |
| `PreferenceController` | `/api/v1/me/preferences/version` | `GetMapping()` | `GET /api/v1/me/preferences/version` | matched |
| `RecommendController` | `/api/v1/recommend` | `GetMapping(/related/{articleUuid})` | `GET /api/v1/recommend/related/{articleUuid}` | matched |
| `RecommendController` | `/api/v1/recommend` | `GetMapping(/trending)` | `GET /api/v1/recommend/trending` | matched |
| `SearchController` | `/api/v1/search` | `GetMapping()` | `GET /api/v1/search` | matched |
| `SearchController` | `/api/v1/search` | `GetMapping(/history)` | `GET /api/v1/search/history` | matched |
| `SearchController` | `/api/v1/search` | `GetMapping(/suggest)` | `GET /api/v1/search/suggest` | matched |
| `SeriesController` | `/api/v1/series` | `GetMapping()` | `GET /api/v1/series` | matched |
| `SeriesController` | `/api/v1/series` | `GetMapping(/{slug})` | `GET /api/v1/series/{slug}` | matched |
| `TagController` | `/api/v1/tags` | `GetMapping(/hot)` | `GET /api/v1/tags/hot` | matched |
| `TagController` | `/api/v1/tags` | `GetMapping(/suggest)` | `GET /api/v1/tags/suggest` | matched |
| `TagController` | `/api/v1/tags` | `GetMapping(/{slug})` | `GET /api/v1/tags/{slug}` | matched |
| `UserController` | `/api/v1/users` | `GetMapping(/me)` | `GET /api/v1/users/me` | matched |
| `BookmarkController` | `/api/v1` | `GetMapping(/users/me/bookmarks)` | `GET /api/v1/users/me/bookmarks` | matched |
| `FileController` | `-` | `GetMapping(/api/v1/users/me/files)` | `GET /api/v1/users/me/files` | matched |
| `FileController` | `-` | `GetMapping(/api/v1/users/me/quota)` | `GET /api/v1/users/me/quota` | matched |
| `UserController` | `/api/v1/users` | `PatchMapping(/me/profile)` | `PATCH /api/v1/users/me/profile` | matched |
| `AdminCategoryController` | `/api/v1/admin/categories` | `PostMapping()` | `POST /api/v1/admin/categories` | matched |
| `AdminSearchController` | `/api/v1/admin/search` | `PostMapping(/reindex)` | `POST /api/v1/admin/search/reindex` | matched |
| `ArticleController` | `/api/v1/articles` | `PostMapping()` | `POST /api/v1/articles` | matched |
| `BookmarkController` | `/api/v1` | `PostMapping(/articles/{articleUuid}/bookmark)` | `POST /api/v1/articles/{articleUuid}/bookmark` | matched |
| `CommentController` | `/api/v1` | `PostMapping(/articles/{articleUuid}/comments)` | `POST /api/v1/articles/{articleUuid}/comments` | matched |
| `HighlightController` | `/api/v1` | `PostMapping(/articles/{articleUuid}/highlights)` | `POST /api/v1/articles/{articleUuid}/highlights` | matched |
| `ArticleLikeController` | `/api/v1/articles/{articleUuid}/like` | `PostMapping()` | `POST /api/v1/articles/{articleUuid}/like` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `PostMapping(/manual)` | `POST /api/v1/articles/{articleUuid}/versions/manual` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `PostMapping(/{versionUuid}/promote)` | `POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/promote` | matched |
| `VersionController` | `/api/v1/articles/{articleUuid}/versions` | `PostMapping(/{versionUuid}/restore)` | `POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/restore` | matched |
| `ArticleController` | `/api/v1/articles` | `PostMapping(/{uuid}/publish)` | `POST /api/v1/articles/{uuid}/publish` | matched |
| `ArticleController` | `/api/v1/articles` | `PostMapping(/{uuid}/reject)` | `POST /api/v1/articles/{uuid}/reject` | matched |
| `ArticleController` | `/api/v1/articles` | `PostMapping(/{uuid}/submit)` | `POST /api/v1/articles/{uuid}/submit` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/forgot-password)` | `POST /api/v1/auth/forgot-password` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/login)` | `POST /api/v1/auth/login` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/logout)` | `POST /api/v1/auth/logout` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/refresh)` | `POST /api/v1/auth/refresh` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/register)` | `POST /api/v1/auth/register` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/resend-verification)` | `POST /api/v1/auth/resend-verification` | matched |
| `AuthController` | `/api/v1/auth` | `PostMapping(/reset-password)` | `POST /api/v1/auth/reset-password` | matched |
| `CommentLikeController` | `/api/v1/comments/{uuid}/like` | `PostMapping()` | `POST /api/v1/comments/{uuid}/like` | matched |
| `FileController` | `-` | `PostMapping(/api/v1/files/upload)` | `POST /api/v1/files/upload` | matched |
| `SeriesController` | `/api/v1/series` | `PostMapping()` | `POST /api/v1/series` | matched |
| `TagController` | `/api/v1/tags` | `PostMapping(/{id}/follow)` | `POST /api/v1/tags/{id}/follow` | matched |
| `UserController` | `/api/v1/users` | `PostMapping(/me/change-password)` | `POST /api/v1/users/me/change-password` | matched |
| `AdminCategoryController` | `/api/v1/admin/categories` | `PutMapping(/{uuid})` | `PUT /api/v1/admin/categories/{uuid}` | matched |
| `AdminTagController` | `/api/v1/admin/tags` | `PutMapping(/{id})` | `PUT /api/v1/admin/tags/{id}` | matched |
| `ReadingProgressController` | `/api/v1/articles/{articleUuid}/progress` | `PutMapping()` | `PUT /api/v1/articles/{articleUuid}/progress` | matched |
| `ArticleController` | `/api/v1/articles` | `PutMapping(/{uuid})` | `PUT /api/v1/articles/{uuid}` | matched |
| `CommentController` | `/api/v1` | `PutMapping(/comments/{uuid})` | `PUT /api/v1/comments/{uuid}` | matched |
| `HighlightController` | `/api/v1` | `PutMapping(/highlights/{uuid})` | `PUT /api/v1/highlights/{uuid}` | matched |
| `PreferenceController` | `/api/v1/me/preferences/version` | `PutMapping()` | `PUT /api/v1/me/preferences/version` | matched |
| `SeriesController` | `/api/v1/series` | `PutMapping(/{uuid})` | `PUT /api/v1/series/{uuid}` | matched |
| `SeriesController` | `/api/v1/series` | `PutMapping(/{uuid}/articles/{articleUuid})` | `PUT /api/v1/series/{uuid}/articles/{articleUuid}` | matched |

## Controller/OpenAPI Differences

| Difference | Evidence | Classification | Action |
|---|---|---|---|
| No production controller/runtime OpenAPI mismatch found | 81 production controller mappings matched 81 runtime OpenAPI operations; `controller-only=0`, `runtime-openapi-only=0`. | matched | No contract action required for Task 2. |
| Raw evidence includes test-only security mappings | `logs/api-contract-controller-mappings.raw.txt` includes `blog-infrastructure/src/test/java/dowob/xyz/blog/infrastructure/config/SecurityConfigTest.java`; these are excluded from production cross-check. | matched | Keep raw evidence intact; do not treat test controller mappings as production endpoints. |
| Runtime capture required Flyway bypass | `logs/api-contract-backend-dev.log` records dev runtime capture with `spring.flyway.enabled=false` because normal dev startup hit Flyway V13 checksum mismatch. | needs-investigation | Track Flyway checksum mismatch outside Task 2; endpoint evidence is still from runtime OpenAPI under dev profile. |
