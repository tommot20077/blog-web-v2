# 全鏈 E2E 測試案例設計 — 2026-07-17

**狀態**:**P0(N1)已實作並實測通過**(前端 PR,commit `da77527`);其餘待分批實作。
**⚠️ 但擴充受阻**:CI 的 e2e-integration job 因 auth 限流而大面積失敗(既有問題,非本輪造成),
**需 Yuan 就 §2.1 的修法 A / B 擇一**,否則 §4 的其餘 case 無法落地。
**決策者**:Yuan(2026-07-17)
**範圍**:Playwright 驅動真瀏覽器 → 真 HTTP → 真後端 → 真臨時 DB 的全鏈測試

---

## 1. 為什麼要做(缺口在哪)

> ### ⚠️ 前提更正(2026-07-17,實地查證後)
>
> **本節初稿寫「全鏈完全沒有」,這是錯的**,已更正如下。實際情況:
>
> - **前端 Playwright 早已有真後端整合層**:`playwright.config.ts` 預設(未帶 `E2E_MOCK=1`)
>   testDir 就是 `./e2e/integration`、`VITE_USE_MOCK: 'false'`,**已有 16 個真後端 spec**
>   (含 `end-to-end-sanity.spec.ts`「完整 user journey 一條龍」、`auth-token-refresh` 等),
>   外加 3 個 `e2e/fullstack-red/`。
> - **CI 早已在跑全棧**:前端 `.github/workflows/ci.yml` 的 `e2e-integration` job 會 checkout
>   兩個 repo → build 後端 JAR → `docker build -t blog-backend:e2e` →
>   `docker compose -f docker-compose.e2e.yml up -d` → 等 health → `npm run test:e2e:ci`
>   (`E2E_CI` 未被 config 引用,故走預設 = 真後端)。
> - **`docker-compose.e2e.yml` 已完整**:postgres / redis / rabbitmq / minio / elasticsearch / backend。
> - **seed 已存在且就是本文件 §3 寫的那三個帳號**:`e2e/global-setup.ts` 的 `SEED_USERS`。
>
> **所以基礎建設不需要從零建,也不需要「證明可行」—— 它已經在 CI 運轉。**
> 真正的缺口是**覆蓋範圍**,見下方 §1.1。

### 1.1 真正的缺口

| 現有測試 | 覆蓋 | 缺什麼 |
|---|---|---|
| 後端 `blog-start/.../e2e/`(Testcontainers) | 真 DB、真 Spring context | **MockMvc 層** — 無瀏覽器、無真 HTTP、無前端 |
| 前端 `e2e/mock/`(29 支) | 真瀏覽器、真前端 | 打 mock 層 |
| 前端 `e2e/integration/`(16 支) | **真瀏覽器 + 真 HTTP + 真後端 + 真 DB** | **見下** |

**缺口一:信箱驗證旅程零覆蓋(P0 的真正價值)**
`e2e/fixtures/admin-helpers.ts` 的 `activateUser` 直接下 SQL
`UPDATE users SET email_verified=true, status='ACTIVE', role=...` **跳過整個驗證流程**。
全 e2e 目錄 grep `verify-email` / `verification_tokens` / `VerifyEmail` → **零命中**。
亦即:**註冊後如何啟用帳號,從來沒有任何 E2E 走過**。
而 **H6(PR #48 + 前端 #38)剛好改的就是這個端點**(GET+query → POST+body,前端另加
`router.replace` 清網址 token)—— 這個變更目前**沒有任何 E2E 保護**。

**缺口二:無正常/異常/邊界的分類意識**
既有 16 支多為 happy path;異常與邊界(§4.2 / §4.3)幾乎空白。

**缺口三:抓不到「靜默降級」**
本輪 review 抓到的 bug 正好都藏在那裡:

- **H6**:前端送 `GET /verify-email?token=`,後端只認 `POST` → **405**。兩邊測試各自全綠。
- **H5**:公開頁 `/tags` 打 `GET /api/v1/series` 拿 401,前端 `Promise.allSettled` **靜默降級為空清單** → 匿名訪客看不到系列區塊,**無任何錯誤跡象**,兩邊測試依然全綠。

