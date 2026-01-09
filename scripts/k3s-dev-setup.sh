#!/bin/bash

#=============================================================================
# 名稱: k3s-dev-setup.sh
# 描述: k3s開發環境初始化腳本
# 作者: Yuan
# 版本: 1.0.0
#=============================================================================

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 配置
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
k3s_DIR="$PROJECT_ROOT/k3s"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"
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

# 進度條
show_progress() {
    local current=$1
    local total=$2
    local width=50
    local percentage=$((current * 100 / total))
    local completed=$((width * current / total))
    
    printf "\r["
    printf "%${completed}s" | tr ' ' '='
    printf "%$((width - completed))s" | tr ' ' '-'
    printf "] %d%%" $percentage
}

# 檢查系統要求
check_requirements() {
    print_header "檢查系統要求"
    
    local requirements_met=true
    
    # 檢查WSL2
    if grep -q Microsoft /proc/version; then
        print_success "運行在WSL2環境"
    else
        print_warning "不是WSL2環境，某些功能可能受限"
    fi
    
    # 檢查Docker
    if command -v docker &> /dev/null; then
        print_success "Docker已安裝: $(docker --version)"
    else
        print_error "Docker未安裝"
        requirements_met=false
    fi
    
    # 檢查kubectl
    if command -v kubectl &> /dev/null; then
        print_success "kubectl已安裝"
    else
        print_warning "kubectl未安裝，將使用k3s kubectl"
    fi
    
    # 檢查必要文件
    if [[ ! -d "$k3s_DIR" ]]; then
        print_error "k3s配置目錄不存在: $k3s_DIR"
        requirements_met=false
    else
        print_success "k3s配置目錄存在"
    fi
    
    if [[ "$requirements_met" == "false" ]]; then
        print_error "系統要求檢查失敗，請先安裝必要的工具"
        exit 1
    fi
    
    echo ""
}

# 檢查和啟動K3s
setup_k3s() {
    print_header "設置K3s"
    
    # 檢查K3s是否已安裝
    if command -v k3s &> /dev/null; then
        print_success "K3s已安裝: $(k3s --version | head -n1)"
    else
        print_error "K3s未安裝"
        read -p "是否要安裝K3s？(y/n): " install_k3s
        if [[ "$install_k3s" == "y" ]]; then
            print_info "安裝K3s..."
            curl -sfL https://get.k3s.io | sh -
        else
            exit 1
        fi
    fi
    
    # 檢查K3s服務狀態
    if systemctl is-active --quiet k3s; then
        print_success "K3s服務運行中"
    else
        print_warning "K3s服務未運行，正在啟動..."
        sudo systemctl start k3s
        sleep 5
    fi
    
    # 設置kubectl權限
    if [[ -f /etc/rancher/k3s/k3s.yaml ]]; then
        sudo chmod 644 /etc/rancher/k3s/k3s.yaml
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
        print_success "kubectl權限已設置"
    else
        print_error "K3s配置文件不存在"
        exit 1
    fi
    
    # 驗證集群狀態
    if kubectl get nodes &> /dev/null; then
        print_success "k3s集群連接正常"
        kubectl get nodes
    else
        print_error "無法連接到k3s集群"
        exit 1
    fi
    
    echo ""
}

# 創建Secret
create_secrets() {
    print_header "創建Secrets"
    
    # PostgreSQL Secret
    if kubectl get secret postgres-secret &> /dev/null; then
        print_warning "postgres-secret已存在，跳過創建"
    else
        if [[ -f "$k3s_DIR/postgres/postgres-secret.yaml" ]]; then
            print_info "使用現有的postgres-secret.yaml"
            kubectl apply -f "$k3s_DIR/postgres/postgres-secret.yaml"
        else
            print_info "創建postgres-secret..."
            read -p "PostgreSQL數據庫名稱 [blog_db]: " db_name
            db_name=${db_name:-blog_db}
            
            read -p "PostgreSQL用戶名 [blog_user]: " db_user
            db_user=${db_user:-blog_user}
            
            read -s -p "PostgreSQL密碼: " db_password
            echo ""
            
            kubectl create secret generic postgres-secret \
                --from-literal=POSTGRES_DB="$db_name" \
                --from-literal=POSTGRES_USER="$db_user" \
                --from-literal=POSTGRES_PASSWORD="$db_password"
            
            print_success "postgres-secret創建成功"
        fi
    fi
    
    echo ""
}

