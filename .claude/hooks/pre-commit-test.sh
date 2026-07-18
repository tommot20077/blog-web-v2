#!/usr/bin/env bash
# 讀取 PreToolUse payload（JSON from stdin）
INPUT=$(cat)

# 取出 Bash 指令內容
COMMAND=$(echo "$INPUT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('tool_input',{}).get('command',''))" 2>/dev/null)

# 若不是 git commit 指令，直接略過
if ! echo "$COMMAND" | grep -q "git commit"; then
    exit 0
fi

# 動態取得當前 worktree 的根目錄，適用於主目錄與任何 git worktree
REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$REPO_ROOT" ]; then
    echo "=== ⚠️  無法取得 git 根目錄，略過測試 ==="
    exit 0
fi

echo "=== 執行測試（pre-commit，worktree: $REPO_ROOT）==="
cd "$REPO_ROOT"
./mvnw test --fail-at-end --no-transfer-progress  -T 2C -q 2>&1

if [ $? -ne 0 ]; then
    echo "=== ❌ 測試失敗，請修正後再 commit ==="
    exit 2
fi

echo "=== ✅ 測試通過 ==="
exit 0
