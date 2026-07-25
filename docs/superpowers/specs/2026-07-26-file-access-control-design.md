# 檔案存取控制：草稿圖片不公開

> 日期：2026-07-26
> 範圍：`blog-module-file`、`blog-infrastructure`（新 Facade）、`blog-module-article`、`blog-db-migration`
> 狀態：設計定案（Yuan 已選定「代理端點 + 302 轉簽名網址」），待實作

---

## 1. 問題

上傳的圖片目前是**瀏覽器直連 MinIO** 取得：

```java
// FileServiceImpl.java:178
String url = minioEndpoint + "/" + bucketName + "/" + storagePath;
```

這條路徑**完全繞過 Spring 後端**，沒有任何權限關卡。後果是二選一：

- bucket 私有 → 所有圖片都 403（原始 bug 現象）
- bucket 公開 → **草稿／未發布文章的圖片，只要網址外流即人人可讀，且永久有效**

Yuan 的要求是後者不可接受：**只有已發布文章的圖片才該公開**。

## 2. 為什麼這是 feature 而非 fix：地基不存在

盤點確認三件事：

1. **「檔案 → 文章」關聯在 schema 層級不存在。**
   `files` 表（V1，含 `reference_id`/`reference_type`）**全 Java 程式碼零引用，是死表**；實際使用的是 `file_metadata`（V8），欄位只有
   `id / original_name / storage_path / content_type / size / width / height / usage_type / has_thumbnail / uploader_id / created_at`——**沒有任何 reference 欄位**。
   `articles.cover_image_url` 是純字串非 FK；`blog-module-article` 對 `FileService` 的引用為 0。
   後端從未結構化記錄「這張圖屬於哪篇文章」。

2. **沒有可插入權限檢查的後端路徑。**
   `GET /api/v1/files/{id}` 只回 JSON metadata，且 `SecurityConfig:88` 對 `GET /api/v1/files/**` 全部 `permitAll`（無 `@PreAuthorize`）。圖片本體從不經過後端。

3. **網址寫死在 markdown 內文。**
   `![alt](url)` 的 URL 存進 `articles.content`，因此**對外網址必須長期穩定**——不能直接發放會過期的簽名網址，否則文章內文會整片破圖。

4. **無 FileFacade。** 現有 facade 有 Article/Reading/Search/Series/Tag/User，唯獨沒有 File。跨模組綁定需新增。

## 3. 方案：代理端點 + 302 轉簽名網址

```
瀏覽器 → GET /api/v1/files/{id}/content   （對外網址，永久穩定，可寫進 markdown）
          ↓ 後端做權限判斷
          ↓ 通過 → 302 Location: <MinIO presigned URL，短效>
瀏覽器 → 直接向 MinIO 取圖（bytes 不經過應用伺服器）
```

**為何選這個**：
- 對外網址穩定 → 可安全寫入 markdown（解決 §2.3）
- bucket 維持**完全私有** → 無任何匿名可讀路徑
- 權限在後端判斷 → 真正的存取控制（非 UUID 難猜的 obscurity）
- 302 而非串流 → 圖片流量不經過應用伺服器，避免頻寬與 CPU 成本

## 4. 授權規則

`GET /api/v1/files/{id}/content` 依序判斷，**任一成立即放行**：

| # | 條件 | 理由 |
|---|---|---|
| 1 | `usage_type = AVATAR` | 使用者頭像屬公開個人資料 |
| 2 | 已綁定文章且該文章 `status = PUBLISHED` | 已發布內容本就公開 |
| 3 | 請求者 = `uploader_id` | 作者永遠看得到自己的檔案（含編輯中的草稿） |
| 4 | 請求者具 ADMIN 權限 | 後台審核需要看到草稿內容 |

皆不成立 → **403**。檔案不存在 → **404**。

