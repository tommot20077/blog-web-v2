#!/bin/bash

#=============================================================================
# 名稱: backup-restore.sh
# 描述: K8s PostgreSQL數據備份和恢復工具
# 作者: Yuan
# 版本: 1.0.0
#=============================================================================

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# 配置
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKUP_DIR="$PROJECT_ROOT/backups"
KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}

# 輸出函數
print_header() {
    echo -e "${BLUE}==============================================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}==============================================================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# 獲取PostgreSQL配置
get_postgres_config() {
    local pod=$(kubectl get pods -l app=postgres -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    
    if [[ -z "$pod" ]]; then
        print_error "找不到PostgreSQL Pod"
        return 1
    fi
    
    # 從Secret獲取配置
    POSTGRES_USER=$(kubectl get secret postgres-secret -o jsonpath='{.data.POSTGRES_USER}' 2>/dev/null | base64 -d)
    POSTGRES_DB=$(kubectl get secret postgres-secret -o jsonpath='{.data.POSTGRES_DB}' 2>/dev/null | base64 -d)
    POSTGRES_PASSWORD=$(kubectl get secret postgres-secret -o jsonpath='{.data.POSTGRES_PASSWORD}' 2>/dev/null | base64 -d)
    
    if [[ -z "$POSTGRES_USER" || -z "$POSTGRES_DB" ]]; then
        print_error "無法獲取PostgreSQL配置"
        return 1
    fi
    
    echo "$pod"
}

# 創建備份目錄
create_backup_dir() {
    if [[ ! -d "$BACKUP_DIR" ]]; then
        mkdir -p "$BACKUP_DIR"
        print_info "創建備份目錄: $BACKUP_DIR"
    fi
}

# 備份數據庫
backup_database() {
    local backup_name=$1
    
    print_header "數據庫備份"
    
    # 檢查kubectl權限
    if ! kubectl get nodes &> /dev/null; then
        print_warning "修復kubectl權限..."
        sudo chmod 644 /etc/rancher/k3s/k3s.yaml
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
    fi
    
    # 獲取PostgreSQL配置
    local pod=$(get_postgres_config)
    if [[ $? -ne 0 ]]; then
        exit 1
    fi
    
    create_backup_dir
    
    # 生成備份文件名
    if [[ -z "$backup_name" ]]; then
        backup_name="blog_db_$(date +%Y%m%d_%H%M%S)"
    fi
    
    local backup_file="$BACKUP_DIR/${backup_name}.sql"
    local backup_compressed="$BACKUP_DIR/${backup_name}.sql.gz"
    
    print_info "開始備份數據庫..."
    print_info "Pod: $pod"
    print_info "數據庫: $POSTGRES_DB"
    print_info "用戶: $POSTGRES_USER"
    print_info "備份文件: $backup_file"
    
    # 執行備份
    if kubectl exec "$pod" -- pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --verbose > "$backup_file" 2>/dev/null; then
        # 壓縮備份文件
        if gzip "$backup_file"; then
            local file_size=$(du -h "$backup_compressed" | cut -f1)
            print_success "備份完成！"
            print_info "備份文件: $backup_compressed"
            print_info "文件大小: $file_size"
            
            # 記錄備份信息
            echo "$(date '+%Y-%m-%d %H:%M:%S') | $backup_name | $file_size" >> "$BACKUP_DIR/backup_history.log"
        else
            print_warning "備份成功但壓縮失敗"
            print_info "備份文件: $backup_file"
        fi
    else
        print_error "備份失敗"
        rm -f "$backup_file"
        exit 1
    fi
}

# 列出備份文件
list_backups() {
    print_header "備份文件列表"
    
    create_backup_dir
    
    if [[ ! -f "$BACKUP_DIR/backup_history.log" ]]; then
        print_warning "沒有找到備份記錄"
        return
    fi
    
    echo -e "${PURPLE}時間               | 備份名稱                     | 大小${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────${NC}"
    cat "$BACKUP_DIR/backup_history.log"
    
    echo ""
    print_info "備份目錄: $BACKUP_DIR"
    echo -e "${YELLOW}文件列表:${NC}"
    ls -lah "$BACKUP_DIR"/*.sql.gz 2>/dev/null || print_warning "沒有找到備份文件"
}

# 恢復數據庫
restore_database() {
    local backup_file=$1
    
    print_header "數據庫恢復"
    
    # 檢查kubectl權限
    if ! kubectl get nodes &> /dev/null; then
        print_warning "修復kubectl權限..."
        sudo chmod 644 /etc/rancher/k3s/k3s.yaml
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
    fi
    
    # 獲取PostgreSQL配置
    local pod=$(get_postgres_config)
    if [[ $? -ne 0 ]]; then
        exit 1
    fi
    
    # 檢查備份文件
    if [[ -z "$backup_file" ]]; then
        print_error "請指定備份文件"
        list_backups
        return 1
    fi
    
    # 查找備份文件
    local full_path=""
    if [[ -f "$backup_file" ]]; then
        full_path="$backup_file"
    elif [[ -f "$BACKUP_DIR/$backup_file" ]]; then
        full_path="$BACKUP_DIR/$backup_file"
    elif [[ -f "$BACKUP_DIR/${backup_file}.sql.gz" ]]; then
        full_path="$BACKUP_DIR/${backup_file}.sql.gz"
    else
        print_error "找不到備份文件: $backup_file"
        list_backups
        return 1
    fi
    
    print_warning "即將恢復數據庫，這將刪除現有數據！"
    print_info "Pod: $pod"
    print_info "數據庫: $POSTGRES_DB"
    print_info "備份文件: $full_path"
    echo ""
    read -p "確認要繼續嗎？(yes/no): " confirm
    
    if [[ "$confirm" != "yes" ]]; then
        print_info "操作已取消"
        return 0
    fi
    
    print_info "開始恢復數據庫..."
    
    # 解壓並恢復
    if [[ "$full_path" == *.gz ]]; then
        # 壓縮文件
        if zcat "$full_path" | kubectl exec -i "$pod" -- psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"; then
            print_success "數據庫恢復完成！"
        else
            print_error "數據庫恢復失敗"
            exit 1
        fi
    else
        # 未壓縮文件
        if kubectl exec -i "$pod" -- psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < "$full_path"; then
            print_success "數據庫恢復完成！"
        else
            print_error "數據庫恢復失敗"
            exit 1
        fi
    fi
}

# 清理舊備份
cleanup_backups() {
    local keep_days=${1:-30}
    
    print_header "清理舊備份"
    
    create_backup_dir
    
    print_info "清理${keep_days}天前的備份文件..."
    
    local deleted_count=0
    
    # 查找並刪除舊文件
    find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$keep_days -type f | while read -r file; do
        print_info "刪除: $(basename "$file")"
        rm -f "$file"
        deleted_count=$((deleted_count + 1))
    done
    
    if [[ $deleted_count -eq 0 ]]; then
        print_success "沒有需要清理的舊備份"
    else
        print_success "已清理 $deleted_count 個舊備份"
    fi
}

# 驗證備份
verify_backup() {
    local backup_file=$1
    
    print_header "驗證備份"
    
    if [[ -z "$backup_file" ]]; then
        print_error "請指定備份文件"
        return 1
    fi
    
    # 查找備份文件
    local full_path=""
    if [[ -f "$backup_file" ]]; then
        full_path="$backup_file"
    elif [[ -f "$BACKUP_DIR/$backup_file" ]]; then
        full_path="$BACKUP_DIR/$backup_file"
    elif [[ -f "$BACKUP_DIR/${backup_file}.sql.gz" ]]; then
        full_path="$BACKUP_DIR/${backup_file}.sql.gz"
    else
        print_error "找不到備份文件: $backup_file"
        return 1
    fi
    
    print_info "驗證備份文件: $full_path"
    
    # 檢查文件
    if [[ "$full_path" == *.gz ]]; then
        # 檢查gzip文件
        if gzip -t "$full_path"; then
            print_success "壓縮文件完整"
            
            # 檢查SQL內容
            local line_count=$(zcat "$full_path" | wc -l)
            print_info "SQL行數: $line_count"
            
            if [[ $line_count -gt 0 ]]; then
                print_success "備份驗證通過"
            else
                print_error "備份文件為空"
            fi
        else
            print_error "壓縮文件損壞"
        fi
    else
        # 檢查SQL文件
        local line_count=$(wc -l < "$full_path")
        print_info "SQL行數: $line_count"
        
        if [[ $line_count -gt 0 ]]; then
            print_success "備份驗證通過"
        else
            print_error "備份文件為空"
        fi
    fi
}

# 定時備份設置
setup_cron_backup() {
    print_header "設置定時備份"
    
    local script_path="$(realpath "${BASH_SOURCE[0]}")"
    local cron_file="/tmp/backup_cron"
    
    echo "選擇備份頻率:"
    echo "1) 每日 02:00"
    echo "2) 每週日 02:00"
    echo "3) 自定義"
    
    read -p "請選擇 (1-3): " choice
    
    case $choice in
        1)
            echo "0 2 * * * $script_path backup daily_backup_\$(date +\\%Y\\%m\\%d) >> $PROJECT_ROOT/backup.log 2>&1" > "$cron_file"
            ;;
        2)
            echo "0 2 * * 0 $script_path backup weekly_backup_\$(date +\\%Y\\%m\\%d) >> $PROJECT_ROOT/backup.log 2>&1" > "$cron_file"
            ;;
        3)
            read -p "輸入cron表達式 (例如: 0 2 * * *): " cron_expr
            echo "$cron_expr $script_path backup auto_backup_\$(date +\\%Y\\%m\\%d) >> $PROJECT_ROOT/backup.log 2>&1" > "$cron_file"
            ;;
        *)
            print_error "無效選擇"
            return 1
            ;;
    esac
    
    # 安裝cron任務
    crontab "$cron_file"
    rm -f "$cron_file"
    
    print_success "定時備份已設置"
    print_info "查看當前cron任務: crontab -l"
}

# 顯示使用幫助
show_usage() {
    print_header "PostgreSQL備份恢復工具"
    echo "用法: $0 [命令] [參數]"
    echo ""
    echo "命令:"
    echo "  backup [名稱]        - 備份數據庫"
    echo "  restore <文件>       - 恢復數據庫"
    echo "  list                 - 列出備份文件"
    echo "  verify <文件>        - 驗證備份文件"
    echo "  cleanup [天數]       - 清理舊備份 (默認30天)"
    echo "  cron                 - 設置定時備份"
    echo "  help                 - 顯示此幫助"
    echo ""
    echo "示例:"
    echo "  $0 backup                    # 自動命名備份"
    echo "  $0 backup my_backup          # 指定名稱備份"
    echo "  $0 restore my_backup.sql.gz  # 恢復備份"
    echo "  $0 list                      # 列出所有備份"
    echo "  $0 cleanup 7                 # 清理7天前的備份"
    echo ""
    echo "備份目錄: $BACKUP_DIR"
}

# 主函數
main() {
    local command=$1
    local param=$2
    
    case "$command" in
        backup)
            backup_database "$param"
            ;;
        restore)
            restore_database "$param"
            ;;
        list)
            list_backups
            ;;
        verify)
            verify_backup "$param"
            ;;
        cleanup)
            cleanup_backups "$param"
            ;;
        cron)
            setup_cron_backup
            ;;
        help|--help|-h)
            show_usage
            ;;
        *)
            if [[ -z "$command" ]]; then
                show_usage
            else
                print_error "未知命令: $command"
                show_usage
                exit 1
            fi
            ;;
    esac
}

# 執行主函數
main "$@"