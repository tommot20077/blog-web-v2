#!/bin/bash

#=============================================================================
# 名稱: k3s-manager.sh
# 描述: k3s服務管理主腳本
# 作者: Yuan
# 版本: 2.3.0
#=============================================================================

# 顏色定義
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

# 部署服務
deploy_service() {
    local env=$1
    local service=$2
    local service_dir="$k3s_DIR/$env/$service"
    
    if [[ ! -d "$service_dir" ]]; then
        print_error "服務目錄不存在: $service_dir"
        return 1
    fi
    
    print_info "在環境 [$env] 中部署服務 [$service]..."
    kubectl apply -f "$service_dir"
    print_success "服務 [$service] 在環境 [$env] 中部署完成"
}

# 停止服務
delete_service() {
    local env=$1
    local service=$2
    local service_dir="$k3s_DIR/$env/$service"
    
    if [[ ! -d "$service_dir" ]]; then
        print_error "服務目錄不存在: $service_dir"
        return 1
    fi
    
    print_warning "在環境 [$env] 中停止服務 [$service]..."
    kubectl delete -f "$service_dir/" --ignore-not-found=true
    print_success "服務 [$service] 在環境 [$env] 中已停止"
}

# 完全刪除服務（包括數據）
down_service() {
    local env=$1
    local service=$2
    local force=$3
    local service_dir="$k3s_DIR/$env/$service"
    
    if [[ ! -d "$service_dir" ]]; then
        print_error "服務目錄不存在: $service_dir"
        return 1
    fi

    if [[ "$force" != "true" ]]; then
        print_warning "您確定要完全刪除環境 [$env] 中的服務 [$service] (包括所有數據) 嗎?"
        print_warning "這個操作無法復原！"
        read -p "請輸入 'y' 或 'Y' 確認: " confirmation
        
        if ! [[ "$confirmation" == "y" || "$confirmation" == "Y" ]]; then
            print_info "操作已取消"
            return 1
        fi
    fi
    
    print_warning "完全刪除環境 [$env] 中的服務 [$service]..."
    kubectl delete -f "$service_dir" --ignore-not-found=true
    print_success "服務 [$service] 在環境 [$env] 中已完全刪除"
}

# 檢查服務狀態
check_service_status() {
    local env=$1
    local service=$2
    
    print_info "在環境 [$env] 中檢查服務 [$service] 狀態..."
    
    echo -e "\n${YELLOW}Pods:${NC}"
    kubectl get pods -l app=$service
    
    echo -e "\n${YELLOW}Services:${NC}"
    kubectl get service -l app=$service 2>/dev/null || kubectl get service | grep $service
    
    if [[ "$service" == "postgres" || "$service" == "elasticsearch" ]]; then
        echo -e "\n${YELLOW}Storage:${NC}"
        kubectl get pvc | grep $service
    fi
}

# 查看服務日誌
view_service_logs() {
    local env=$1
    local service=$2
    local follow=${3:-false}
    
    local pod=$(kubectl get pods -l app=$service -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)
    
    if [[ -z "$pod" ]]; then
        print_error "在環境 [$env] 中找不到服務 [$service] 的Pod"
        return 1
    fi
    
    print_info "查看環境 [$env] 中服務 [$service] 的日誌 (Pod: $pod)..."
    
    if [[ "$follow" == "true" ]]; then
        kubectl logs -f "$pod"
    else
        kubectl logs "$pod" --tail=50
    fi
}

# 重啟服務
restart_service() {
    local env=$1
    local service=$2
    
    print_warning "在環境 [$env] 中重啟服務 [$service]..."
    kubectl delete pod -l app=$service
    print_info "等待服務重新啟動..."
    sleep 5
    kubectl wait --for=condition=ready pod -l app=$service --timeout=60s
    print_success "服務 [$service] 在環境 [$env] 中重啟完成"
}