**未綁定檔案（`article_uuid IS NULL`）預設為私有**，只有上傳者與 ADMIN 可讀。這是 fail-safe：新文章尚未儲存時上傳的圖片不會外洩。

## 5. 綁定時機（兩段式）

上傳當下文章可能尚未存在（新文章未儲存），因此：

1. **上傳時**：`uploadFile` 接受**可選**的 `articleUuid`；有值即綁定。
2. **文章儲存時**：create/update 文章後，掃描 `content` 中出現的 `/api/v1/files/{uuid}/content`，將這些檔案綁定至該文章。

第 2 步是必要的——否則「先傳圖、後存檔」的檔案永遠停在未綁定狀態，文章發布後讀者仍看不到圖。

**跨模組**：`blog-module-article` 不得直接存取 file 的 repository（architecture.md：「Modules interact ONLY via Service Interfaces」）。新增 `FileFacade`（介面定義於 `blog-infrastructure`，實作於 `blog-module-file`），提供 `bindFilesToArticle(articleUuid, fileUuids)`。

## 6. Schema（V20）

> develop 目前最大為 V18；`feature/article-toc` 已佔用 V19（`articles.toc`）。本批次使用 **V20**。

```sql
ALTER TABLE file_metadata ADD COLUMN article_uuid UUID;
CREATE INDEX idx_file_metadata_article_uuid ON file_metadata (article_uuid);
```

- nullable（未綁定 = 私有）
- **刻意不設 FK**：跨模組邊界，避免 file 模組與 article 表產生資料庫層耦合（與 `articles.cover_image_url` 用字串而非 FK 的既有取捨一致）
- 依 CLAUDE.md 強制規定，**必須同步更新 `ai-docs/schema.md`**（file_metadata 欄位區塊 + Migration Index 補 V20）

## 7. 其他變更

- **`uploadFile` 回傳的 url** 改為 `{對外 base}/api/v1/files/{id}/content`，不再回傳 MinIO 直連網址。
- **`MinioConfig`**：**不得**設定 bucket 為公開；bucket 維持私有。（本機先前為了應急曾手動 `mc anonymous set download`，需一併還原為 private。）
- **`SecurityConfig:88`** 的 `GET /api/v1/files/**` permitAll 需重新檢視：新端點需允許匿名到達（才能判斷規則 1、2），但判斷邏輯在 service 層，不可因 permitAll 就跳過檢查。
- **presigned URL 效期**：建議 5 分鐘（夠瀏覽器完成一次載入，外流也很快失效）。

## 8. 既有資料

站台尚未上線，現有圖片皆為測試資料。既有 markdown 中的 MinIO 直連網址**不做遷移**，直接重新上傳／重新 seed。與 [[content-html-rerender-path]] 同一個判斷基準：無正式資料時不值得為相容性付出代價。

## 9. 測試策略

TDD 強制。重點場景：

**授權矩陣**（每條規則各一，且必須有反例）
- AVATAR 匿名可讀
- 已綁定 + PUBLISHED → 匿名可讀
- 已綁定 + DRAFT → **匿名 403**、**他人 403**、上傳者 200、ADMIN 200
- 未綁定 → **匿名 403**、上傳者 200
- 檔案不存在 → 404

**綁定**
- 上傳帶 articleUuid → 即時綁定
- 文章儲存時掃描 content → 綁定其中出現的檔案
- 文章 update 移除某圖後，該檔案**不再**被該文章綁定（避免權限殘留）

**其他**
- 回應為 302 且 Location 指向 presigned URL（不可回傳圖片 bytes）
- bucket policy 非公開（回歸測試，防止日後有人為了方便改回 public-read）

## 10. 範圍外

- 既有圖片的網址遷移（見 §8）
- 圖片對齊、側欄圖片清單（編輯器 UI 項目，另案）
- CDN／快取策略（302 + presigned 天然對 CDN 不友善，上線前若有需求另議）
- `files` 死表的清理（獨立的技術債，不在此批次）
