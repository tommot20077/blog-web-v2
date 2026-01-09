#!/bin/bash

#=============================================================================
# 名稱: k3s-logs.sh
# 描述: k3s服務日誌查看工具
# 作者: Yuan
# 版本: 2.0.0
#=============================================================================

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# 配置
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../" && pwd)"
k3s_DIR="$PROJECT_ROOT/k3s"
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

# 檢查K3s狀態
check_k3s() {
    if ! systemctl is-active --quiet k3s; then
        print_error "K3s 服務未運行。"
        print_info "請手動啟動 K3s: sudo systemctl start k3s"
        exit 1
    fi
    
    if ! kubectl get nodes &> /dev/null; then
        print_error "kubectl 無法連接到 K3s 集群或權限不足。"
        print_info "請檢查您的 KUBECONFIG 環境變數，例如: export KUBECONFIG=/etc/rancher/k3s/k3s.yaml"
        print_info "您可能需要修復 kubectl 權限: sudo chmod 644 /etc/rancher/k3s/k3s.yaml"
        exit 1
    fi
    
    print_success "K3s 和 kubectl 運行正常"
}

# 獲取環境列表
get_environments() {
    find "$k3s_DIR" -mindepth 1 -maxdepth 1 -type d -exec basename {} \;
}

# 獲取指定環境下的服務列表
get_services() {
    local env=$1
    find "$k3s_DIR/$env" -mindepth 1 -maxdepth 1 -type d -exec basename {} \;
}

# 加載 .env 文件
load_env() {
    if [ -f "$PROJECT_ROOT/.env" ]; then
        export $(grep -v '^#' "$PROJECT_ROOT/.env" | xargs)
    fi
}

# 獲取Pod列表
get_pods() {
    local env=$1
    local service=$2

    if [[ -z "$env" ]]; then
        kubectl get pods -o jsonpath='{.items[*].metadata.name}' 2>/dev/null
        return
    fi
    
    if [[ -z "$service" || "$service" == "all" ]]; then
        local all_services=$(get_services "$env")
        local labels=""
        for s in $all_services;
        do
            if [[ -z "$labels" ]]; then
                labels="app=$s"
            else
                labels="$labels,app=$s"
            fi
        done
        kubectl get pods -l "$labels" -o jsonpath='{.items[*].metadata.name}' 2>/dev/null
    else
        kubectl get pods -l app=$service -o jsonpath='{.items[*].metadata.name}' 2>/dev/null
    fi
}

# 顯示Pod選擇菜單
select_pod() {
    local env=$1
    local service=$2
    
    local pods=($(get_pods "$env" "$service"))
    
    if [[ ${#pods[@]} -eq 0 ]]; then
        print_error "在環境 [$env] 中沒有找到任何Pod"
        return 1
    fi
    
    if [[ ${#pods[@]} -eq 1 ]]; then
        echo "${pods[0]}"
        return 0
    fi
    
    echo -e "${CYAN}請選擇Pod:${NC}"
    for i in "${!pods[@]}"; do
        local status=$(kubectl get pod "${pods[$i]}" -o jsonpath='{.status.phase}')
        local color=$GREEN
        if [[ "$status" != "Running" ]]; then
            color=$YELLOW
        fi
        echo -e "  ${color}$((i+1))) ${pods[$i]} [$status]${NC}"
    done
    
    read -p "輸入數字 (1-${#pods[@]}): " choice
    
    if [[ "$choice" -ge 1 && "$choice" -le ${#pods[@]} ]]; then
        echo "${pods[$((choice-1))]}"
    else
        print_error "無效的選擇"
        return 1
    fi
}

# 查看日誌
view_logs() {
    local pod=$1
    local follow=$2
    local tail=$3
    local previous=$4
    local container=$5
    
    local cmd="kubectl logs $pod"
    
    if [[ -n "$container" ]]; then
        cmd="$cmd -c $container"
    fi
    if [[ "$follow" == "true" ]]; then
        cmd="$cmd -f"
    fi
    if [[ -n "$tail" ]]; then
        cmd="$cmd --tail=$tail"
    else
        cmd="$cmd --tail=100"
    fi
    if [[ "$previous" == "true" ]]; then
        cmd="$cmd --previous"
    fi
    
    eval $cmd
}

# 獲取所有容器
get_containers() {
    local pod=$1
    kubectl get pod $pod -o jsonpath='{.spec.containers[*].name}' 2>/dev/null
}

# 顯示使用幫助
show_usage() {
    print_header "k3s日誌查看工具"
    echo "用法: $0 [選項] [環境] [服務名稱|all]"
    echo ""
    echo "選項:"
    echo "  -f, --follow          實時跟隨日誌輸出"
    echo "  -t, --tail <行數>     顯示最後N行日誌 (默認: 100)"
    echo "  -p, --previous        查看前一個容器實例的日誌"
    echo "  -c, --container       指定容器名稱"
    echo "  -h, --help            顯示此幫助信息"
    echo ""
    echo "示例:"
    echo "  $0 -f demo postgres   # 實時查看 demo 環境中 postgres 的日誌"
    echo "  $0 product all        # 選擇 product 環境中的一個Pod來查看日誌"
    echo "  $0                    # 交互式選擇一個Pod"
}

# 主函數
main() {
    load_env
    check_k3s
    
    local follow=false
    local tail=""
    local previous=false
    local container=""
    local env=""
    local service=""
    
    # 解析參數
    while [[ $# -gt 0 ]]; do
        case $1 in
            -f|--follow) follow=true; shift ;;
            -t|--tail) tail="$2"; shift 2 ;;
            -p|--previous) previous=true; shift ;;
            -c|--container) container="$2"; shift 2 ;;
            -h|--help) show_usage; exit 0 ;;
            -*) print_error "未知選項: $1"; show_usage; exit 1 ;;
            *)
                if [[ -z "$env" ]]; then
                    env="$1"
                elif [[ -z "$service" ]]; then
                    service="$1"
                fi
                shift
                ;; 
        esac
    done

    # 處理預設環境
    if [[ -z "$env" ]]; then
        local environments=($(get_environments))
        if [ ${#environments[@]} -eq 1 ]; then
            env=${environments[0]}
        elif [ -n "$DEFAULT_ENV" ]; then
            env=$DEFAULT_ENV
        fi
    fi

    # 選擇Pod
    print_header "k3s日誌查看"
    pod=$(select_pod "$env" "$service")
    
    if [[ -z "$pod" ]]; then
        exit 1
    fi
    
    print_info "已選擇Pod: $pod"
    
    # 選擇容器
    if [[ -z "$container" ]]; then
        containers=($(get_containers "$pod"))
        if [[ ${#containers[@]} -gt 1 ]]; then
            echo -e "\n${CYAN}Pod包含多個容器:${NC}"
            for i in "${!containers[@]}"; do echo "  $((i+1))) ${containers[$i]}"; done
            read -p "選擇容器 (1-${#containers[@]}) [默認: 1]: " choice
            choice=${choice:-1}
            if [[ "$choice" -ge 1 && "$choice" -le ${#containers[@]} ]]; then
                container="${containers[$((choice-1))]}"
            fi
        fi
    fi
    
    # 查看日誌
    echo ""
    if [[ "$follow" == "true" ]]; then
        print_info "實時查看日誌 (按 Ctrl+C 退出)..."
    else
        print_info "查看日誌..."
    fi
    echo -e "${BLUE}────────────────────────────────────────────────────────────────────────────${NC}"
    
    view_logs "$pod" "$follow" "$tail" "$previous" "$container"
}

# 執行主函數
main "$@"