> **本文件的核心判準**:一條 E2E 若無法抓到上述兩型缺陷,就沒有寫的價值。
> 因此「異常流」不只驗後端回對的錯誤碼,**必須驗前端是否顯性呈現該錯誤**(見 §5)。

---

## 2. 執行環境(Yuan 決策:docker-compose 全棧 —— **已存在,沿用**)

現況(查證後):

| 元件 | 現況 |
|---|---|
| 後端 + Postgres + Redis + RabbitMQ + MinIO + Elasticsearch | **`docker-compose.e2e.yml` 已完整**,backend 用 CI 現 build 的 `blog-backend:e2e` image |
| 前端 | Playwright `webServer` 起 **vite dev server**(`localhost:5500`),非容器、非 nginx |
| CI | `e2e-integration` job(僅 PR 觸發)已完整串起上述流程 |

**沿用現有配置,不重造。** 兩點與原始決策的落差,誠實記錄:

1. **前端目前是 dev server 而非容器**(故測不到 build 產物與 nginx)。改成容器化雖更貼近部署,
   但那是既有基礎建設的重構,**不在本輪範圍**;先用既有形狀把覆蓋補起來,價值高得多。
2. **`Dockerfile.fullstack` 未被 e2e compose 使用**(compose 只跑後端 image + 外部 vite)。
   它是本機整併用途,見 `runbook-integration.md`。

**無 SMTP 服務**(`MANAGEMENT_HEALTH_MAIL_ENABLED: "false"`)—— 這正好印證 §3 的策略:
驗證 token 在**註冊當下**就寫入 `verification_tokens`,不依賴信件真的寄出。

### 2.1 🚨 阻斷級發現:CI 的 e2e-integration job **目前是壞的**(2026-07-17 查證)

**這是本輪最重要的發現,也是 §4 全部 44 條 case 的前提。**