# 部署服務
deploy_services() {
    print_header "部署服務"
    
    local services=("postgres" "redis")
    local total=${#services[@]}
    local current=0
    
    for service in "${services[@]}"; do
        current=$((current + 1))
        show_progress $current $total
        
        if kubectl get deployment $service &> /dev/null; then
            print_warning "\n$service已部署，跳過"
        else
            print_info "\n部署$service..."
            
            # 部署服務
            case $service in
                postgres)
                    kubectl apply -f "$k3s_DIR/postgres/postgres-pvc.yaml"
                    kubectl apply -f "$k3s_DIR/postgres/postgres-deployment.yaml"
                    kubectl apply -f "$k3s_DIR/postgres/postgres-service.yaml"
                    ;;
                redis)
                    kubectl apply -f "$k3s_DIR/redis/redis-deployment.yaml"
                    kubectl apply -f "$k3s_DIR/redis/redis-service.yaml"
                    ;;
            esac
        fi
    done
    
    echo -e "\n"
}

# 等待服務就緒
wait_for_services() {
    print_header "等待服務就緒"
    
    local services=("postgres" "redis")
    
    for service in "${services[@]}"; do
        print_info "等待$service就緒..."
        
        local retries=0
        local max_retries=30
        
        while [[ $retries -lt $max_retries ]]; do
            if kubectl get pods -l app=$service 2>/dev/null | grep -q "1/1.*Running"; then
                print_success "$service已就緒"
                break
            fi
            
            retries=$((retries + 1))
            echo -ne "\r等待中... ($retries/$max_retries)"
            sleep 2
        done
        
        if [[ $retries -eq $max_retries ]]; then
            print_error "$service啟動超時"
            kubectl get pods -l app=$service
        fi
    done
    
    echo ""
}

# 設置腳本權限
setup_scripts() {
    print_header "設置腳本權限"
    
    if [[ -d "$SCRIPTS_DIR" ]]; then
        chmod +x "$SCRIPTS_DIR"/*.sh
        print_success "腳本權限已設置"
        
        # 添加到PATH提示
        echo ""
        print_info "建議將腳本目錄添加到PATH:"
        echo "  echo 'export PATH=\"$SCRIPTS_DIR:\$PATH\"' >> ~/.bashrc"
        echo "  source ~/.bashrc"
    else
        print_warning "腳本目錄不存在"
    fi
    
    echo ""
}

# 顯示環境信息
show_environment_info() {
    print_header "環境信息"
    
    # 執行get-services-info.sh
    if [[ -x "$SCRIPTS_DIR/get-services-info.sh" ]]; then
        "$SCRIPTS_DIR/get-services-info.sh"
    else
        print_warning "服務信息腳本不可用"
    fi
}

# 創建別名
create_aliases() {
    print_header "創建便捷別名"
    
    local alias_file="$PROJECT_ROOT/.k3s_aliases"
    
    cat > "$alias_file" << 'EOF'
# k3s便捷別名
alias k="kubectl"
alias kgp="kubectl get pods"
alias kgs="kubectl get services"
alias kgn="kubectl get nodes"
alias kdp="kubectl describe pod"
alias klog="kubectl logs"
alias kexec="kubectl exec -it"

# 項目特定別名
alias k3s-start="$SCRIPTS_DIR/k3s-manager.sh start all"
alias k3s-stop="$SCRIPTS_DIR/k3s-manager.sh stop all"
alias k3s-status="$SCRIPTS_DIR/k3s-manager.sh status all"
alias k3s-info="$SCRIPTS_DIR/get-services-info.sh"
alias k3s-logs="$SCRIPTS_DIR/k3s-logs.sh"
EOF
    
    print_success "別名文件已創建: $alias_file"
    print_info "要使用別名，請執行:"
    echo "  source $alias_file"
    echo "  或將其添加到~/.bashrc"
    
    echo ""
}

# 主函數
main() {
    print_header "k3s開發環境初始化"
    echo "項目路徑: $PROJECT_ROOT"
    echo "開始時間: $(date '+%Y-%m-%d %H:%M:%S')"
    echo ""
    
    # 執行設置步驟
    check_requirements
    setup_k3s
    create_secrets
    deploy_services
    wait_for_services
    setup_scripts
    create_aliases
    
    # 顯示完成信息
    print_header "初始化完成"
    print_success "k3s開發環境已準備就緒！"
    echo ""
    
    # 顯示環境信息
    show_environment_info
    
    # 快速開始指南
    print_header "快速開始"
    echo "1. 查看服務狀態:"
    echo "   $SCRIPTS_DIR/k3s-manager.sh status all"
    echo ""
    echo "2. 查看服務連接信息:"
    echo "   $SCRIPTS_DIR/get-services-info.sh"
    echo ""
    echo "3. 查看服務日誌:"
    echo "   $SCRIPTS_DIR/k3s-logs.sh"
    echo ""
    echo "4. 連接PostgreSQL:"
    echo "   kubectl exec -it \$(kubectl get pods -l app=postgres -o jsonpath='{.items[0].metadata.name}') -- psql -U \$USER"
    echo ""
    
    print_success "祝您開發愉快！"
}

# 執行主函數
main "$@"