# 顯示使用幫助
show_usage() {
    print_header "k3s服務管理器"
    echo "用法: $0 [命令] [環境] [服務名稱|all] [選項]"
    echo ""
    echo "命令:"
    echo "  start, stop, down, restart, status, logs, list"
    echo "選項:"
    echo "  -A  用於 'down' 命令，強制刪除無需確認"
    echo "  -f  用於 'logs' 命令，實時跟隨日誌"
    echo ""
    echo "示例:"
    echo "  $0 start demo all"
    echo "  $0 down product postgres -A"
    echo "  $0 list"
    echo "  $0 start postgres  (如果預設環境存在)"
}

# 列出所有環境和服務
list_all() {
    print_header "可用的環境和服務"
    local max_len=0
    
    # 儲存每個服務的名稱和帶顏色的狀態
    declare -A service_details_map
    local environments_list=($(get_environments))

    for env in "${environments_list[@]}"; do
        local services_in_env=($(get_services "$env"))
        for service in "${services_in_env[@]}"; do
            local status_text=""
            local pods_json=$(kubectl get pods -l app=$service -o json -n "$env" 2>/dev/null)

            if [[ -z "$(echo "$pods_json" | jq -r '.items')" ]]; then
                status_text="${RED}未部署${NC}"
            else
                local running_pods=0
                local pending_pods=0
                local error_pods=0
                local total_pods=$(echo "$pods_json" | jq -r '.items | length')

                if [[ "$total_pods" -eq 0 ]]; then
                    status_text="${RED}未部署${NC}"
                else
                    for i in $(seq 0 $((total_pods - 1))); do
                        local pod_name=$(echo "$pods_json" | jq -r ".items[${i}].metadata.name")
                        local pod_phase=$(echo "$pods_json" | jq -r ".items[${i}].status.phase")
                        local container_statuses=$(echo "$pods_json" | jq -r ".items[${i}].status.containerStatuses")

                        if [[ "$pod_phase" == "Failed" ]]; then
                            error_pods=$((error_pods + 1))
                            continue
                        fi

                        local container_error=false
                        if [[ -n "$container_statuses" && "$container_statuses" != "null" ]]; then
                            # 檢查每個容器的狀態
                            local num_containers=$(echo "$container_statuses" | jq -r 'length')
                            for c_idx in $(seq 0 $((num_containers - 1))); do
                                local c_state=$(echo "$container_statuses" | jq -r ".items[${c_idx}].state")
                                local c_ready=$(echo "$container_statuses" | jq -r ".items[${c_idx}].ready")

                                if [[ "$c_state" =~ "waiting" ]]; then
                                    local reason=$(echo "$c_state" | jq -r ".waiting.reason")
                                    if [[ "$reason" == "CrashLoopBackOff" || "$reason" == "ImagePullBackOff" ]]; then
                                        container_error=true
                                        break
                                    fi
                                fi
                                if [[ "$c_state" =~ "terminated" ]]; then
                                    local reason=$(echo "$c_state" | jq -r ".terminated.reason")
                                    if [[ "$reason" == "Error" ]]; then
                                        container_error=true
                                        break
                                    fi
                                fi
                                if [[ "$c_ready" == "false" && "$pod_phase" == "Running" ]]; then
                                    # Running pod with not ready container often indicates an issue
                                    container_error=true
                                    break
                                fi
                            done
                        fi

                        if $container_error; then
                            error_pods=$((error_pods + 1))
                        elif [[ "$pod_phase" == "Pending" ]]; then
                            pending_pods=$((pending_pods + 1))
                        elif [[ "$pod_phase" == "Running" ]]; then
                            running_pods=$((running_pods + 1))
                        fi
                    done # end of pod loop

                    if [[ "$error_pods" -gt 0 ]]; then
                        status_text="${RED}容器錯誤${NC}"
                    elif [[ "$running_pods" -eq "$total_pods" && "$total_pods" -gt 0 ]]; then
                        status_text="${GREEN}運行中${NC}"
                    elif [[ "$pending_pods" -gt 0 ]]; then
                        status_text="${YELLOW}啟動中${NC}"
                    else
                        status_text="${NC}狀態未知${NC}"
                    fi
                fi # end of total_pods > 0
            fi # end of pods_json check

            service_details_map["$env-$service"]="$service:$status_text"
            if [[ ${#service} -gt $max_len ]]; then
                max_len=${#service}
            fi
        done # end of service loop
    done # end of env loop

    # 輸出對齊的狀態
    for env in "${environments_list[@]}"; do
        echo -e "${BLUE}環境: $env${NC}"
        local services_in_env=($(get_services "$env"))
        for service in "${services_in_env[@]}"; do
            local details="${service_details_map["$env-$service"]}"
            local svc_name=$(echo "$details" | cut -d':' -f1)
            local svc_status=$(echo "$details" | cut -d':' -f2)
            printf "  - %-${max_len}s %b\n" "$svc_name" "$svc_status"
        done
    done
}


# 加載 .env 文件
load_env() {
    if [ -f "$PROJECT_ROOT/.env" ]; then
        export $(grep -v '^#' "$PROJECT_ROOT/.env" | xargs)
    fi
}

# 主函數
main() {
    load_env
    check_k3s

    local command=""
    local env=""
    local service=""
    local options=()

    # 解析參數
    for arg in "$@"; do
        case "$arg" in
            start|stop|down|restart|status|logs|list)
                [[ -z "$command" ]] && command="$arg"
                ;; 
            -*) 
                options+=("$arg")
                ;; 
            *)
                if [[ -z "$env" ]] && [[ -d "$k3s_DIR/$arg" ]]; then
                    env="$arg"
                elif [[ -z "$service" ]]; then
                    service="$arg"
                fi
                ;; 
        esac
done

    # 處理 'list' 或無命令的情況
    if [[ "$command" == "list" || -z "$command" ]]; then
        [[ "$command" == "list" ]] && list_all || show_usage
        return
    fi
    
    # 處理環境未指定的情況
    if [[ -z "$env" ]]; then
        local environments=($(get_environments))
        if [ ${#environments[@]} -eq 1 ]; then
            env=${environments[0]}
            print_info "自動選擇唯一的環境: $env"
        elif [ -n "$DEFAULT_ENV" ]; then
            env=$DEFAULT_ENV
            print_info "使用預設環境: $env"
        else
            print_warning "請選擇一個環境:"
            select choice in "${environments[@]}"; do
                if [[ -n "$choice" ]]; then
                    env=$choice
                    break
                else
                    print_error "無效的選擇"
                fi
            done
            [[ -z "$env" ]] && { print_error "未選擇環境，操作取消。"; exit 1; }
        fi
    fi

    local command_func=""
    case "$command" in
        start) command_func="deploy_service" ;;
        stop) command_func="delete_service" ;;
        restart) command_func="restart_service" ;;
        status) command_func="check_service_status" ;;
    esac

    if [[ -n "$command_func" ]]; then
        [[ -z "$service" ]] && { print_error "請指定服務或 'all'"; show_usage; exit 1; }
        local services_to_process=${service:-"all"}
        if [[ "$services_to_process" == "all" ]]; then
            services_to_process=$(get_services "$env")
        fi
        for svc in $services_to_process; do
            $command_func "$env" "$svc"
        done
    elif [[ "$command" == "down" ]]; then
        [[ -z "$service" ]] && { print_error "請指定服務或 'all'"; show_usage; exit 1; }
        local force=false
        for opt in "${options[@]}"; do [[ "$opt" == "-A" ]] && force=true; done
        local services_to_process=${service:-"all"}
        if [[ "$services_to_process" == "all" ]]; then
            services_to_process=$(get_services "$env")
        fi
        for svc in $services_to_process; do
            down_service "$env" "$svc" "$force"
        done
    elif [[ "$command" == "logs" ]]; then
        [[ -z "$service" ]] && { print_error "請指定服務"; show_usage; exit 1; }
        local follow=false
        for opt in "${options[@]}"; do [[ "$opt" == "-f" ]] && follow=true; done
        view_service_logs "$env" "$service" "$follow"
    else
        print_error "未知命令: $command"
        show_usage
    fi
}

# 執行主函數
main "$@"
