# API Contract Audit Design

## 1. Goal

建立前後端 API 規格差異報告，範圍涵蓋所有後端 API。

這一輪只做 audit 與修正設計，不直接修改前端 service 或後端 controller。後續修正要另走 implementation plan，且遵守 TDD：先寫失敗測試，再改實作。

## 2. Truth Source

主要真相來源是 dev profile 啟動後端後產出的 runtime OpenAPI：

```bash
mvn -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev
curl http://localhost:9010/v3/api-docs > logs/api-contract-openapi-runtime.json
```

已確認 dev profile 設定：

- `blog-start/src/main/resources/application-dev.yaml`
- `server.port=9010`
- `springdoc.api-docs.path=/v3/api-docs`

Controller 靜態掃描作為第二層防漏驗證，用來確認 runtime OpenAPI 是否漏掉 controller endpoint。

## 3. Compared Sources

Audit 會比對四個來源：

1. Runtime OpenAPI：`logs/api-contract-openapi-runtime.json`
2. 後端 controller：所有 `@RequestMapping` 與 method mapping annotations
3. 前端 real service：`D:\end\workspace\vue\blog-web-v2-front-end\src\api\real\*.ts`
4. 前端直接 API 使用：E2E/global setup/direct `fetch` 或 Playwright `request`

既有前端 `api-reference/openapi.json` 不當作 truth source。它是被檢查對象，用來判斷文件是否過期。

## 4. Outputs

後續 implementation plan 預期產出三份文件：

- `docs/api-contract/backend-endpoints.md`
- `docs/api-contract/frontend-usage.md`
- `docs/api-contract/gap-report.md`

`backend-endpoints.md` 記錄 runtime OpenAPI 與 controller scan 的後端 endpoint matrix。

`frontend-usage.md` 記錄前端 service 與 E2E 直接呼叫的 endpoint。

`gap-report.md` 記錄差異、證據、優先級與建議修正方式。

## 5. Classification

每個 endpoint 會被分類：

- `matched`：前端 service 已對接，method/path 相符。
- `frontend-missing`：後端有 API，但前端 real service 沒有。
- `frontend-extra`：前端有呼叫，但後端 runtime OpenAPI/controller 都沒有。
- `path-mismatch`：語意相同但 path 不一致。
- `doc-mismatch`：既有 `api-reference/openapi.json` 與 runtime OpenAPI 不一致。
- `not-yet-planned`：後端已有，但前端尚無頁面或流程，可暫緩。

優先級使用：

- `必修正`：會造成前端功能失敗、路徑錯誤、method 錯誤、request/response contract 明顯不一致。
- `文件需更新`：runtime 正確，但前端 `api-reference/openapi.json` 過期。
- `可暫緩`：後端 API 已存在，但前端目前沒有使用場景。

## 6. Normalization Rules

比對前先標準化 endpoint：

- `/api/v1/articles/${uuid}` 與 `/api/v1/articles/{uuid}` 視為同一路徑樣式。
- path variable 名稱不同但位置與語意一致時，列為 matched 並註明 alias。
- query params 另外列出，不只用 path 判斷。例如 `page`、`size`、`status`、`categorySlug`、`q`、`sort`。
- 前端 wrapper service 與 E2E direct request 分開記錄，避免把測試 seed helper 誤認為正式 service 對接。

## 7. Known Initial Findings

初步探索已看到以下風險，正式 audit 需用 runtime OpenAPI 驗證：

- 既有前端 `api-reference/openapi.json` 出現 `/api/admin/...`，但後端 controller 實際使用 `/api/v1/admin/...`，可能是 `doc-mismatch`。
- 後端已有 `series`、`reading progress`、`bookmark`、`highlight`、`version`、`preference` API；前端 real service 尚未看到完整對接，可能是 `frontend-missing` 或 `not-yet-planned`。

## 8. Execution Design

1. 啟動後端 dev profile。
2. 確認 `GET http://localhost:9010/actuator/health` 正常。
3. 抓取 runtime OpenAPI 到 `logs/api-contract-openapi-runtime.json`。
4. 用 `jq` 匯出 `METHOD path` 清單。
5. 用 `rg` 掃 controller mapping，匯出靜態 endpoint 清單。
6. 用 `rg` 掃前端 real service 的 `apiClient.get/post/put/patch/delete`。
7. 用 `rg` 掃 E2E direct API usage。
8. 比對 runtime OpenAPI、controller scan、frontend usage、既有 `api-reference/openapi.json`。
9. 輸出三份 audit 文件。

## 9. Error Handling

如果 dev profile 後端無法啟動，先回報缺少的外部依賴，例如 PostgreSQL、Redis、RabbitMQ、Elasticsearch 或 MinIO。

如果 `/v3/api-docs` 無法取得，但 controller scan 可取得 endpoint，gap report 會標記 runtime OpenAPI 取得失敗，不以過期 `api-reference/openapi.json` 取代 truth source。

如果 runtime OpenAPI 與 controller scan 不一致，優先調查 Springdoc 是否被 security、package scan、annotation 或 conditional bean 影響。

## 10. Verification

Audit 文件本身以可重跑命令驗證，不跑完整測試套件，因為此階段不改實作。

後續若進入修正階段：

- 前端 service 修正要先補 Vitest 失敗測試。
- 後端 contract 或 OpenAPI generation 修正要先補對應 controller/service test。
- 測試輸出依 repo 規範寫入 `logs/`。

## 11. Out of Scope

這份設計不包含：

- 直接補前端所有 missing service。
- 改後端 API path 或 response shape。
- 重建 OpenAPI generator pipeline。
- 補 UI 頁面或互動流程。

這些會在 gap report 完成後，依優先級進入下一份 implementation plan。
