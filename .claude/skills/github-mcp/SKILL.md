---
name: github-mcp
description: Interact with the blog-web-v2 GitHub repository using MCP tools — read/write issues, PRs, branches, files, and code search. Use this skill whenever you need to check open PRs, read issue details, create or update files on GitHub, search code, manage branches, or do any GitHub operations — even if the user says "check the PR", "look at that issue", "search GitHub for X", or "what's open on GitHub".
---

# GitHub MCP — blog-web-v2

Repository: `tommot20077/blog-web-v2`

## Quick Start

```
# 確認目前使用者身份
get_me()

# 列出開放中的 PR
list_pull_requests(owner="tommot20077", repo="blog-web-v2", state="open")

# 列出開放中的 Issues
list_issues(owner="tommot20077", repo="blog-web-v2", state="open")

# 搜尋程式碼
search_code(q="ArticleService repo:tommot20077/blog-web-v2")
```

## 工具分類

### 身份確認
- `get_me()` — 取得目前登入的 GitHub 用戶資訊（操作前先確認權限）

### Issues
- `list_issues(owner, repo, state?, labels?, assignee?)` — 列出 issues（優先用 list）
- `search_issues(q)` — 用關鍵字搜尋 issues（複雜條件用 search）
- `issue_read(owner, repo, issue_number)` — 讀取 issue 詳情
- `issue_write(owner, repo, ...)` — 建立或更新 issue
- `add_issue_comment(owner, repo, issue_number, body)` — 新增留言
- `sub_issue_write(...)` — 管理 sub-issues

### Pull Requests
- `list_pull_requests(owner, repo, state?, base?, head?)` — 列出 PRs
- `search_pull_requests(q)` — 搜尋 PRs
- `pull_request_read(owner, repo, pull_number)` — 讀取 PR 詳情
- `create_pull_request(owner, repo, title, head, base, body?)` — 建立 PR
- `update_pull_request(owner, repo, pull_number, ...)` — 更新 PR
- `merge_pull_request(owner, repo, pull_number, merge_method?)` — 合併 PR
- `update_pull_request_branch(owner, repo, pull_number)` — 更新 PR branch（同步 base）
- `pull_request_review_write(...)` — 建立/提交 Code Review

### Code Review 流程（PR Review）
```
# Step 1: 建立 pending review
pull_request_review_write(owner, repo, pull_number, method="create")

# Step 2: 新增行內評論
add_comment_to_pending_review(owner, repo, pull_number, path, line, body)

# Step 3: 提交 review
pull_request_review_write(owner, repo, pull_number, method="submit_pending", event="COMMENT")
```

### 分支管理
- `list_branches(owner, repo)` — 列出所有分支
- `create_branch(owner, repo, branch, from_branch?)` — 建立分支

### 檔案操作
- `get_file_contents(owner, repo, path, branch?)` — 讀取檔案內容
- `create_or_update_file(owner, repo, path, message, content, sha?)` — 建立或更新檔案
- `delete_file(owner, repo, path, message, sha)` — 刪除檔案
- `push_files(owner, repo, branch, files, message)` — 批次推送多個檔案

### 程式碼搜尋
- `search_code(q)` — 搜尋程式碼（語法：`keyword repo:owner/repo language:java`）
- `search_repositories(q)` — 搜尋 repos
- `search_users(q)` — 搜尋用戶

### Commits 與 Tags
- `list_commits(owner, repo, branch?, path?)` — 列出 commits
- `get_commit(owner, repo, sha)` — 取得 commit 詳情
- `list_tags(owner, repo)` — 列出 tags
- `get_tag(owner, repo, tag)` — 取得 tag 詳情

### Releases
- `list_releases(owner, repo)` — 列出 releases
- `get_latest_release(owner, repo)` — 取得最新 release
- `get_release_by_tag(owner, repo, tag)` — 用 tag 查 release

## 工具選擇指引

| 情境 | 用哪個 |
|------|--------|
| 列出所有 open PRs | `list_pull_requests` |
| 搜尋特定標題或作者的 PR | `search_pull_requests` |
| 列出所有 issues | `list_issues` |
| 搜尋包含特定文字的 issue | `search_issues` |
| 查某段程式碼在哪裡 | `search_code` |
| 讀某個檔案現在的內容 | `get_file_contents` |
| 一次更新多個檔案 | `push_files` |

## 常用搜尋語法

```
# 搜尋特定 repo 中的 Java 程式碼
search_code(q="UserService repo:tommot20077/blog-web-v2 language:java")

# 搜尋包含特定字串的 issue
search_issues(q="bug label:bug repo:tommot20077/blog-web-v2")

# 搜尋我的 open PR
search_pull_requests(q="is:open author:tommot20077 repo:tommot20077/blog-web-v2")
```

## 分支命名規範（本專案）

- Feature: `feature/<description>`
- Hotfix: `hotfix/<description>`
- Main branch: `main`
- Development branch: `develop`
- PR 目標：Feature → `develop`，Hotfix → `main`（視情況也 merge 回 develop）