**證據**(前端 repo run `29517064292` / `29504978666`,即 PR #39 / #38):

```
Running 43 tests using 1 worker
  ✓ 1..13  admin-review / article-like / article-slug-api / auth-logout ... 通過
  ✘ 14..   author-file-upload 起，其後大面積失敗
  16 passed (4.7m)        ← 43 個測試只過 16 個
失敗訊息:TypeError: Cannot read properties of null (reading 'accessToken')
```

**根因:auth 端點的 per-IP 限流被打爆。**

| 限流 | 上限 | 窗口 | 常數 |
|---|---|---|---|
| register | **10** 次 | 60 分鐘 | `RedisKeyConstant.REGISTER_IP_MAX` |
| login | **20** 次 | 15 分鐘 | `RedisKeyConstant.LOGIN_IP_MAX` |

E2E 的所有請求來自**同一個 IP**(compose gateway,如 `172.22.0.1`)→ 整個套件共用一組計數器。
而 `e2e/integration/` 有 **約 66 處登入呼叫點**、整趟只跑 **4.7 分鐘**(遠短於 15 分鐘窗口,
計數器永不重置)→ 跑到第 13 個測試左右即撞上 `LOGIN_IP_MAX`,其後每個要登入的測試
都拿到 `A0113`(`data` 為 null)→ 取 `.accessToken` 拋 TypeError。

**為什麼沒人發現**:該 job 設 `if: github.event_name == 'pull_request'`,而最近幾次
合併都是 push 事件 → job **skipped**。最後一次真正執行是很久以前;本輪的 PR #38 / #39
是久違的觸發,因此看起來「像是我們改壞的」,**實際是既有問題**。

> **⚠️ 對 PR #38 / #39 的 reviewer**:這兩個 PR 的 CI 紅燈**不是它們造成的**。
> H4 的型別變更不可能弄壞檔案上傳測試。

**這使 §4 的擴充計畫在現況下不可行**:44 條 case 幾乎每條都要登入,上限只有 20 次/15 分鐘。

**修法二選一,需 Yuan 決策**(兩者皆有代價,故不擅自決定):

| | 做法 | 優點 | 代價 |
|---|---|---|---|
| **A. 測試側** | 每個 spec 前重置 Redis 計數(`resetAuthRateLimits`,本輪已實作) | 不動 prod code | 14 個 spec 用自訂 `test` fixture、14 個直接用 `@playwright/test`,**無單一注入點**,需逐檔加或做共用 wrapper |
| **B. 後端側** | 把上限外部化為設定(`@Value("${app.rate-limit.login-ip-max:20}")`),e2e compose 設高值 | 一次修好、標準做法、常數硬編本就是異味 | 動 prod code;且 E2E 從此不會踩到真限流 → 需另立一條專測限流的 case(見 §4.2 建議新增 E17) |

**本輪已做的**:`global-setup` 加了 `resetAuthRateLimits()`,確保**起跑點**乾淨
(CI 是 no-op;本機重跑必要 —— 實測計數會累積到 21 而讓整套無法啟動)。
但這**修不了跑到一半才撞頂**的 CI 根因 —— 那需要上述 A 或 B。

## 3. 測試資料策略(Yuan 決策:seed 為主 + 保留註冊旅程 —— **seed 已存在**)

- **主要:seed 已存在且已在用** —— `e2e/global-setup.ts` 的 `SEED_USERS` 正是
  `reader@test.local` / `author@test.local` / `admin@test.local`(密碼 `Test1234!`),
  與 `2026-04-26-fe-be-integration.md` 的慣例一致。**沿用,勿另造。**
- **但 N1 例外**:「註冊 → 信箱驗證 → 登入」這條旅程必須有完整 E2E。
  **這正是目前的空白**:`activateUser` 用 SQL 直接把帳號改成 ACTIVE,驗證流程從沒被走過。
- **信箱驗證 token 取得**:查 `verification_tokens` 表。**現成手法可沿用**:
  `e2e/fixtures/admin-helpers.ts` 已有透過 `docker run --rm postgres:16-alpine psql` 下 SQL 的
  既有做法(聰明之處是不必為 e2e 加 `pg` npm 相依),照抄該模式加一個「取 token」的 helper 即可。
  對應 SQL 比照後端 `AuthE2E`:
  `SELECT vt.token FROM verification_tokens vt JOIN users u ON u.id = vt.user_id
  WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'`
  *(替代方案 MailHog 更真實但多一個服務要維護,本輪不採。)*
- **隔離**:每個 spec 用唯一 email(既有 spec 已在用 `c2author_${Date.now()}@test.local` 的模式);
  seed 帳號只讀不改,要改狀態的 case 自建帳號。

---

## 4. Test Case 清單

**優先序**:P0 = 垂直切片(先跑通,證明基礎建設可行) / P1 = 高價值(抓過真 bug 的路徑) / P2 = 補完

### 4.1 正常流(Happy Path)

| ID | 旅程 | 判準 | 優先 | 關聯 |
|---|---|---|---|---|
| **N1** | 註冊 → 查 DB 取 token → **驗證頁** → 登入成功 | 帳號狀態 ACTIVE、登入後有 session。**目前唯一零覆蓋的旅程**(`activateUser` 以 SQL 跳過);且 H6(#48/#38)剛改此端點卻無 E2E 保護。**刻意寫在旅程層而非端點層** → 舊(GET+query)與新(POST+body)實作皆應通過,但**只有一邊上線就會紅** —— 這正是要抓的部署耦合 | **P0** | **H6** |
| N1b | 驗證後網址不得殘留 token | `router.replace` 清掉 query(原則 8 的瀏覽器歷史面) | P1 | H6 前端側 #38 |
| **N2** | 作者登入 → 發文 → 送審 → 管理員核准 → **登出** → 匿名看得到該文 | 匿名訪客看得到已發布文章全文 | **P1** | — |
| N3 | 匿名瀏覽文章列表 → 點進詳情 | 內容正確;view count 增加(MQ 非同步,需 retry 斷言) | P1 | H7 |
| N4 | 讀者登入 → 按讚 → 收藏 → `/bookmarks` 看得到 | 計數變化正確、重整後保留 | P2 | — |
| N5 | 讀者留言 → 回覆 → 對留言按讚 | 巢狀結構正確、`comment_count` 與標頭數字一致 | P2 | M5 |
| N6 | 作者建系列 → 加文章 → **匿名**看系列詳情 | 匿名 200(非 401);只列出 PUBLISHED | **P1** | **H3/H5** |
| N7 | 作者編輯文章 → 產生快照 → 檢視版本 → 還原 | 還原後內容正確;**回應不含 `id`/`authorId`/`categoryId`** | P2 | H4 |
| N8 | 搜尋關鍵字 → 結果列表 → 點進文章 | ES 索引非同步,需 retry | P2 | — |
| N9 | `/tags` 標籤雲 → **系列區塊對匿名可見** → 點標籤 → 文章列表 | **匿名訪客看得到系列區塊**(不是空的) | **P1** | **H5** |
| N10 | 讀文章捲動 → 離開 → 回訪 | 閱讀進度保留 | P2 | — |
| N11 | 登出 → 用回上一頁 | session 已失效、受保護內容不可見 | P2 | — |
| N12 | 忘記密碼 → 重設 → **舊 session 失效** → 新密碼可登入 | 舊 access/refresh token 全部失效 | **P1** | **C1/H2** |

### 4.2 異常流(Error Path)

> **每一條都必須驗「前端顯性呈現錯誤」**,而非只驗後端回對的碼。

| ID | 情境 | 判準 | 優先 | 關聯 |
|---|---|---|---|---|
| E1 | 重複 email 註冊 | 顯性錯誤訊息(非靜默失敗) | P1 | — |
| E2 | 錯誤密碼登入 | 顯性錯誤;**不透露帳號是否存在** | P1 | — |
| E3 | 過期/無效驗證 token | 顯性錯誤 + 可重發 | P1 | — |
| **E4** | **未登入打受保護頁**(`/editor`、`/settings`、`/my-articles`) | 導向 `/login`、記錄 returnUrl、登入後回原頁 | **P1** | — |
| E5 | USER 角色打 `/editor`(需 AUTHOR) | 顯性拒絕 / 導向,非白畫面 | P1 | — |
| E6 | 非 owner 改他人文章 | 403;前端顯性提示 | P2 | — |
| E7 | 非 owner 看他人版本 | V0102;前端顯性提示 | P2 | — |
| E8 | 一般 USER 打 `/admin/review` | 403 / 導向 | P1 | — |
| **E9** | **後端回 5xx(或服務中斷)** | **前端顯性報錯,不得靜默降級為空清單** | **P1** | **H5** |
| E10 | 網路 timeout(Playwright route abort) | 顯性錯誤 + 可重試 | P2 | — |
| **E11** | **access token 過期 → 自動 refresh → 續用** | **refresh 後角色不得降級**(AUTHOR 仍是 AUTHOR) | **P1** | **H1** |
| E12 | refresh token 也過期 | 導向登入,不無限迴圈 | P1 | — |
| E13 | 改密碼後,用舊 refresh token 換發 | 換發失敗 | P1 | **H2** |
| E14 | 密碼複雜度不符 | **前後端提示規則一致**(不能前端過、後端擋) | P1 | — |
| E15 | 重複提交(double-click 送出) | 不產生兩筆 | P2 | M6/M9 |
| E16 | 表單必填留空 | 前端擋下、不打 API | P2 | — |
| **E17** | **短時間內反覆註冊 / 登入 → A0113 限流** | 顯性提示「請求過於頻繁」。**若採 §2.1 的修法 B(把上限外部化並在 e2e 調高),此條必須補上**,否則限流從此無人測 | P1(修法 B 的配套) | §2.1 |

### 4.3 邊界(Boundary)

| ID | 情境 | 判準 | 優先 | 關聯 |
|---|---|---|---|---|
| B1 | 空狀態(無文章/留言/收藏/系列) | 空狀態 UI,**非錯誤畫面、非無限 loading** | P1 | — |
| B2 | 分頁:首頁 / 末頁 / `page=0` / `size` 超上限 | `page=0` 應 **400 非 500** | P1 | **M8** |
| B3 | 連續翻頁 | 不重複、不漏顯(缺唯一 tiebreaker) | P2 | LOW |
| B4 | 超長標題 / 超長內容 | 驗證錯誤或正確截斷,不 500 | P2 | — |
| **B5** | **XSS:`<script>` 置入標題 / 留言 / Markdown** | **不執行**;以文字呈現 | **P1** | — |
| B6 | Markdown 注入(`<img onerror>`、iframe) | sanitize 生效 | P1 | — |
| B7 | 閱讀進度剛好 `0.95` | 算已讀完(門檻邊界) | P2 | — |
| **B8** | **系列內文章 PUBLISHED → 改 DRAFT** | **匿名立刻看不到**;`myProgress` 分母同步 | **P1** | **H3** |
| B9 | 只含草稿的系列 | 出現在公開列表但點進去空(**已知後續**,先記錄不修) | P2 | #49 後續 |
| B10 | 併發:同文章同時加入系列 ×2 | `article_count` 不得 +2 | P2 | **M6** |
| B11 | 併發:重複收藏 | 不 500 | P2 | **M9** |
| B12 | 留言軟刪 | tombstone 顯示;**卡片徽章與標頭數字一致** | P2 | **M5** |
| B13 | 檔案上傳:超 quota / 超大檔 / 錯 MIME | 顯性錯誤,不 500 | P2 | — |
| B14 | Unicode 標題、特殊字元 slug | 正確處理、網址可用 | P2 | — |

---

## 5. 判準原則(這批測試的靈魂)

1. **顯性錯誤 > 靜默降級**:任何「後端失敗 → 前端顯示空/正常」的路徑一律視為失敗。
   H5 就是這樣藏了一週:`Promise.allSettled` 把 401 吞成空清單,使用者只看到「沒有系列」。
2. **匿名視角必測**:每個公開頁至少一條匿名 case。H5/H3 都是「登入者正常、匿名壞掉」。
3. **斷言不存在,不只斷言存在**:如 H4 的「回應**不含** `authorId`」——只斷言新欄位存在的測試,
   在 entity 偷渡回來時仍會通過。
4. **非同步要 retry 不要 sleep**:view count、搜尋索引走 MQ/ES,用 Playwright 的
   `expect.poll` / `toPass`,不要固定 `waitForTimeout`。
5. **測旅程,不測端點**:端點層級後端 E2E 已覆蓋,全鏈的價值在跨層串接。

## 6. 不在本範圍

- 效能/負載測試(N+1 見 M16/M17,另案)。
- 視覺回歸(screenshot diff)。
- 真實 SMTP 收信(本輪用 DB 取 token;若日後要測信件內容再引入 MailHog)。
- 手機版 viewport(先桌面)。

---

## 7. 實作順序

> **原本寫「N1 用來證明基礎建設可行」—— 前提已更正**(見 §1):基礎建設已在 CI 運轉,
> 不需要證明。N1 的價值改為:**補上唯一零覆蓋的旅程,且它剛被 H6 改動卻無保護**。

1. **P0 = N1**(註冊 → 查 DB 取 token → 驗證頁 → 登入)。價值有三:
   - 它是**唯一沒有任何 E2E 走過**的旅程(`activateUser` 用 SQL 跳過驗證)。
   - **H6(#48 + #38)剛改了這個端點**,目前零保護。
   - 寫在**旅程層**(不斷言 HTTP method),故舊實作(GET+query)與新實作(POST+body)皆應通過;
     **只有一邊上線時會紅** —— 正是要抓的部署耦合。
2. 接著 **N2 + N6 + N9**(P1 核心三條:發布旅程、匿名系列、標籤頁系列區塊)。
   注意 N6/N9 需 **#47 + #49 合併後**才會綠(現在 series 對匿名是 401)。
3. 再依 P1 → P2 分批補完。既有 16 支 `e2e/integration/` 已覆蓋部分 happy path,
   補之前先 grep 該目錄,**勿重複造**。

## 8. 給實作者的環境陷阱(踩過的)

- 跑跨模組/E2E 測試**必加 `-am`**,否則連到 `.m2` 舊 jar,出現與程式碼無關的假失敗。
- 後端有 repo 層級 **CRLF 汙染**(97 個 java 檔),碰到受汙染的檔需拆 commit(食譜見
  `backlog/2026-07-14-full-review-findings.md`)。
- 改程式碼用 `git worktree` 從 `origin/develop` 開,**勿動 Yuan 的主 checkout**(有未提交治理層 WIP)。
- 目前有 6 個 draft PR 待審 + H4 分支待開 PR,E2E 工作走獨立分支。
