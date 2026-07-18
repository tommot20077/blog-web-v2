---
name: git-action
description: Stage, commit, and push changes to the current feature or hotfix branch. Use after completing a logical unit of work to create a well-formed commit following Conventional Commits with Traditional Chinese subject.
---

# Skill: git-action

Stage, commit, and push changes to the current **feature** or **hotfix** branch.
Invoke this skill multiple times during a feature — once per logical change.
When ready for review, run `/open-pr`.

---

## Step 0 — Branch Guard

```bash
git branch --show-current
```

- If on `main` or `develop`: **STOP**.
  - Ask Yuan for a feature branch name.
  - Then run:
    ```bash
    git checkout develop && git checkout -b feature/<name>
    ```
- If on `feature/*` or `hotfix/*`: proceed to Step 1.

---

## Step 1 — Inspect

```bash
git status
git diff
git diff --staged
```

---

## Step 2 — Stage

- Stage **only** task-relevant files. Never `git add -A`.
- Exclude: `.env`, `target/`, `*.class`, credentials, `*.log`.
- If the diff spans unrelated concerns → ask Yuan to split into separate commits.

```bash
git add <specific-files>
```

---

## Step 3 — Commit

遵循 **Conventional Commits v1.0.0**（https://www.conventionalcommits.org/zh-hant/v1.0.0/）。

- **type**: `feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`
- **scope**: 模組（`user` / `article` / `common` / `infrastructure`）或層次（`controller` / `service` / `repository` / `model` / `dto` / `config` / `security` / `exception`）；可組合，如 `user/service`
- **Subject**: 繁體中文、祈使語氣、不加句號、不超過 72 字元

### Commit 格式（完整版）

```
<type>(<scope>): <繁體中文 subject>       ← 必填，≤ 72 字元，不加句號

[optional body]                            ← 選用，空一行後說明動機/異動
[optional body continued...]

[optional footer(s)]                       ← 選用，空一行後
Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
BREAKING CHANGE: <說明>                    ← 若有 breaking change
Fixes #<issue-number>                      ← 若關閉 issue
```

### Breaking Change 寫法（二擇一，可同時使用）

在 type 後加 `!`，並於 footer 加 `BREAKING CHANGE:` 說明：

```
feat(security)!: 移除舊版 RSA JWT 支援

BREAKING CHANGE: 所有 JWT 必須改用 ES256，舊 token 即時失效

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

### Commit 指令模板

```bash
git commit -m "$(cat <<'COMMIT_MSG'
<type>(<scope>): <繁體中文 subject>

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
COMMIT_MSG
)"
```

含 body 時：

```bash
git commit -m "$(cat <<'COMMIT_MSG'
<type>(<scope>): <繁體中文 subject>

<動機與異動說明>

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
COMMIT_MSG
)"
```

### Examples

```
feat(user/service): 新增登入失敗鎖定（5次 → 15分鐘）

連續 5 次密碼錯誤後以 Redis Counter 鎖定帳號 15 分鐘，
防止暴力破解。鎖定期間回傳 A0114 ACCOUNT_LOCKED。

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>
```

```
fix(security): 修正 JWT version claim key 讀取錯誤
```

```
test(article/controller): 補充 RBAC 403 拒絕場景整合測試
```

```
docs(common): 更新 Permission enum JavaDoc 對齊 plan2.md §7.2
```

```
refactor(article/repository): 簡化文章查詢邏輯
```

---

## Step 4 — Push

```bash
git push -u origin <current-branch>
```

- **Never** force-push.
- **Never** skip hooks (`--no-verify`).

---

## Step 5 — Report

Print:
- Current branch name
- Commit SHA (short)
- Commit subject

Then remind Yuan:

> 提交完成。準備好 Code Review 時，請執行 `/open-pr` 建立 Pull Request。
