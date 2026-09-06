# 給未來 Session 的信(Institution Notes)

> 寫於 2026-07-07,由一次性的高階模型 session 立制度時留下。讀者:之後在此 repo 工作的任何模型與 Yuan。

## 這套制度是什麼

2026-07-07 依 `D:\end\institution-authoring-prompt.md` 的骨架,對本 repo 做了一次治理盤點與補洞:
新增 `ai-docs/{judgment,agent-dispatch,task-briefs,maintenance}.md`、把 BUG-2026-001 懸置的預防措施晉升進 `code-standards.md`/`security.md`、修復 `post-bug`/`review-bugs` skill 的 MEMORY.md 懸空引用、把 `AGENTS.md`/`GEMINI.md` 收斂為指標檔。原則:**在既有機器上補洞,不建平行系統**。

## 三件沒人問但最重要的事

1. **飛輪只有在餵料時才轉**:全 repo 只有 1 份 bug report,`/review-bugs` 從未跑過。制度已修好管道,但如果修完 bug 不跑 `/post-bug`,這套系統三個月後就是死文件。最低要求:每個 `fix:` commit 之後跑一次。
2. **ArchUnit 守衛還沒寫**(`ai-docs/backlog/2026-07-07-archunit-guards.md`):BUG-2026-001 的兩條程式類防線只存在於文件層。文件擋不住不讀文件的人,測試才擋得住。這是 backlog 裡價值最高的一項。
3. **前後端契約沒有單一真相**:本 repo 有 `docs/api-contract/`,前端 repo 有 `ai-docs/api-contract.md` + `api-reference/openapi.json`,兩邊各自維護、無同步規則。跨 repo 契約變更目前靠人記得。這次只在兩邊 CLAUDE.md 互加了指標;真正的解法(單一契約源 + 生成)需要 Yuan 決策。

## 這套制度最可能的退化方式(與預防)

- **有人直接編輯 AGENTS.md/GEMINI.md** → 三胞胎漂移重演。預防:兩檔已寫明「pointer only」;`maintenance.md` §5 健康檢查會抓。
- **CLAUDE.md 慢慢長胖** → 常載超標、路由變垃圾場。預防:上限 150 行寫進 `maintenance.md` §3,新內容一律新檔+一行索引。
- **judgment.md 變成沒人讀的散文** → 預防:每條保持「判準+正反例」結構;新增條目必須源自真實踩雷(bug report),不收通用常識。
- **預防措施重新開始沉睡** → `maintenance.md` §1 的鐵律 + `/review-bugs` Step 2 的 Prevention gaps 檢查是防線。

## 待 Yuan 確認的一個決策

2026-07-07 發現 `.gitignore` 原本排除 `/ai-docs/`、`/CLAUDE.md`、`/GEMINI.md`、`/.claude/`——導致大半制度檔不在版控,git worktree/clone/CI 裡讀不到(而制度要求 subagent 在 worktree 工作)。已把這些排除移掉(機器本地檔 `settings.local.json`、`worktrees/` 仍排除),**但尚未 commit**。若 Yuan 本意是不讓這些檔上公開 GitHub:還原 `.gitignore` 那幾行,並改用「worktree 建立後從主 checkout 複製制度檔」的替代方案(需在 using-git-worktrees skill 加一步)。

## 誠實條款:信心最低的產出

1. **agent-dispatch.md 的模型清單**(2026-07 快照)——型號與定位會過時,檔內已標註「用前查證」。
2. **AGENTS.md/GEMINI.md 指標檔對 Codex/Gemini 的實效**——未實測這兩個工具是否會乖乖跟著指標去讀 CLAUDE.md;若實測發現不會,退路是恢復同步複製、但把「同步」寫成 skill 步驟。
3. **task-briefs.md 的驗收條件粒度**——是否足以讓 Sonnet 級 subagent 穩定產出,需要 1-2 次實戰校準;收尾的使用測試只驗了一個場景。

## 2026-09-02 分支整理紀錄(給下一個 session)

