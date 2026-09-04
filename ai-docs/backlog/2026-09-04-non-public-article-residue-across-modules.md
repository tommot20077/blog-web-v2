# Backlog: 非公開文章在各模組的殘留（下架語意的收尾）

- **建立日期**: 2026-09-04
- **來源**: PR「ADMIN-only 文章下架／復原端點」的 code review（`be-archive-review.md` H2 / M1 / M2 / M3）
  與其修復輪（`be-archive-report.md` § Fix round 1）
- **類型**: 語意一致性（「下架 ＝ 從公開面徹底消失」的尾巴）

## 前提：Yuan 拍板的語意

下架（`PUBLISHED → ARCHIVED`）用於**法務／侵權撤下**，文章必須從所有公開面消失，
不是「不再主動推廣」。可見性政策的單一真相是
`blog-common` 的 `ArticleVisibility.isReadableBy`（委派 `ArticleStatus.isPubliclyVisible`）：
PUBLISHED 全公開；非 PUBLISHED 只有**作者本人與 ADMIN**。

## 已解決（2026-09-04）

| 項目 | commit | 位置 |
|---|---|---|
| 留言列表對匿名全公開（HIGH） | `4045b03` | `CommentService.listComments` 前置可見性判斷，不可讀回 200 + 空清單 |
| 收藏列表不濾 status | `131866b` | `BookmarkController.myBookmarks` caller 端過濾 |
| 下架事件送不出／消不掉時靜默 | `5058fd5` | producer / consumer 皆 ERROR + articleUuid + eventId |
| `reindexAll` 沒有補救路徑 | `17d5adc` | 幽靈 document 清除（詳見 `2026-07-29-index-cache-rebuild-completeness.md`） |
| 收藏列表 total 高估／每頁筆數不一致 | （本 PR） | `BookmarkQueryService` 先過濾再分頁；前端 `BookmarksView.vue:25` 以 `pages` 畫分頁器，高估會產生空尾頁 |

## 仍開放

### 1. `recommend:related:*` 快取內嵌已下架文章（≤ 1h）

- Key 是「來源文章」，value 是**其他文章**的摘要清單（`RecommendServiceImpl.java:70,99`，TTL 1h）
- 下架文章 X 會殘留在 `recommend:related:{Y}` 的 value 裡，最長 1 小時
- 照抄一個 `article.archived` consumer 只會刪 `recommend:related:{X}`，那是安全劇場：
  該 key 的讀取入口回的是**別人**的文章
- 真正的修法需要反向索引（哪些 key 的 value 含 X）或讀取端重新水化，屬設計變更
- **硬刪今日亦同**（recommend 模組沒有 `article.deleted` consumer，
  `RecommendRabbitMqConfig` 只綁 `article.published`），故非本次下架新引入
- 同 `findings.md` 的 **DATA-13**

### 2. `tags.usage_count` 含非公開文章（公開顯示）

- `GET /api/v1/tags/hot` / `/all` / `/{slug}` 對匿名顯示 `usageCount`
- 遞增在 `TagUsageConsumer`（訂閱 `article.tagged`，不分 status）
- **`Tag.decrementUsage()` 全 repo 零 production caller**（grep 命中只有定義與 `TagTest`）
  ⇒ 刪除與下架都不遞減，archive **沒有新增不對稱**，屬既有議題
- 要修得連 DRAFT 一起修（計數本來就 status-agnostic）

### 3. 已認證的寫入端點不檢查文章狀態（**需 Yuan 裁示**）

下架後，任何登入者仍可對該文章留言／按讚／收藏／記錄閱讀進度：

| 端點 | file:line |
|---|---|
| `POST /api/v1/articles/{uuid}/comments` | `CommentService.java:62`（`findIdByUuid`，無 status） |
| `POST`／`DELETE /api/v1/articles/{uuid}/like` | `ArticleLikeController.java:61-67` |
| `POST`／`DELETE /api/v1/articles/{uuid}/bookmark` | `BookmarkController.java:41,51` |
| `PUT /api/v1/articles/{uuid}/progress`、`POST .../highlights` | `ReadingProgressService.java:51,76`、`HighlightService.java:34,54` |

- 不構成內容外洩（這些人下架後本來就讀不到文章與留言），但會讓
  `articles.comment_count` / `like_count` 繼續變動，且 200 vs A0201 的差異
  對**已登入者**仍是存在性探測器
- 之所以沒在修復輪一併處理：這是對既有端點的**語意變更**，同時牽動前端
  （前端目前預期這些呼叫恆成功），屬 `judgment.md` §5「停下問 Yuan」
- 修法與已解決的兩條相同：caller 端 `ArticleVisibility` 判斷 + 回 `A0201`

### 4. 下架仍無排程對帳／DLQ 自動重送

ES 索引移除失敗後的補救是**對帳式**的，需要有人去按 admin 的「重建索引」。
完整方案（alias 切換、排程對帳）見 `2026-07-29-index-cache-rebuild-completeness.md`。

## 驗收線索

- 下架文章後：`GET /api/v1/articles/{uuid}/comments` 匿名回 200 空清單（已有 IT）
- 下架文章後：`GET /api/v1/users/me/bookmarks` 不含該文（已有 IT）
- 開放項 3 若動：登入者對 ARCHIVED 文章 `POST` 留言應回 `A0201`
- 開放項 1 若動：下架後立即查 `GET /api/v1/recommend/related/{其他文章}` 不含該文
