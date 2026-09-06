# 專案判斷準則(Judgment)

> 讀者:在此 repo 工作的任何 AI session。這裡放**機械規則蓋不到的判斷**;機械規則本身在 `ai-docs/code-standards.md`、`ai-docs/security.md` 等,不在這重複。
> 每條格式:判準 → ✅ 正例 → ❌ 反例。

## 1. 何時算「真完成」

判準:對應層級的測試綠(有 surefire/log 證據)+ 測試輸出乾淨(pristine)+ 改動範圍內無 TODO 殘留。「編譯過」「理論上可行」都不是完成。

- ✅ 「`ArticleServiceTest` 12 綠,`logs/test-output.log` 無 WARN 以上雜訊,見 `blog-module-article/target/surefire-reports/TEST-*.xml`」
- ❌ 「已修改 `ArticleServiceImpl`,邏輯正確,應該會過」——沒跑測試就回報完成

## 2. 危險模式訊號(看到就停,不是繼續寫)

判準:下列訊號代表你正走進已知地雷,先查對應規範/報告再動:

| 訊號 | 對應地雷 |
|------|----------|
| `@Transactional` 方法內出現 `rabbitTemplate` | BUG-2026-001 FIN-2 → `code-standards.md` §Transaction+MQ |
| 新增 Consumer/binding 但 diff 裡沒有 Producer(或反之) | BUG-2026-001 FIN-1 → `code-standards.md` §Transaction+MQ(配對規則) |
| `@RequestParam` 傳密碼/token | BUG-2026-001 U-1 → `security.md` 原則 8 |
| 端點用 `@AuthenticationPrincipal` 但沒有 `@PreAuthorize` | BUG-2026-001 FIN-3+4 → `security.md` 原則 7 |
| 想在 SQL 裡 UPDATE 別的模組的業務表 | 跨模組邊界 → `architecture.md` 速查表 |
| Controller JavaDoc 宣稱「公開」 | 交叉核對 `SecurityConfig` 是否真有對應 `permitAll()`——文件自稱公開不算數 |

- ✅ 看到訊號 → 開對應規範檔,照規範寫,並在回報中註明「已按 §X 處理」
- ❌ 「這裡先照舊模式寫,之後再統一」——BUG-2026-001 的根因就是這句話

## 3. Migration 不可變性

判準:已進版控的 `V1..V{N}` migration 檔**永不修改**;任何 schema 變更一律新增 `V{N+1}`,並同步 `ai-docs/schema.md`(CLAUDE.md「Schema Maintenance」)。

- ✅ 發現 V9 的欄位型別錯 → 新增 `V13__fix_xxx_column_type.sql` + 更新 schema.md 對應區塊
- ❌ 直接改 `V9__xxx.sql` 的內容——已套用過的環境 checksum 會炸

## 4. 測試失敗的處理方向

判準:測試失敗時,先判斷「測試對還是程式對」。修法只能是修根因;任何讓測試「安靜下來」的手段都是方向錯誤。

- ✅ 讀 surefire 報告 → 定位根因 → 修程式或修錯誤的測試斷言(並說明為何測試錯)
- ❌ 弱化斷言、`@Disabled`、加 sleep 治 flaky、catch-all 吞例外、改 production 邏輯去遷就錯誤的測試——出現任何一個 = 換路訊號,停下重想

## 5. 何時停下問 Yuan(而不是自己決定)

判準:以下變更影響面超出單一任務,必問:

- `security.md` Public Endpoints 表的任何增減、`@PreAuthorize` 權限放寬
- 對**既有表**的破壞性 migration(DROP/型別變更/NOT NULL 回填)
- API 契約破壞性變更(欄位刪除/改名/語意變更)——同時牽動前端 repo
- 重寫任何系統(code-standards:「Ask before rewriting systems」)
- 測試要求豁免(testing-standards:「unless explicitly waived by Yuan」)

- ✅ 「此修法需要把 `GET /api/v1/users/**` 移出公開表,影響前端,先停下來確認」
- ❌ 自行放寬 `@PreAuthorize` 讓測試通過

## 6. 何時該換路而非重試

判準:同一修法失敗兩次、或每次修完冒出新的同型錯誤 → 你的假設錯了,停止重試,回到根因分析(讀報告/讀規範/縮小重現範圍),必要時按 `agent-dispatch.md` 升級。

- ✅ 兩次修 NPE 都在不同位置再炸 → 停,畫出資料流,找真正的 null 源頭
- ❌ 第三次在新位置加 null check

## 7. 品質底線怎麼驗(抽查法)

判準:驗收別人(subagent)的實作時,不全信回報,抽查三件事:RED 證據(測試先失敗過)、測試名稱與場景表對得上、diff 範圍沒超出任務範圍。

- ✅ 抽開回報中的測試檔,確認斷言真的驗行為,而非 `assertTrue(true)` 型煙霧
- ❌ 「subagent 說 12 綠」就標完成

## 8. 寫新程式碼前先找同類的既有防線

判準:要處理的問題若可能已被處理過(切批、分頁夾界、交易與 MQ 時序、可見性判斷),**先在同一個類、再擴到同模組 grep 既有的常數或 helper**。找到就沿用;判斷不該沿用時,在程式碼(不是只在 commit message)寫下理由。

**這條源自同型錯誤的兩次出現,且距離在縮短**:BUG-2026-001 是 File 模組已有正確的 `TransactionTemplate` 模式、Article/User 沒沿用(跨模組);BUG-2026-003 是 `filterPublishedUuids` 已有切批防線、同一個類裡的新方法沒沿用(同檔案)。「把最佳實踐文件化並推廣」擋不住它——那是 BUG-2026-001 當時就下的對策。

- ✅ 新增集合述詞方法前,先看到同類已有 `PUBLISHED_FILTER_BATCH_SIZE = 500`,沿用它或抽成共用原語
- ❌ 新方法自己在 JavaDoc 標一個門檻(5,000),與同一個檔案裡的既有常數(500)並存,兩份標準都沒人察覺

**反向同樣成立:發現一個盲點模式時,回頭掃描既有防線是否有同一個盲點。** 這條是 PR #71 的 review 逼出來的——我在普查 `<foreach>` 時第一次用的 pattern 漏掉了 XML mapper,並把該教訓寫進了 BUG-2026-003;但沒有回頭問「既有的守衛 #5 是不是也對 XML 失明」,而它確實是。**剛踩過的坑,最有可能也埋在你正信任的那道防線裡。**

- ✅ 發現 grep pattern 漏掉 XML mapper → 立刻檢查所有依賴類似 pattern 的守衛與稽核腳本
- ❌ 把盲點寫進 bug report 就當結案,不檢查同型盲點是否存在於既有防線

## 9. 界限要寫進程式,不是只寫進 JavaDoc

判準:凡是「超過某個量就會壞」的界限(集合大小、分頁上限、逾時),**必須有一段程式碼在執行期強制它**。JavaDoc 標註是給 reviewer 看的,不會在門檻到達時做任何事。

- ✅ 無上界輸入走 `BatchedQuery.queryInBatches`;分頁上限由 `PageQuery` 的 compact constructor 保證(型別層面不可繞過)
- ❌ 在 JavaDoc 寫「重評門檻:任一使用者 B > 5,000」然後不做任何事——到了 5,000 沒有任何機制會反應
