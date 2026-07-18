# Backlog: 全專案憲法合規完整 Review 發現(2026-07-14)

**狀態**:進行中(C1/H1/H2/C2/H7/H3/H6/H5/H4 已修並出 PR — **CRITICAL 與 HIGH 全數清空**;MEDIUM 以下未動)
**來源**:2026-07-14 六維平行稽核(安全 / 交易+MQ / 模組邊界 / 錯誤處理+API / 程式碼+測試規範 / 正確性 bug),唯讀掃描未動程式碼。CRITICAL 與 auth 叢 HIGH 已由主 session 親自二次驗證(見各項「已驗證」標記);其餘為 subagent 稽核回報。
**去重**:本檔第 §C2 / §H7 / §H5 三項與 `2026-07-07-security-txmq-audit-findings.md` #1/#2/#3 為同一發現,本次**再驗證屬實**;C2/H7 已於本輪修畢,H5 仍未修。

---

## 📌 交接記錄(2026-07-16 更新 — 換 session 用,先讀這節)

### 已完成(4 個 draft PR 待 review;**#45 → #46 有順序;#47 / #48 獨立**)

| PR | 分支 | 內容 | commit | 測試 |
|----|------|------|--------|------|
| [#45](https://github.com/tommot20077/blog-web-v2/pull/45) → `develop` | `feature/auth-session-revocation` | **C1 + H2**(SessionRevoker 統一撤銷 session)、**H1**(refresh 角色降級) | `963a1f8`、`235b766` | blog-module-user **191 / 0 failed** |
| [#46](https://github.com/tommot20077/blog-web-v2/pull/46) → **#45 的分支**(stacked) | `feature/tx-mq-commit-ordering` | **C2 + H7**(MQ 改為 commit 後才發) | `f790f2c`、`cd0b8f3` | article **314 / 0**、version **66 / 0** |
| [#47](https://github.com/tommot20077/blog-web-v2/pull/47) → `develop`(獨立) | `feature/series-detail-published-only` | **H3**(Series 詳情只公開 PUBLISHED) | `f405580` | series 全模組 **37 / 0** |
| [#48](https://github.com/tommot20077/blog-web-v2/pull/48) → `develop`(獨立,**BREAKING**) | `feature/verify-email-token-in-body` | **H6**(verifyEmail 改 POST + body) | `4c2a403` | user **188 / 0**;E2E 實跑 AuthE2E **8/0**、P0Auth **2/0**、UserLifecycle **1/0** |
| [前端 #38](https://github.com/tommot20077/blog-web-v2-front-end/pull/38) → 前端 `develop` | `feature/verify-email-token-in-body` | **H6 前端側**(改打 POST + 清網址 token) | `813df91` | vitest **1168 / 0**、vue-tsc 綠 |
| [#49](https://github.com/tommot20077/blog-web-v2/pull/49) → **#47 的分支**(stacked) | `feature/series-public-endpoints` | **H5**(series 補 permitAll + 原則 7 豁免 JavaDoc)、Comment.list 豁免 JavaDoc | `a45d3ce`(純行尾)+ `41211de`(真變更,+111/−3) | SecurityConfigTest **35/0**、SeriesControllerIT **13/0**、infra **80/0**、series **39/0**、comment **67/0** |
| [#50](https://github.com/tommot20077/blog-web-v2/pull/50) → `develop`(獨立,**BREAKING**) | `feature/version-series-response-dto` | **H4**(version/series 不再回 entity,對外只出 UUID) | `31a183d` | VersionControllerIT **15/0**、SeriesControllerIT **11/0**、SeriesServiceTest **16/0**、全模組 `-am` 綠 |
| [前端 #39](https://github.com/tommot20077/blog-web-v2-front-end/pull/39) → 前端 `develop` | `feature/version-series-response-types` | **H4 前端側**(型別對齊,刪除 Series/ArticleVersion entity 介面) | `15eea2e` | vitest **1167/0**、vue-tsc 綠 |

- #46 的 base 指向 #45 的分支(兩者在同一 commit 序列上),**#45 合併後 GitHub 會自動把 #46 改指向 develop**。
- **#47 / #48 從 `origin/develop` 開,與 #45/#46 無檔案衝突**,可獨立 review 與合併,不必等前兩者。
- **#48 與前端 #38 必須一起上線**(端點形狀變更)。後端先行部署 → 前端信箱驗證頁失效;前端先行部署 → 舊後端只認 GET 而失效。**信件連結不受影響**(信裡連結指向前端頁面,不是 API)。
- **#49 的 base 指向 #47 的分支——這是安全關鍵的刻意設計**:#49 把 series 開放給匿名,#47 是「詳情只回 PUBLISHED」的過濾。若 #49 先上線,未發布文章全文會從「僅登入者可見」直接曝給匿名網路。stacked 讓它物理上不可能先落地,**勿把 #49 的 base 改成 develop 後搶先合併**。
- 全部皆 draft,CI 尚未驗證。

### 下一步建議順序

**CRITICAL 與 HIGH 已全數清空(C1/C2 + H1–H7),以下皆為 MEDIUM 以下或制度層。**

1. **🚨 決策待定 — CI 的 `e2e-integration` job 目前是壞的(既有問題)**:43 個測試只過 16 個,根因是 **auth 端點 per-IP 限流被打爆**(login 20 次/15 分,而 E2E 全部同 IP、約 66 處登入、整趟 4.7 分鐘 → 第 13 個測試就撞頂,其後皆 `Cannot read properties of null (reading 'accessToken')`)。它藏很久是因為該 job 僅 `pull_request` 觸發,近期合併都是 push → skipped。**修法 A(測試側逐檔重置)或 B(後端把上限外部化為設定)需 Yuan 擇一**,詳見 `integration-tests/2026-07-17-fullstack-e2e-test-cases.md` §2.1。**在此之前 E2E 無法擴充**(44 條 case 幾乎每條都要登入)。
2. **全鏈 E2E**(2026-07-17 Yuan 指定的當前工作):見 `ai-docs/integration-tests/2026-07-17-fullstack-e2e-test-cases.md`。**P0(N1 信箱驗證旅程)已實作並實測通過** → 前端 PR #40。注意該文件初稿的前提有誤(誤寫「全鏈完全沒有」),已更正:`e2e/integration/` 早有 16 支真後端 spec、CI 也早有全棧 job,**基礎建設不需重建**。
2. MEDIUM 依模組批次(Series 邊界 M1/M2、冪等 M3/M4、正確性 M5–M9、規範 M13–M15)。
3. 選配但建議:`2026-07-07-archunit-guards.md` 守衛 #1(禁 `@Transactional` 方法呼叫 `convertAndSend`)— #46 修完後即可通過,是這整類 bug 的系統性防線。
4. **等 #45~#50 全數合併後**:跑一次 `git add --renormalize .` 根治 CRLF(見下方環境注意事項)。
5. **新發現(2026-07-17,建議另案評估)— 前端測試檔完全不被 typecheck**:`blog-web-v2-front-end/tsconfig.app.json` 明確 `exclude: ["src/**/*.test.ts", ...]`,`tsconfig.json` 只 reference app + node,`vitest.config.ts` 亦未開 `typecheck` → **所有測試檔裡的型別錯誤目前都是隱形的**,型別與真實 API 回應漂移時不會有任何訊號。**已實測**:把 `authorId` 加回 `SeriesSummary` 型別後 `vue-tsc -b` 依然 exit 0。此缺口使前端無法用 `@ts-expect-error` 之類的型別守衛釘住 API 契約(H4 前端 PR #39 原本想加,實測無效後移除)。修法需改 tsconfig 或開 vitest typecheck,可能一次噴出大量既有錯誤,故未夾帶。

### 給下一個 session 的關鍵教訓(非顯而易見,務必先讀)

1. **交易/MQ 類缺陷不能用一般測試驗**:C2/H7 這類是「交易上下文」不變量,不是狀態變更。斷言「文章有還原」或「事件有發」在**修復前後都會通過**——你們既有的 `restoreVersion_draftSnapshot_publishesContentChangedOnly` 正是這種假綠。有鑑別力的判準是:**事件送出當下 `TransactionSynchronizationManager.isActualTransactionActive()` 必須為 false**,且要先 verify 事件確實有送出以免空過。此判準**僅在整合測試(真 tx manager)有效**——Mockito 單元測試沒有 proxy,`@Transactional` 根本不生效,壞碼也會讀到 false。範例見 `CrossModuleVersionIT.restoreVersion_publishesEventsAfterCommit` 與 `ArticleControllerIT.getArticle_publishesViewedOutsideTransaction`。
2. **交易邊界拆分的安全性論證**(C2 為何敢拆成兩個交易):stash 先行 commit,唯一壞路徑是「stash 成功但還原失敗」→ 只多一筆忠實反映「未還原」現況的 AUTO 快照、**不發任何 MQ**、`retainAuto` 會修剪;危險方向(還原了卻沒備份)被順序擋掉。故採憲法既有樣板(TransactionTemplate)而非引入零先例的 afterCommit hook。
3. **既有測試可能把 bug 當預期行為在斷言**:H1 修復時發現 `AuthControllerTest.refresh_nullRoleAndVersion_shouldUseDefaultsAndReturnNewAccessToken` 明確斷言「role null → 降級 USER」。修 bug 前先確認相關測試是否在幫 bug 背書。
4. **改 service 建構子會炸手動 new 的測試**:`ArticleFacadeImplTest`(手動 `new ArticleFacadeImpl(...)`)、`VersioningServiceTest`/`AuthServiceTest`(`@InjectMocks`)。加依賴後需補 `@Mock` 並把 `TransactionTemplate` stub 成直接執行 callback,否則 `execute()` 回 null、方法體不會跑。
5. **本 backlog 開的處方可能修錯層——動手前先讀契約 javadoc**(2026-07-16 實例):H3 原處方寫「`ArticleMapper.java:282` 補 `AND status='PUBLISHED'`,一行見效」,但 `ArticleFacade` javadoc 明文把該方法歸在 SP-B read 類「**不限狀態**,caller 自行依 status 判斷(如 SeriesService 過濾 DRAFT)」,`ArticleData` 亦為此保留 status 欄位。**不過濾是契約刻意的,漏過濾的是 caller**。照原處方改雖然「今天結果一樣」(SeriesService 是唯一呼叫者),卻會讓該方法與 `findById`/`findByIds` 等同類方法行為不一致,並讓契約與實作漂移。**判準:修之前先問「這個行為是 bug,還是某份契約刻意的設計而別人沒遵守?」** 稽核報告開的處方是線索,不是判決。
6. **端點形狀類的發現要先追真正的呼叫鏈**(2026-07-16 實例):H6 原本註記「信箱驗證連結目前是 GET,改 body 需前端配合改 POST」,實際上 `UserMailService.buildUrl` 用的是 **`frontendBaseUrl`**——信裡的連結指向**前端頁面**而非 API,本來就該是 GET 且完全不用動,已寄出的信也不會失效。真正要改的只有前端打後端的那一次呼叫。**影響面評估請以程式碼為準,勿沿用稽核當下的推測。**
7. **本檔的兩節可以互相矛盾——遇到就實測,別選你喜歡的那個**(2026-07-16 實例):H3 節寫「匿名可打 `GET /series/{slug}`」,H5 節寫「`/api/v1/series/**` **沒有** permitAll,落入 `anyRequest().authenticated()`」——同一份文件、直接打架。上一輪修 H3 時採信了 H3 節的說法,把「匿名可存取」寫進了程式碼 javadoc 與 PR;這一輪修 H5 才發現是錯的(實測 401),得回頭修正 #47 的措辭(commit `f024805`)。**判別成本極低**:在既有的 `SeriesControllerIT`(跑真 SecurityConfig)加一個匿名請求的探針測試,27 秒就有答案。**凡「誰能存取這個端點」的斷言,一律以跑真 SecurityConfig 的整合測試為準**——`@WebMvcTest` 會 mock 掉 filter chain,單元測試看不到真相。稽核報告的嚴重性判定常繫於此,搞錯會連帶誤導後續每一個 PR 的措辭與優先序。
8. **安全類發現要追到使用者面的實際後果,別停在「設定與文件矛盾」**(2026-07-16 實例):H5 在 backlog 裡被記為 MEDIUM「URL 層與 JavaDoc 矛盾」,聽起來像文件潔癖。追到前端才發現真正的代價:公開頁 `/tags` 的系列區塊對匿名訪客**靜默消失**(`Promise.allSettled` 把 401 降級為空清單)。**跨 repo 追一次呼叫鏈,常把「規範違規」變成「線上 bug」**,決策依據完全不同。

### 環境/狀態注意事項

- **`.gitattributes` 行尾衝突是 repo 層級的,不是 IDE 造成的**(2026-07-16 更正前次診斷):`.gitattributes` 規定 `*.java text eol=lf`,但**版控裡的 blob 本身存著 CRLF**,兩者永久打架 → 任何人 clone / 任何全新 worktree 一 checkout 就有 **97 個 java 檔**顯示為 modified(實測:全新 worktree、`core.autocrlf=false`、無 IDE 介入,照樣重現)。前次推測的「疑似 IDE 寫入 CRLF」不成立。
  - **判別法**:`git diff --ignore-cr-at-eol --stat` 剩下的才是真差異。
  - **⚠️ 「只 add 自己的路徑」不夠——前一版本檔的這條建議不完整,2026-07-16 二次更正**:**汙染是 per-file 的**。你碰到的檔若本身受汙染(blob 是 CRLF),`git add` 一定會依 gitattributes 把它正規化成 LF → **該檔在 diff 裡變成整檔重寫,無法迴避**(除非去動 .gitattributes,不要)。H3/H6 的 diff 乾淨只是**運氣好**——series/comment/user 那幾個檔的 blob 本來就是 LF;H5 碰到 `SecurityConfig.java`(172 行全 CRLF)就中了,345 行的 diff 裡只有 3 行是真的。
  - **動手前先驗該檔是否受汙染**:`git cat-file blob HEAD:<path> | python -c "import sys;b=sys.stdin.buffer.read();print('CRLF',b.count(b'\r\n'),'LF-only',b.count(b'\n')-b.count(b'\r\n'))"`。**不要用 `grep -c $'\r'`**——Git Bash 會把 CR 吃掉變成空 pattern,回傳的其實是總行數,看起來像「每個檔都有 CR」的假象(本 session 踩過)。
  - **中了就拆成兩個 commit**(#49 實作範例,Yuan 已認可此作法):沒人 review 得動 996 行噪音裡的 36 行。食譜(在自己的 worktree,分支未被他人使用時):
    ```bash
    git reset -q <parent>                 # HEAD+index 退回,工作區保留你的變更
    # commit A:把受汙染檔的「原始內容」寫成 LF,一字不改
    git cat-file blob <parent>:<path> | python -c "import sys;sys.stdout.buffer.write(sys.stdin.buffer.read().replace(b'\r\n',b'\n'))" > <path>
    git add <path> && git commit -m "style: 行尾正規化為 LF"
    git diff --ignore-cr-at-eol --stat <parent> HEAD   # 必須為空 → 證明零內容變更
    # commit B:還原原本那個「已測過」的 commit 的內容
    git cat-file blob <原commit>:<path> > <path>       # 五個檔都做
    git add ... && git commit -m "<原訊息>"
    git diff <原commit> HEAD                           # 必須為空 → 證明拆分沒改到一個字,原測試結果仍有效
    git push --force-with-lease
    ```
    兩個驗證步驟是關鍵:前者證明 A 是純行尾,後者證明拆完的樹與已測過的完全相同(故不必重跑測試)。**force-push 前先確認沒有別的 PR 以該分支為 base**(`gh pr list --json baseRefName`),且需 Yuan 授權。
  - **切勿用 `git checkout -f` / `git reset --hard` 清**——會連未提交的治理層 WIP 一起毀掉。
  - **根治**(未做,需 Yuan 決定):跑一次 `git add --renormalize .` 全 repo 正規化並單獨 commit。代價是產生一個碰 97 檔的巨大 commit,且會與所有進行中的 PR 衝突,**建議等 #45~#49 全部合併後再做**。
- **改動程式碼時建議用 worktree 隔離**:`git worktree add .worktrees/<name> -b <branch> origin/develop`(`.worktrees/` 已在 .gitignore)。你的主 checkout 有未提交的治理層 WIP,在上面切分支風險高。注意**本地 `develop` 可能落後 origin 很多**(2026-07-16 實測落後 23 個 commit),開分支請用 `origin/develop`。
- **治理層是「部分」進版控,不是全部 untracked**(2026-07-16 更正前次記載):實際 `git ls-files ai-docs/` 只有 **`architecture.md`、`schema.md`、`integration-tests/*`** 進了版控;**`security.md`、`code-standards.md`、`testing-standards.md`、`judgment.md`、`maintenance.md`、`backlog/*`(含本檔)、`.claude/`、`GEMINI.md` 全部仍 untracked**。是否 commit 由 Yuan 決定。
  - **這已經造成具體代價**:H5(PR #49)依 Yuan 授權改了 `ai-docs/security.md` 的 Public Endpoints 表,但該檔 untracked → **改動無法出現在 PR diff 裡**,reviewer 只看得到 SecurityConfig 開放了 series、看不到對應的文件軌跡。而原則 7 豁免的第 1 條正是「對得上 Public Endpoints 表」——證據只存在於 Yuan 的工作區磁碟。
- 測試指令:`./mvnw -pl <module> -o test -Dtest=<Class>`;整合測試需 Docker(Testcontainers 起 postgres+redis)。日誌依 CLAUDE.md 存 `logs/`。
- **跑 E2E 一定要加 `-am`,否則是假失敗**(2026-07-16 實測):`./mvnw -B test -Pe2e -pl blog-start -Dtest=<E2E> -Dsurefire.failIfNoSpecifiedTests=false` **漏 `-am`** 會讓 blog-start 連結 `.m2` 裡**舊版**的模組 jar,改在其他模組的程式碼根本沒進去 → 出現與程式碼無關的失敗(H6 實測:漏 `-am` 時 AuthE2E 回 405,加了就全綠)。**正確**:`./mvnw -B test -Pe2e -pl blog-start -am -Dtest=<E2E> -Dsurefire.failIfNoSpecifiedTests=false`(注意是 `surefire.failIfNoSpecifiedTests`,不是 `failIfNoTests`)。副作用:此假失敗恰好等同「舊後端 + 新客戶端」,可用來實證部署耦合。
- **CI 有獨立 E2E job**(`.github/workflows/ci.yml`:`./mvnw -B test -Pe2e -pl blog-start -am`),改動端點形狀時 `blog-start/src/test/.../e2e/` 底下的呼叫點必須一起改,否則 CI 紅。**CI 未跑 `docs/api-contract/` 的契約稽核**(已查證,兩個 repo 皆然),故契約快照與程式碼暫時不一致不會擋 CI。

### 與其他 backlog 的連動

- `2026-07-07-security-txmq-audit-findings.md` **已全數修畢,該檔可關閉**(其狀態列已更新):#1/#2 = 本檔 C2/H7 → PR #46;#3 = 本檔 H5 → PR #49;#4 早於 07-07 完成。
- `2026-07-07-archunit-guards.md` 守衛 #1(禁 `@Transactional` 方法呼叫 `convertAndSend`)— PR #46 修完後此守衛即可通過,是這整類 bug 的系統性防線,建議接著補上。

---

## 🔴 CRITICAL(立即修)

### ✅ DONE(PR #45,commit `963a1f8`)— C1. `resetPassword` 重設密碼後未撤銷任何 session(帳號救援失效)〔本次新發現,已驗證〕

> 修法:抽 `SessionRevoker.revokeAllSessions(userId)`(清 auth hash + refresh ZSet),resetPassword / changePassword / deleteAccount 三處共用。語意採「登出所有裝置」。

`blog-module-user/.../service/AuthService.java:468-486`。方法只做 DB `token_version` v1→v2 + 存密碼 + 刪 reset token,**整段無任何 `redisTemplate` 操作**:既未更新 `user:auth:{id}` 的 cached version,也未清 `user:refresh:{id}` ZSet。
- **失敗場景**:帳號被盜(攻擊者持 access token v1 + refresh token 在 ZSet)。受害者走「忘記密碼→resetPassword」。filter 命中 Redis 舊快取讀到 v1 == 被盜 token v1 → 被盜 access token 續用;攻擊者 refresh token 仍在 ZSet → 打 `/refresh` 持續換發有效 token,最長維持到 refresh 7 天壽命。
- **對照組**:`UserService.changePassword:156` 有 `put(FIELD_VERSION,...)`、`deleteAccount:181-182` 有清 auth key + refresh key;唯 resetPassword 兩者皆缺。
- **規範依據**:`security.md` 原則 5(Stateful JWT / instant logout)。
- **→ 修法**:resetPassword 補上「更新 Redis `user:auth:{id}` version + 清 refresh ZSet」,與 changePassword/deleteAccount 統一。建議抽一個 `revokeAllSessions(userId, newVersion)` 私有方法供三處共用。TDD:先寫「重設密碼後舊 access + 舊 refresh 皆失效」的失敗測試。

### ✅ DONE(PR #46,commit `f790f2c`)— C2. `applyRestoreContent` 在未 commit 交易內發 MQ(BUG-2026-001 FIN-2 同型)〔= 07-07 #1〕

`blog-module-article/.../facade/ArticleFacadeImpl.java:381-405`。詳見 `2026-07-07-security-txmq-audit-findings.md` #1。
- **本次補充**:外層呼叫者 `VersioningService.restore`(`VersioningService.java:234`)本身也 `@Transactional`(REQUIRED 傳播 → 合併同一外層交易),交易範圍還涵蓋 restore 前的 stash AUTO 快照寫入。**只改 facade 不夠,外層 restore 的 @Transactional 也要一併處理**,否則仍在外層交易內。
- **規範依據**:`code-standards.md` §Transaction+MQ 時序。**→ 修法**:比照 `ArticleCommandSubService` 的 TransactionTemplate 收斂模式。TDD。

---

## 🟠 HIGH(盡快修)

### ✅ DONE(PR #45,commit `235b766`)— H1. `/refresh` 每次刷新把 AUTHOR/ADMIN 降級成 USER〔本次新發現,已驗證〕

> 修法:login 寫入 `FIELD_ROLE`(快路徑)+ `/refresh` 於 role 缺失時回退 DB(`AuthService.resolveUserRole`,涵蓋 filter 回填與 legacy hash)。

`blog-module-user/.../controller/AuthController.java:141-148`。`roleStr` 讀 `user:auth:{id}` 的 `FIELD_ROLE`,但全 main code **零寫入點**(已 grep 確認:`FIELD_ROLE` 只有常量定義 `RedisKeyConstant:353` + 此處讀取;`opsForHash().put` 只寫過 `FIELD_VERSION`/`FIELD_STATUS`)→ `roleStr` 恆 null → `roleStr != null ? roleStr : "USER"` 恆回 USER。
- **失敗場景**:AUTHOR/ADMIN 登入 15 分鐘後 access token 到期,前端一 `/refresh` 就換到 role=USER 的 token → filter 只給 USER 權限 → 作者/管理員失去發文/審核/管理能力,須重登。功能面幾近全毀(方向是降級非提權,故非提權漏洞,但影響極大)。
- **→ 修法**:登入時(`AuthService:218-220` 寫 auth hash 處)一併 `put(FIELD_ROLE, role)`;`revokeAllSessions`/changePassword 更新時同步。TDD:先寫「refresh 後 token role 維持 AUTHOR」失敗測試。

### ✅ DONE(PR #45,commit `963a1f8`)— H2. `changePassword` 未清 refresh ZSet(被盜 refresh token 改密碼後仍能換發)〔本次新發現,已驗證〕

`blog-module-user/.../service/UserService.java:141-158`。更新 DB version + Redis hash version(→v2),但**未清 ZSet**;攻擊者打 `/refresh` 仍過 ZSet 檢查 → 讀 hash v2 → 換發 v2 新 token。與 javadoc「使所有現有 Token 立即失效」不符。與 C1 同一叢,建議合併於 `revokeAllSessions` 一起修。**規範依據**:`security.md` 原則 5。

### ✅ DONE(PR #47,commit `f405580`)— H3. 公開 Series 詳情端點洩漏非 PUBLISHED 文章全文〔本次新發現〕

> **修法與原處方不同(原處方修錯層,已更正)**:改修 `SeriesService.getSeriesDetail`,於 facade 回傳後以 PUBLISHED **白名單**過濾,**不動 ArticleMapper 的 SQL**。詳見下方「原處方為何錯」。

呼叫點 `SeriesService.getSeriesDetail:210`,取用 `ArticleMapper.java:282`(`SELECT * FROM articles WHERE series_id=#{seriesId} ORDER BY series_position`)。
- **⚠️ 暴露面更正(2026-07-16 實測)**:本節原文寫「匿名可打 `GET /series/{slug}`」**與事實不符**,且**與本檔 H5 節自相矛盾**(H5 明寫「`/api/v1/series/**` 非 permitAll」)。實測(`SeriesControllerIT` 跑真 SecurityConfig)匿名回 **401**。當時真正的暴露面是**任何已登入使用者**,不是匿名網路。**兩節矛盾時以實測為準**——見教訓 #7。修完 H5(PR #49)後才會真的對匿名開放,故 #47 必須先合併。
- **失敗場景**:PUBLISHED 文章加入系列後被改 DRAFT / 退稿 REJECTED,但 `series_id` 未清 → 讀者拿到 title/slug/summary/content;`myProgress` 的 readCount/total 也把非公開文章計入,`nextUnread` 甚至直接回傳未公開文章的 UUID。
- **~~原處方~~**:~~SQL 補 `AND status='PUBLISHED'`(成本最低影響最高的一項)~~ ← **錯層,勿照做**。
- **原處方為何錯**:`ArticleFacade` javadoc 明文把 `findBySeriesIdOrderByPosition` 歸在 SP-B read 類——「**不限狀態**,回傳含 status 的 ArticleData,**caller 自行依 status 判斷(如 SeriesService 過濾 DRAFT)**」;`ArticleData` javadoc 也列明 status 欄位是為 SeriesService 保留的。**不過濾是契約刻意的設計,漏過濾的是 caller**。在 mapper 加條件會讓該方法與 `findById` / `findByIds` 等 SP-B 同類方法行為不一致,並使契約與實作漂移——下一個照 javadoc 假設「不限狀態」的呼叫者會中招。(`SeriesMapper.findPrevNav/findNextNav` 有 `AND status='PUBLISHED'` 不構成反證:那是 series 模組自己的 SQL,不經過 ArticleFacade 契約。)
- **實際修法**:`getSeriesDetail` 內 `.filter(a -> ArticleStatus.PUBLISHED.name().equals(a.status()))`。採白名單而非「排除 DRAFT」——`ARCHIVED`(不再公開)與 `REJECTED`(僅作者可見)同樣不得公開。`toDetailResponse` 的 articleIds 由傳入列表推導,故**單點過濾即同時修好文章列表與 myProgress**,口徑一致。
- **測試判準**(有鑑別力的設計):讓唯一的 PUBLISHED 文章「已讀」,修復前 `nextUnread` 會回傳 **DRAFT 的 UUID**(把洩漏演出來),修復後為 null。屬純過濾邏輯,`SeriesServiceTest`(已 mock articleFacade)即為適當層級,**不需整合測試**(非教訓 #1 的交易類)。
- **後續**:`toDetailResponse` 的 `articleCount` 取自 `series.article_count` 非正規化計數欄,仍含非公開文章 → 匿名訪客會看到「articleCount=5 但只列 1 篇」。屬計數層級的次要洩漏,修它會動到該欄在其他端點的語意(另有 M6 競態),**未處理**。

### ✅ DONE(PR #50 + 前端 #39,commit `31a183d` / `15eea2e`)— H4. version / series 端點直接回傳 entity,內部 `Long id`/`authorId` 洩漏到 API 表面〔本次新發現〕

> 修法:version 的 `createManual`/`promote` 改回 `VersionDetailResponse`;series 的 `create`/`update` 改回**既有的** `SeriesSummaryResponse`(不新增 DTO,使 create/update/list 形狀一致)。`authorId`/`categoryId` **直接移除而非翻譯成 UUID**,理由見下。

`VersionController.java:95,130`、`SeriesController.java:74,83`、`VersionDetailResponse:21,27` / `VersionSummaryResponse:24`。
- **規範依據**:`architecture.md`「All external IDs must be UUIDs」。根因:缺 response DTO 映射層(對照 `ArticleResponse` 有明文「作者 UUID,不暴露內部 Long ID」並落實)。
- **Red 實測**:`$.data.id` = 2(createManual)、4(promote)、5(series create);`$.data.authorId` = 1(getDetail、list) — 洩漏屬實。
- **為何「移除」而非「翻譯成 UUID」**(本項唯一的設計判斷,兩者皆經查證):
  1. **`authorId` 對外無資訊價值**:`snapshotFromContent` 設的是 `v.setAuthorId(article.authorId())` — 存的是**文章作者**而非快照建立者(管理員代建亦同),對同一篇文章的每個版本恆為同值,而呼叫端必然已持有該文章。權限不受影響:ownership 檢查在 service 層以 entity 進行,不倚賴 DTO。
  2. **`categoryId` 恆為 null(死欄位)**:article 早已改多對多分類(`article_categories`),`Article` entity 與 `ArticleContentData` **皆無 category 欄位**,快照流程從未寫入。DB(V16)、entity、DTO **三層皆死**。前端 `EditorMetaSidebar` 用 `categoryIds: string[]` 佐證前端早已是 UUID 世界。
  - **附帶好處**:summary 移除 `authorId` 後,`listSummaries` SQL 不再投影 `author_id`,**列表無需 JOIN users**(避開 N+1,也避開 JOIN 後 `uuid` 欄位 ambiguous 的坑)。
- **設計取捨**:service 內部方法維持回傳 entity(供 ownership 檢查與既有呼叫者),僅 controller 邊界映射 DTO;`toDetailResponse` 因此由 private 改 public。既有斷言 entity 的單元測試(`VersioningServiceTest` 的 `saved.getAuthorId()`)完全不受影響——它們測的是持久化正確性,不是 API 形狀。
- **無部署耦合**(與 H6 不同):前端這些欄位只在型別宣告、無元件讀取,`create/update/promote` 無頁面呼叫,`apiClient` 無 response schema 驗證層 → 前後端 PR 可各自合併。

### ✅ DONE(PR #49,commit `374b617`)— H5. `SeriesController.get` 缺 `@PreAuthorize`,且 `/api/v1/series/**` 非 permitAll(設定與 JavaDoc 矛盾)〔= 07-07 #3,再驗證屬實〕

> **Yuan 裁定採選項 (a)「開放公開」**(2026-07-16):補 `GET /api/v1/series/**` permitAll(僅讀取,寫入維持認證)+ `SeriesController.get` 補豁免 JavaDoc。**PR #49 stacked 在 #47 上,不可先於 #47 合併**(否則未發布文章全文直曝匿名)。

`blog-module-series/.../controller/SeriesController.java:63`。詳見 `2026-07-07-security-txmq-audit-findings.md` #3(該項已標「上線前必須擇一」)。
- **實測佐證**:匿名 `GET /api/v1/series` 與 `/series/{slug}` 修復前皆回 **401**(SecurityConfig 無 series 規則 → 落入 `anyRequest().authenticated()`)。
- **真實後果(本次新發現,backlog 原文未載)**:前端公開頁 `/tags`(`TagsIndexView`)會打 `GET /api/v1/series` → 匿名 401 → 該頁以 `Promise.allSettled` **靜默降級為空清單**(原始碼註解自陳「series 失敗時靜默降級為空清單,區塊會自動隱藏」)→ **匿名訪客看不到系列區塊、登入者才看得到,且無任何錯誤跡象**。這是「設定與文件矛盾」在使用者面的實際代價,也是選 (a) 的決定性證據。
- **一併修**:`CommentController.list:53` 同型較輕案例(路徑早由 `GET /api/v1/articles/**` 涵蓋、實質無越權,僅缺豁免 JavaDoc)→ 純文件補正,零行為變更。
- **security.md / 前端 api-contract.md 已同步**(Yuan 授權),但**兩者皆 untracked,不在 PR diff 內**——見下方治理層觀察。
- **後續(未處理)**:`GET /series` 列表會列出「只含草稿的系列」——`SeriesMapper.findPublic` 用 `WHERE s.article_count > 0`,而 `article_count` 含非 PUBLISHED。無內容洩漏(點進去文章列表是空的),但屬 #49 新增的匿名曝光面,與 #47 的 `articleCount` 口徑問題、M6 競態同源,建議合併為「series 計數口徑」一項處理。

### ✅ DONE(PR #48 + 前端 #38,commit `4c2a403` / 前端 `813df91`)— H6. `verifyEmail` 用 `@RequestParam String token`,憑證進 query string〔本次新發現〕

> 修法:改 `POST` + `@Valid @RequestBody VerifyEmailRequest`。**信件連結不受影響**(見下),前端同步改打 POST 並清掉網址上的 token。

`blog-module-user/.../controller/AuthController.java:212`。`security.md` 原則 8(CRITICAL)明列 token 必須放 `@RequestBody`。同檔 `resetPassword`(:266)正確放 body,證明可改;token 會進 access log 與瀏覽器歷史。
- **~~原註記~~**:~~注意信箱驗證連結目前是 GET,改 body 需前端配合改 POST~~ ← **只對一半**。`UserMailService.buildUrl` 用的是 **`frontendBaseUrl`**,信裡的連結指向**前端頁面** `/verify-email?token=...`(前端有對應 route → `VerifyEmailView.vue`),**不是後端 API**。信件連結本來就該是 GET、完全不用動,已寄出的舊信也不會失效、不需要 grace period。真正要改的只有前端頁面打後端的那一次呼叫。
- **`SecurityConfig` 無須調整**:`/api/v1/auth/**` 為 `permitAll` 且未綁 HTTP method,故本項**不觸及 `security.md`**(所有權表 ❌ 先問)——是讓程式碼符合規範,非變更規範。
- **迴歸守衛**:新增 `verifyEmail_getWithTokenInQueryString_shouldNotBeAllowed`,斷言 GET + query 回 405 **且不得觸及 authService**,避免端點形狀被改回去。三個既有 E2E(`AuthE2E` / `UserLifecycleFlowE2E` / `P0AuthLifecycleRedE2E`)同步改用 body。
- **原則 8 的另一半洞(前端側,已一併堵)**:原則 8 的立法理由是「access log **與瀏覽器歷史記錄**」。只改後端只堵了 access log——token 仍在前端網址 `/verify-email?token=...` 裡,照樣進瀏覽器歷史。前端 #38 於讀入 token 後 `router.replace` 清掉網址參數。
  - **實作陷阱**:token 必須先存成 ref 快照,不能沿用 `route.query` 的 computed——網址清空後 computed 變空字串,畫面會把驗證中的請求誤判為「無效的驗證連結」。
  - **殘留限制(未解)**:`replaceState` 無法消除最初那一次頁面請求,`GET /verify-email?token=...` 仍會進**前端靜態站台(nginx)的 access log**。要完全消除需把信件連結改成 **URL fragment**(`#token=`,不送到伺服器),需後端改信件產生 + 前端改解析。**同型問題 `reset-password` 也有**(`buildUrl("/reset-password", token)`)。→ **已另開追蹤:`ai-docs/backlog/2026-07-18-token-in-url-fragment.md`**(2026-07-18 PR #48 review 補記)。
- **契約文件未同步(刻意)**:`docs/api-contract/*.md` 與前端 `api-reference/openapi.json` 仍記載 `GET`。兩者皆為**特定時點的 runtime capture 稽核快照**(檔頭 Source 節指明來源 log),非手寫規格;手改等同偽造稽核證據。前端 `ai-docs/maintenance.md` §2 明訂「**後端契約變更合併後**重新抓快照」,故為 merge 後步驟。
- **順帶發現(未處理)**:`VerifyEmailView.vue` 的「重新發送驗證信」按鈕綁的是 `verify()`(重試驗證),而非 `authService.resendVerification()`——文案與行為不符。該頁面手上沒有 email 可用,修法需另行設計。

### ✅ DONE(PR #46,commit `cd0b8f3`)— H7. `getArticleByUuid`/`getArticleBySlug` 在 `@Transactional` 讀路徑內發 viewed 事件〔= 07-07 #2〕

> 判定結果:兩處 `@Transactional` 作用域內無 DB 寫入(查詢已由 querySubService 以 readOnly 處理、recordView 僅碰 Redis),移除註解即解。

`blog-module-article/.../service/ArticleServiceImpl.java:46,59`。詳見 `2026-07-07-security-txmq-audit-findings.md` #2。字面違反 §Transaction+MQ;交易內無 DB 寫入故資料不一致風險低。**→ 待判斷**:這兩個 `@Transactional` 是否必要,不必要則移除即解。

---

## 🟡 MEDIUM

### 架構邊界

- **M1. `SeriesMapper` 4 處直讀/JOIN `articles` 業務表**(`:63 findPrevNav`、`:79 findNextNav`、`:92 countPublishedInSeries`、`:101 findSeriesByArticleIds`),繞過 ArticleFacade。唯讀無資料完整性風險,但依 `architecture.md`(articles 明列業務 data,跨模組讀須走 service)為違規。諷刺:同模組**寫**路徑正確走 `articleFacade.updateSeriesAssignment`。**→ 修法**:nav/enrich 讀取改走 ArticleFacade/ArticleQueryService 提供的合規通道。**可連動 `2026-07-07-archunit-guards.md`——加一條「模組不得對他模組業務表下原始 SQL」的 ArchUnit 守衛。**
- **M2. `SeriesService:44` / `BookmarkController:7` 跨模組注入 `ArticleQueryService` 具體類**(非 infrastructure facade 介面)。程式碼自帶註解承認是已知未解議題(「SP-X: ArticleQueryService 跨模組 inject 議題」)。**→ 修法**:抽 infrastructure 介面,或將所需讀取能力併入既有 Facade。

### 交易 / MQ / 冪等

- **M3. `article.published` 孤兒 queue**:`ArticleRabbitMqConfig:107-112` 宣告+綁定但全專案無 consumer 監聽,每次發文堆一則永不 ACK 的訊息。**→ 修法**:刪除該 queue+binding bean(producer 不需為自己事件宣告 queue),或補 consumer。**規範依據**:`code-standards.md`「Producer/Consumer 必須配對」(BUG-2026-001 FIN-1)。
- **M4. 四個 consumer 未做冪等**:`ArticleVersionConsumer`、`ViewCountConsumer`、`EmailVerificationConsumer`、`PasswordResetConsumer` 未用 `IdempotencyService`(僅 `SeriesArticleDeletedConsumer` 有用)。MQ at-least-once 重送 → 重複快照 / 重複灌 view / 重寄信。**且 `schema.md:529` 明文宣稱「所有 consumer 透過 IdempotencyService 做 dedup」與現實不符——schema.md 需校正(所有權表:schema.md ✅ 必須改)。** **→ 修法**:PUBLISHED/freeze 路徑優先補冪等;校正 schema.md 措辭。

### 正確性

- **M5. `comment_count` 與 `totalAll` 頂層留言軟刪後永久不一致**:`CommentMapper` count SQL 保留頂層 tombstone(`parent_id IS NULL` 為真),`CommentService.java:96,188` decrement 卻無條件 -1 → 文章卡片徽章與留言區標頭數字對不上。**→ 修法**:統一計數口徑(count SQL 排除軟刪頂層,或 decrement 條件對齊)。
- **M6. `addArticleToSeries` 計數 read-modify-write 競態**(`SeriesService:160-165`):併發同一文章可把 `series.article_count` 灌成 +2,無唯一約束/行鎖。**→ 修法**:條件式 `WHERE series_id IS NULL` 的原子 UPDATE 判定 isNewMember,或加唯一約束。
- **M7. nickname 唯一性只靠應用層 `existsByNickname`,DB 無 UNIQUE 約束**(`AuthService:108`、`UserService:92`):併發 TOCTOU 重複暱稱。**→ 修法**:評估是否加 DB UNIQUE(需先確認 nickname 是否本就允許重複);若唯一則加約束 + 捕捉違反例外。
- **M8. 列表端點未驗證 page/size**(`ArticleController:76,213`):`page=0` → 負 OFFSET → PostgreSQL 回 500(應 400);`size` 無上限可資源耗盡。**→ 修法**:`@Min/@Max` + 合理上限。
- **M9. `BookmarkService.bookmark` 併發重複收藏拋未捕捉 `DataIntegrityViolationException` → 500**(`:30-39`):like 服務有 try/catch 保冪等,此處遺漏。**→ 修法**:比照 ArticleLikeService 補 catch。

### API / 規範

- **M10. `FileController` 公開端點回傳 `FileMetadata` entity,洩漏 MinIO `storagePath`**(`FileController:96` + `FileMetadata:53`,ID 本身為 UUID 不違反 UUID 規則,但洩漏內部儲存拓撲)。**→ 修法**:改回 response DTO,`storagePath`/`uploaderId` 不對外。
- **M11. `rejectArticle`(`ArticleController:270`)、`updateTag`(`AdminTagController:49`)`@RequestBody` 缺 `@Valid`**,且對應 DTO 欄位無約束註解。**→ 修法**:補 `@Valid` + `@NotBlank/@Size`。
- **M12. 錯誤碼兩套並存**:`GlobalExceptionHandler` 大量用裸 HTTP 字串(`"400"`/`"500"`),但 `CommonErrorCode` 已定義 `A0001`/`B0001` 等結構化碼卻幾乎未使用,客戶端無法可靠 dispatch。**→ 修法**:handler 統一改用 CommonErrorCode enum。
- **M13. 測試方法中文命名 6 筆**(`RecommendControllerIT.java:121,144,159`、`ArticlePublishedConsumerTest.java:68,77,87`,全在 recommend 模組)。`testing-standards.md` 明列 FORBIDDEN。**→ 修法**:改英文 camelCase + `@DisplayName` 保留繁中。
- **M14. `//` 單行註解 88 行 / 20 檔**(article+comment 佔 74%,約 4 成是純裝飾分節線,機械可修)。`code-standards.md`「No Single-line Comments」。**→ 修法**:改 `/** */`,分節線刪除。
- **M15. article 模組 service 拆分後 13 檔完全無 JavaDoc**(`ArticleServiceImpl` 一族 + reading DTO 一批,含缺 @author/@version)。**→ 修法**:補繁中 JavaDoc。

### 效能

- **M16. 文章列表最熱端點對作者資訊 N+1**(`ArticleResponseMapper:111` → 未快取 `userRepository.findById`,一頁 20 次全欄位查詢;tag/series/liked 都批次化唯獨作者沒有)。**→ 修法**:批次查作者或加快取。
- **M17. series 詳情 `getArticleSummariesByIds` N+1**(`ArticleQuerySubService:122`,逐篇 findById + 逐篇單筆 tag 查詢)。

---

## 🟢 LOW(擇期)

- 多個分頁 `ORDER BY created_at` 缺唯一 tiebreaker → 翻頁重複/漏顯(ArticleMapper/CommentMapper/BookmarkMapper/SeriesMapper 多處;修法加 `, id DESC`)。
- `ReadingProgressService` completed 路徑與 flush job 之間 lost-update(進度可能倒退;`:56-58` + `ReadingProgressFlushJob:46-63`,completed 分支未從 dirty set 移除)。
- `/articles/archive` 無分頁全量查詢(`ArticleMapper:149 findAllPublished`)。
- 留言建立/列表未檢查文章發布狀態(`CommentService:62,202`,可利用性受 UUIDv4 隨機性限制)。
- `@DisplayName` 覆蓋 ~90%(129 個 @Test 缺)、約 227 個為英文。
- null 工具類(StringUtils/ObjectUtils/Objects)幾乎未採用(~57 處手寫 null 三元/`== null ||`)——`code-standards.md` 為「建議」級。
- `ArticleMarkdownRenderer` 錯置在 article 模組被 version 跨模組取用(本質是無狀態通用工具,宜下沉 common/infrastructure)。
- 跨模組事件契約位置不一致(部分 event 在 producer 模組內、部分已上移 infrastructure.event)。
- `series_position` 由 client 任意指定、無唯一約束 → 排序不定 + 導覽跳文章。
- `IdempotencyService`「先標記後做事」為刻意 at-most-once 折衷(業務失敗即永久漏做),javadoc 已註明,列此供評估。
- Repo 衛生:根目錄殘留 `nul` 檔(未追蹤)、空的 `src/api/mock` 目錄樹、`.gitignore` 有重複條目(`.worktrees/` 等)。

---

## ⚙️ 治理層觀察(非程式碼,建議先處理)

1. **憲法檔本身尚未進版控**(2026-07-16 更正:是**部分**未進,非全部):`.gitignore` 已加註解宣告「AI 治理層必須進版控」並加了 `!/ai-docs/`、`!/.claude/` 反排除,但實際只有 `ai-docs/architecture.md`、`schema.md`、`integration-tests/*` 進了版控;本次 review 依據的 `security.md`、`code-standards.md`、`testing-standards.md` 與 `.claude/`、`GEMINI.md`、`backlog/*` 全部仍 untracked——制度宣稱與 repo 狀態不符。**→ 建議**:盡快 commit 憲法層。**已非假設性風險**:PR #49 依授權改的 `security.md` 因此無法進入 PR diff,reviewer 無從核對「permitAll 與 Public Endpoints 表一對一」這條規則(該規則正是原則 7 豁免的第 1 條)。每多一個動到治理檔的 PR,這個斷點就多痛一次。
2. **`schema.md` 與現實脫節**:§529 宣稱所有 consumer 都做冪等,實際只有一個(見 M4)。schema.md 應是真相版本。
3. **既有 backlog 的 TODO 未落地**:`2026-07-07-security-txmq-audit-findings.md` #1/#2/#3(= 本檔 C2/H7/H5)已記錄逾一週未修;依 `maintenance.md` §1 鐵律「預防措施不准以 TODO 沉睡」,建議本輪一併清掉。

---

## 驗收條件(整體)

- 每項先驗證發現屬實再動手(驗證不自驗;CRITICAL + auth 叢 HIGH 已由主 session 二次驗證,可直接進 TDD)。
- 修任一項前讀對應憲法檔(見各項「規範依據」);**動 `security.md` / `schema.md` / `CLAUDE.md` 前依所有權表先問 Yuan 或走提案制**。
- 修復後 grep + 呼叫鏈追蹤複查全綠;C1/C2/H1/H2/H3/H4 需補迴歸測試。
- auth 叢(C1+H1+H2)建議合併於一個 `revokeAllSessions(userId, newVersion)` 收斂,一次修完。

## 建議處理順序

1. ~~**C1 + H1 + H2**(auth/session 一叢,最危險且互相關聯)~~ → ✅ PR #45
2. ~~**C2 + H7**(交易/MQ,已在 07-07 backlog,照 TransactionTemplate 模式修)~~ → ✅ PR #46
3. ~~**H3**(Series 洩漏,一行 SQL 見效)~~ → ✅ PR #47(**實際修法非一行 SQL,見該節**)
4. ~~**H6**(安全 Rule 8)~~ → ✅ PR #48 + 前端 #38;~~**H5**(安全 Rule 7)~~ → ✅ PR #49(Yuan 裁定開放公開)。**剩 H4**(DTO 映射層)
5. MEDIUM 依模組批次(Series 邊界 M1/M2、冪等 M3/M4、正確性 M5–M9、規範 M13–M15)
