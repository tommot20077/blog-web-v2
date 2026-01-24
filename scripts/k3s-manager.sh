#!/bin/bash

#=============================================================================
# 名稱: k3s-dev-setup.sh
# 描述: k3s 開發環境初始化腳本 (支援模式選擇)
# 版本: 1.1.0
#=============================================================================

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' 

# 配置
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
k3s_DIR="$PROJECT_ROOT/k3s"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"
KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}

# 模式選擇變數
SETUP_MODE="1" # 默認 1

# 輸出函數
print_header() {
    echo -e "${BLUE}==============================================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}==============================================================================${NC}"
}
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error() { echo -e "${RED}✗ $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠ $1${NC}"; }
print_info() { echo -e "${BLUE}ℹ $1${NC}"; }

# 模式選擇界面
select_mode() {
    print_header "模式選擇"
    echo "請選擇 K3s 安裝模式："
    echo -e "1) ${GREEN}標準模式 (Standard)${NC} - 原生 K3s, 包含 Traefik 負載均衡 (默認)"
    echo -e "2) ${YELLOW}Nginx 共存模式 (Expert)${NC} - 停用 Traefik/LB, 使用 Docker 運行, 適合用 Nginx 管理"
    echo ""
    read -p "請輸入選擇 [1/2，默認 1]: " mode_input
    SETUP_MODE=${mode_input:-1}
    
    if [[ "$SETUP_MODE" == "2" ]]; then
        print_info "已選擇：Nginx 共存模式"
    else
        print_info "已選擇：標準模式"
    fi
}

# 檢查系統要求
check_requirements() {
    print_header "檢查系統要求"
    local requirements_met=true
    
    if grep -q Microsoft /proc/version; then print_success "運行在WSL2環境"; fi
    
    if command -v docker &> /dev/null; then
        print_success "Docker已安裝"
    else
        if [[ "$SETUP_MODE" == "2" ]]; then
            print_error "共存模式需要預先安裝 Docker"
            requirements_met=false
        else
            print_warning "Docker未安裝 (標準模式將使用 containerd)"
        fi
    fi
    
    [[ "$requirements_met" == "false" ]] && exit 1
}

# 設置和啟動 K3s
setup_k3s() {
    print_header "設置 K3s"
    
    if command -v k3s &> /dev/null; then
        print_success "K3s 已安裝"
    else
        if [[ "$SETUP_MODE" == "2" ]]; then
            print_info "正在以 [共存模式] 安裝 K3s..."
            # 禁用 Traefik 讓出 80/443, 禁用 ServiceLB, 使用 Docker 作為 Runtime
            curl -sfL https://get.k3s.io | sh -s - \
                --docker \
                --disable traefik \
                --disable servicelb \
                --write-kubeconfig-mode 644
        else
            print_info "正在以 [標準模式] 安裝 K3s..."
            curl -sfL https://get.k3s.io | sh -
        fi
    fi
    
    if systemctl is-active --quiet k3s; then
        print_success "K3s 服務運行中"
    else
        sudo systemctl start k3s
        sleep 5
    fi
    
    sudo chmod 644 /etc/rancher/k3s/k3s.yaml
    export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
}

# 創建 Secret (這部分維持原樣)
create_secrets() {
    print_header "創建 Secrets"
    if kubectl get secret postgres-secret &> /dev/null; then
        print_warning "postgres-secret 已存在，跳過"
    else
        read -p "PostgreSQL 數據庫名稱 [blog_db]: " db_name; db_name=${db_name:-blog_db}
        read -p "PostgreSQL 用戶名 [blog_user]: " db_user; db_user=${db_user:-blog_user}
        read -s -p "PostgreSQL 密碼: " db_password; echo ""
        
        kubectl create secret generic postgres-secret \
            --from-literal=POSTGRES_DB="$db_name" \
            --from-literal=POSTGRES_USER="$db_user" \
            --from-literal=POSTGRES_PASSWORD="$db_password"
        print_success "postgres-secret 創建成功"
    fi
}

# 部署服務
deploy_services() {
    print_header "部署服務"
    for service in postgres redis; do
        if [[ -d "$k3s_DIR/$service" ]]; then
            print_info "正在部署 $service..."
            kubectl apply -f "$k3s_DIR/$service/"
        fi
    done
}

# 等待服務就緒
wait_for_services() {
    print_header "等待服務就緒"
    for service in postgres redis; do
        print_info "等待 $service..."
        kubectl wait --for=condition=ready pod -l app=$service --timeout=60s 2>/dev/null
        if [[ $? -eq 0 ]]; then print_success "$service 已就緒"; else print_warning "$service 啟動較慢，請稍後查看"; fi
    done
}

# 模式專屬建議
show_mode_tips() {
    if [[ "$SETUP_MODE" == "2" ]]; then
        print_header "Nginx & UFW 配置提示"
        print_info "由於您選擇了共存模式，請記得："
        echo "1. 在宿主機安裝 Nginx: sudo apt install nginx"
        echo "2. 修改 /etc/nginx/nginx.conf 加入 stream 區塊轉發 5432 端口"
        echo "3. UFW 設定："
        echo "   sudo ufw allow 80,443/tcp"
        echo "   sudo ufw allow 5432/tcp"
    else
        print_header "標準模式提示"
        echo "K3s Traefik 已啟動。若要從外部連線，請確保 UFW 開放了 NodePort 範圍 (30000-32767)。"
    fi
}

# 主函數
main() {
    print_header "k3s 開發環境初始化"
    select_mode
    check_requirements
    setup_k3s
    create_secrets
    deploy_services
    wait_for_services
    show_mode_tips
    print_success "所有流程執行完畢！"
}

main "$@"