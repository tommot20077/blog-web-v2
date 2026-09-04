# 2026-09-04 develop 三維度稽核

- **審查對象**：`origin/develop`（PR #53–#59 全部合併後的狀態）
- **維度**：安全 / 架構 / 性能，各由獨立的 reviewer 產出
- **原始 94 條 → 去除 11 組跨維度重述後 83 條**：HIGH 10 / MEDIUM 47 / LOW 26，無 CRITICAL

## 這個目錄與 `ai-docs/findings.md` 的分工

| 檔案 | 內容 | 什麼時候讀 |
|------|------|-----------|
| `../../findings.md` | **登記簿**——每條 finding 一到數行，含狀態（OPEN/DONE）與交叉主題 | 想知道「有哪些問題、修到哪」 |
| `triage.md` | **決策文件**——四象限行動清單、11 組同根因合併明細、12 項待拍板議題的完整選項與取捨、跨維度衝突（修 A 會踩到 B） | 要決定「先修哪個、怎麼修」 |
| `security.md` / `architecture.md` / `performance.md` | **原始報告**——逐條的證據鏈、file:line、攻擊或退化情境、與基線的對照 | 真的要動某一條時，看它的完整推導 |

登記簿是索引，這裡是推導過程。**動任何一條之前先讀對應的原始報告**——很多條目的「為什麼是這樣」比「是什麼」更重要（例如某個 backdrop 為何是 `--bg-sub`、某條 regex 在兩個 matcher 引擎下的行為差異）。

## 已在 2026-09-04 當天處理掉的

| finding | PR |
|---------|-----|
| ARCH-05 ＝ PERF-14 孤兒 queue | #64 |
| SEC-09 + SEC-27 permitAll 過寬與文件落差 | #65 |
| SEC-02（＝基線 AUTH-01/DATA-03）restore 繞過狀態守衛 | #66 |
| SEC-01 client IP 信任鏈 | 暫緩，記錄於 `../../backlog/2026-09-04-client-ip-trust-chain.md` |

## 12 項待拍板議題

見 `triage.md` §4 與 `../../findings.md` 的 Q4–Q15。2026-09-04 已拍板 Q4/Q5（收窄 permitAll + 修表）、Q6（restore 不還原 status）、Q7（刪孤兒 queue），其餘待處理。
