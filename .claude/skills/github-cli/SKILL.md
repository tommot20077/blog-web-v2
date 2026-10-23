---
name: github-cli
description: Interact with the blog-web-v2 GitHub repository using the gh CLI — read/write issues, PRs, branches, files, releases, and code search. Use this skill whenever you need to check open PRs, read issue details, search code, manage branches, inspect CI runs, or do any GitHub operations — even if the user says "check the PR", "look at that issue", "search GitHub for X", or "what's open on GitHub".
---

# GitHub CLI (gh) — blog-web-v2

Repository: `tommot20077/blog-web-v2`（前端：`tommot20077/blog-web-v2-front-end`）

全部透過 `gh` CLI 操作，不使用 GitHub MCP。在 repo 目錄內執行時 `gh` 會自動從 `origin` 推斷 repo；跨 repo 時加 `--repo owner/name`（`gh api` 則直接寫完整路徑）。

## Quick Start

```bash
# 確認登入身份與權限（操作前先確認）
gh auth status

# 列出開放中的 PR / Issues
gh pr list --state open
gh issue list --state open

# 搜尋程式碼
gh search code "ArticleService" --repo tommot20077/blog-web-v2 --language java
```

若 `gh auth status` 顯示未登入 → **STOP**，請 Yuan 自行執行 `gh auth login`（不要代為輸入 token）。

## 指令分類

### Issues
- `gh issue list [--state] [--label] [--assignee]` — 列出 issues
- `gh search issues "<q>" --repo owner/repo` — 關鍵字搜尋
- `gh issue view <n> [--comments]` — 讀取詳情
- `gh issue create --title ... --body ...` / `gh issue edit <n> ...` — 建立 / 更新
- `gh issue comment <n> --body ...` — 新增留言

### Pull Requests
- `gh pr list [--state] [--base] [--head] [--author]` — 列出 PRs
- `gh search prs "<q>" --repo owner/repo` — 搜尋 PRs
- `gh pr view <n> [--json fields]` — 讀取詳情（`--json` 取結構化資料）
- `gh pr diff <n>` / `gh pr checks <n>` — diff 與 CI 狀態
- `gh pr create --base develop --head <branch> --title ... --body ...` — 建立 PR（流程見 `/open-pr`）
- `gh pr edit <n> ...` — 更新 PR
- `gh pr merge <n> --merge|--squash|--rebase` — 合併 PR（**需 Yuan 明確同意**）
- `gh pr update-branch <n>` — 同步 base 到 PR branch

### Code Review 流程（PR Review）
```bash
# 一次提交 review（event: --comment / --approve / --request-changes）
gh pr review <n> --comment --body "整體意見"

# 行內評論：gh pr review 不支援，改走 REST API
gh api repos/{owner}/{repo}/pulls/<n>/comments \
  -f body="..." -f commit_id="<head sha>" -f path="<file>" -F line=<line> -f side=RIGHT
```
需要「pending review + 多則行內評論 + 一次送出」時，用 GraphQL（`addPullRequestReview` + `threads`）。

### 分支管理
- `gh api repos/{owner}/{repo}/branches --paginate --jq '.[].name'` — 列出所有分支
- `git push origin <branch>` / `git push origin --delete <branch>` — 建立 / 刪除遠端分支（需 Yuan 同意）
- `gh api repos/{owner}/{repo}/git/refs -f ref=refs/heads/<new> -f sha=<sha>` — 無本機 checkout 時建立分支

### 檔案操作
- `gh api repos/{owner}/{repo}/contents/<path>?ref=<branch> --jq .content | base64 -d` — 讀取檔案
- 修改檔案：一律在本機改、`git commit`、`git push`（見 `/git-action`），不用 contents API 直接寫入

### 程式碼搜尋
- `gh search code "<keyword>" --repo owner/repo --language java` — 搜尋程式碼
- `gh search repos "<q>"` — 搜尋 repos
- 搜尋用戶：`gh api search/users -f q="<q>"`

### Commits 與 Tags
- `gh api repos/{owner}/{repo}/commits --jq '.[] | [.sha[:7], .commit.message] | @tsv'` — 列出 commits（可加 `-f sha=<branch> -f path=<path>`）
- `gh api repos/{owner}/{repo}/commits/<sha>` — commit 詳情
- `gh api repos/{owner}/{repo}/tags` — 列出 tags

### Releases
- `gh release list` — 列出 releases
- `gh release view [<tag>]` — 最新 release 或指定 tag（省略 tag 為最新）

### CI / Actions
- `gh run list [--branch] [--workflow]` — 列出 workflow runs
- `gh run view <run-id> [--log-failed]` — 查看 run 與失敗 log

## 指令選擇指引

| 情境 | 用哪個 |
|------|--------|
| 列出所有 open PRs | `gh pr list` |
| 搜尋特定標題或作者的 PR | `gh search prs` |
| 列出所有 issues | `gh issue list` |
| 搜尋包含特定文字的 issue | `gh search issues` |
| 查某段程式碼在哪裡 | `gh search code`（本機有 checkout 時優先用 Grep） |
| 讀某個檔案現在的內容 | 本機 `Read`；遠端分支才用 contents API |
| 取得結構化資料給後續處理 | 加 `--json <fields>` 或 `--jq` |
| 沒有對應子指令 | `gh api`（REST 或 `graphql`） |

## 常用搜尋語法

```bash
# 搜尋特定 repo 中的 Java 程式碼
gh search code "UserService" --repo tommot20077/blog-web-v2 --language java

# 搜尋包含特定字串的 issue
gh search issues "bug" --repo tommot20077/blog-web-v2 --label bug

# 搜尋我的 open PR
gh pr list --author @me --state open
```

## 分支命名規範（本專案）

- Feature: `feature/<description>`
- Hotfix: `hotfix/<description>`
- Main branch: `main`
- Development branch: `develop`
- PR 目標：Feature → `develop`，Hotfix → `main`（視情況也 merge 回 develop）