- 2026-08-16 批次合併 #53–#59 後,本地所有 feature/fix 分支均已進 develop;本輪把它們(含 pr53/54/55 審查分支、空的 fix/minio-public-read、只剩三方合併殘留的 integration/local-e2e)本地與遠端一併刪除。develop 是唯一活的整合線。
- `origin/claude/fullstack-review-architecture-fdmjbd`(2026-07 稽核)只有 docs 被移植(PR #60);其 `59c170d fix(auth)` 交由 2026-09-02 security review 判定 develop 是否已涵蓋;`a4651c5` CRLF→LF 正規化**未搬**——所以 fresh worktree 的 `git status` 會顯示上百個 CRLF「modified」噪音,根因是 .gitattributes 加入後從未 `git add --renormalize .`,要不要做一次由 Yuan 決定(會是一個 ~190 檔的純格式 commit)。
- **main 落後 develop 467 commits**,這輪未動;後端 main 沒有獨立 hotfix,release 時直接開 develop → main PR。
- repo 實際路徑已從 `D:\end\workspace\java\blog-web-v2` 搬到 `D:\backup\backup\程式\workspace\java\blog-web-v2`(前端同樣搬到 `D:\backup\backup\程式\workspace\vue\`);CLAUDE.md「External Repositories」與 agent-dispatch.md 的姊妹檔路徑仍是舊的,待 Yuan 確認新路徑是長期位置再一併改。 → **2026-09-05 已由 Yuan 確認並修正**(見下節)。稽核報告與 findings.md 內的舊路徑**刻意未改**——那些是當時事實的紀錄,改動等於竄改推導過程。
- `.worktrees/` 內殘留的舊 checkout 目錄(admin-console、article-toc、context-smoke、minio、version-fix、merge-withdraw、qa-backlog)經 blob 比對確認內容全在 git 物件庫,已刪除。

## 2026-09-05 紀錄(集合述詞規範分支的收尾)

### 已解決:姊妹 repo 路徑

2026-09-02 掛著等確認的那條已由 Yuan 拍板。`CLAUDE.md`「External Repositories」與
`agent-dispatch.md:4` 的姊妹檔路徑已更新為 `D:\backup\backup\程式\workspace\...`。
**這條過期路徑不是理論問題**:本輪一個 subagent 依它去找前端 repo、找不到,只好把一條
本來查得到的宣稱標記為「無法驗證」,由控制端補查才發現引用其實正確。
教訓:**常載層的路徑錯誤不會報錯,只會讓下游默默降級成「查不到」**。

### 新增判斷條文:同批 commit 內的文件不得前向引用尚未落地的程式碼

**踩雷經過**:一批三個 commit(A 改測試命名 / B 更正文件結論 / D 修守衛程式碼),
要求「每個 commit 可獨立審查」。B 的 backlog 編輯寫了「三形式反向驗證已完成」,
但那個驗證是 D 才落地的 —— 單獨 checkout B 時,文件描述了程式碼裡不存在的事實。

**為什麼值得立條文**:這個錯誤在**寫的當下**成本是零(換個時態即可),
在 **review 時**才發現則補救成本極高——要在一個有明文 CRLF 正規化危害的 repo 上
對多個 commit 動歷史手術。本次判定 park(最終樹正確、分支未推送、且會以 merge commit
進 develop 故中間狀態不出現在 first-parent 歷史),但那是**權衡後接受瑕疵**,不是沒事。

**條文**:拆分 commit 時,每個 commit 內的文件敘述只能描述**該 commit 當下為真**的事。
需要引用後續 commit 的成果時,改用前瞻語氣("將於…"),或把該句留到成果落地的那個 commit。

### 另一個值得記的模式:自我驗證矩陣會系統性漏掉最難的那一格

本輪一位實作者為 regex 假陽性做了驗證矩陣,測了 `my_articles_archive`(前綴變體)
卻**漏了** `articles_archive`(尾綴變體)。前者因字面不符而失敗,根本沒觸及
`\b` 與底線的邊界邏輯——也就是整條 regex 安全性的真正機制。程式碼是對的,
但**自述證據恰好在最難的那一點上有缺口**。
對 reviewer 的意涵:看到自我驗證矩陣時,先問「最容易寫錯的那一格在不在裡面」。
