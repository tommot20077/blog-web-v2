# Backlog: 安全信 token 改走 URL fragment（`#token=`）以徹底堵住原則 8「瀏覽器歷史/access log」洩漏

**狀態**:TODO
**優先級**:MEDIUM（安全深度；非阻斷，CRITICAL/HIGH 已於 #48 + 前端 #38 清空）
**來源**:
- `ai-docs/backlog/2026-07-14-full-review-findings.md` H6 §「殘留限制(未解)」(:170) —— 該處已明列此為未解殘留並「建議另開項目追蹤」
- PR [#48](https://github.com/tommot20077/blog-web-v2/pull/48) code review（2026-07-18）altitude findings 第 1 項
**規範依據**:`ai-docs/security.md` 原則 8（CRITICAL）——「密碼、token、任何憑證必須放 `@RequestBody`，禁止 query string」，立法理由為「會被 **access log 與瀏覽器歷史記錄**」（源自 BUG-2026-001 U-1）

## 問題描述

原則 8 的立法理由是防止 token 進入 **access log 與瀏覽器歷史**。PR #48 已把後端 `verifyEmail` 端點改為 `POST` + body，堵住**後端 API** 這一條洩漏線；前端 #38 也在讀入 token 後 `router.replace` 清掉網址參數，緩解了瀏覽器**歷史紀錄**。

但 token 仍以 query string 出現在信件連結本身：

- `blog-module-user/.../service/UserMailService.java:101` — `buildUrl` 產生 `{frontendBaseUrl}{path}?token={token}`
  - `sendVerificationEmail`(:51):`buildUrl("/verify-email", ...)`
  - `sendPasswordResetEmail`(:73):`buildUrl("/reset-password", ...)` ← **同型問題,一併處理**

### 殘留洩漏向量（`router.replace` 無法消除）

1. **前端靜態站台 access log**:使用者點信件連結時,`GET /verify-email?token=...` 這**第一次頁面請求**必定送達 nginx/CDN,完整帶 token 進其 access log。`replaceState` 只能改瀏覽器歷史,無法回收這一次已送出的請求。
2. **Referer 外洩**:該頁初次載入時若引用任何第三方資源,完整 URL(含 token)可能經 `Referer` 標頭洩漏。

## 建議修法（後端 + 前端須協同,BREAKING）

把信件連結的 token 從 query string 改為 **URL fragment**(`#token=`)。fragment 不會送到伺服器,故不進任何 access log、也不出現在 Referer。

- **後端**:`UserMailService.buildUrl` 改產生 `...{path}#token={token}`(verify-email 與 reset-password 皆改)
- **前端**:對應頁面改從 `location.hash` 解析 token(而非 `route.query`);沿用讀後清除的作法
- **相容性**:已寄出的舊信仍是 `?token=`,前端解析需**同時支援 hash 與 query 一段過渡期**,或評估舊信 24h(verify)/15min(reset)過期後即淘汰的自然 grace period

## 驗收條件

- 新寄出的驗證信 / 密碼重設信連結為 `#token=` 形狀,token 不出現在 query string
- 前端能從 `location.hash` 正確取得 token 完成驗證 / 重設,讀後清除
- 過渡期:舊 `?token=` 連結在前端仍可解析(或已確認自然過期淘汰,不需相容)
- 端到端實測一次點信 → nginx access log 不再出現 token（若有測試環境可驗）

## 備註

- 這是「讓實作符合原則 8 立法目的」的深度補強,非變更規範。`security.md` 所有權為「❌ 先問」,本項不觸及 `security.md` 本體。
- 影響 `UserMailService`(後端擁有)+ 前端兩個頁面(`VerifyEmailView.vue` / 重設密碼頁),屬**部署耦合**,兩端須協同上線(同 H6 教訓)。
