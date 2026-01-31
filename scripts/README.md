# K8s服務管理腳本

這個目錄包含了用於管理K8s微服務環境的便捷腳本工具集。

## 腳本列表

### 🚀 k8s-manager.sh - 主管理腳本
統一管理所有K8s服務的啟動、停止、重啟和狀態查看。

**使用方法:**
```bash
# 啟動所有服務
./k8s-manager.sh start all

# 查看服務狀態
./k8s-manager.sh status postgres

# 重啟Redis
./k8s-manager.sh restart redis

# 查看服務日誌
./k8s-manager.sh logs postgres -f
```

### 📊 get-services-info.sh - 服務信息查看
顯示所有服務的連接信息，包括IP地址、端口、用戶名等。

**使用方法:**
```bash
# 顯示所有服務信息
./get-services-info.sh

# 保存信息到文件
./get-services-info.sh > services-info.txt
```

### 📋 k8s-logs.sh - 日誌查看工具
交互式日誌查看工具，支持實時跟蹤、篩選等功能。

**使用方法:**
```bash
# 交互式選擇Pod
./k8s-logs.sh

# 查看特定服務日誌
./k8s-logs.sh postgres

# 實時跟蹤日誌
./k8s-logs.sh -f redis

# 查看最後50行日誌
./k8s-logs.sh -t 50 postgres
```

### ⚙️ k8s-dev-setup.sh - 環境初始化
一鍵設置整個開發環境，包括K3s、服務部署、權限設置等。

**使用方法:**
```bash
# 初始化開發環境
./k8s-dev-setup.sh
```

### 💾 backup-restore.sh - 備份恢復工具
PostgreSQL數據庫備份和恢復工具。

**使用方法:**
```bash
# 備份數據庫
./backup-restore.sh backup

# 指定備份名稱
./backup-restore.sh backup my_backup

# 列出所有備份
./backup-restore.sh list

# 恢復數據庫
./backup-restore.sh restore my_backup.sql.gz

# 清理舊備份（保留30天）
./backup-restore.sh cleanup

# 設置定時備份
./backup-restore.sh cron
```

## 快速開始

### 1. 首次環境設置
```bash
# 初始化整個環境
./scripts/k8s-dev-setup.sh
```

### 2. 日常使用
```bash
# 查看服務狀態
./scripts/k8s-manager.sh status all

# 查看連接信息
./scripts/get-services-info.sh

# 重啟某個服務
./scripts/k8s-manager.sh restart postgres
```

### 3. 問題排查
```bash
# 查看日誌
./scripts/k8s-logs.sh postgres

# 實時監控日誌
./scripts/k8s-logs.sh -f redis
```

## 添加到PATH（推薦）

將腳本目錄添加到PATH以便全局使用：

```bash
# 添加到~/.bashrc
echo 'export PATH="/mnt/d/end/workspace/java/blog-web-v2/scripts:$PATH"' >> ~/.bashrc
source ~/.bashrc

# 現在可以直接使用
k8s-manager.sh status all
get-services-info.sh
```

## 創建便捷別名

```bash
# 添加到~/.bashrc
cat >> ~/.bashrc << 'EOF'
# K8s便捷別名
alias k8s-start="k8s-manager.sh start all"
alias k8s-stop="k8s-manager.sh stop all"
alias k8s-status="k8s-manager.sh status all"
alias k8s-info="get-services-info.sh"
alias k8s-logs="k8s-logs.sh"
EOF

source ~/.bashrc
```

## 故障排除

### kubectl權限問題
```bash
sudo chmod 644 /etc/rancher/k3s/k3s.yaml
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
```

### K3s服務問題
```bash
sudo systemctl status k3s
sudo systemctl restart k3s
```

### Pod無法啟動
```bash
kubectl get pods
kubectl describe pod <pod-name>
kubectl logs <pod-name>
```

## 項目結構

```
scripts/
├── README.md              # 本文件
├── k8s-manager.sh         # 主管理腳本
├── get-services-info.sh   # 服務信息查看
├── k8s-logs.sh           # 日誌查看工具
├── k8s-dev-setup.sh      # 環境初始化
└── backup-restore.sh     # 備份恢復工具
```

## 注意事項

1. 需要sudo權限來操作K3s
2. 備份文件存儲在項目根目錄的`backups/`目錄下
3. 所有腳本都包含詳細的錯誤處理和用戶提示