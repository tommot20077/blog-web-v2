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
