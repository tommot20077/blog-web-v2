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

# 多選函數（支援序號選擇和多選）
multi_select() {
    local prompt=$1
    shift
    local options=("$@")
    local selected=()

    echo -e "${YELLOW}${prompt}${NC}" >&2
    echo -e "${BLUE}提示: 輸入序號(可多選，用空格分隔)，或輸入 'all' 選擇全部${NC}" >&2

    for i in "${!options[@]}"; do
        echo "  $((i+1))) ${options[$i]}" >&2
    done

    while true; do
        read -p "請選擇 (例如: 1 3 5 或 all): " input

        if [[ -z "$input" ]]; then
            echo -e "${RED}✗ 請輸入選項${NC}" >&2
            continue
        fi

        if [[ "$input" == "all" ]]; then
            selected=("${options[@]}")
            break
        fi

        # 解析輸入的序號
        local valid=true
        local indices=($input)
        for idx in "${indices[@]}"; do
            if ! [[ "$idx" =~ ^[0-9]+$ ]] || [ "$idx" -lt 1 ] || [ "$idx" -gt "${#options[@]}" ]; then
                echo -e "${RED}✗ 無效的序號: $idx (有效範圍: 1-${#options[@]})${NC}" >&2
                valid=false
                break
            fi
        done

        if $valid; then
            for idx in "${indices[@]}"; do
                selected+=("${options[$((idx-1))]}")
            done
            break
        fi
    done

    # 返回選中的項目（用空格分隔）
    echo "${selected[@]}"
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

    # 確保 namespace 存在
    if ! kubectl get namespace "$env" &>/dev/null; then
        print_info "創建命名空間: $env"
        kubectl create namespace "$env"
    fi

    print_info "在環境 [$env] 中部署服務 [$service]..."
    
    local success=true
    # 遍歷目錄下的所有 yaml 檔案
    find "$service_dir" -maxdepth 1 \( -name "*.yaml" -o -name "*.yml" \) | while read -r yaml_file; do
        if ! cat "$yaml_file" | \
             sed "s/namespace: *default/namespace: $env/g" | \
             sed "s/NAMESPACE_PLACEHOLDER/$env/g" | \
             kubectl apply -n "$env" -f -; then
            success=false
        fi
    done

    if $success; then
        print_success "服務 [$service] 在環境 [$env] 中部署完成"
    else
        print_error "服務 [$service] 在環境 [$env] 中部署部分失敗，請檢查上方錯誤訊息"
        return 1
    fi
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
    find "$service_dir" -maxdepth 1 \( -name "*.yaml" -o -name "*.yml" \) | while read -r yaml_file; do
        cat "$yaml_file" | \
            sed "s/namespace: *default/namespace: $env/g" | \
            sed "s/NAMESPACE_PLACEHOLDER/$env/g" | \
            kubectl delete -n "$env" --ignore-not-found=true -f -
    done
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
    find "$service_dir" -maxdepth 1 \( -name "*.yaml" -o -name "*.yml" \) | while read -r yaml_file; do
        cat "$yaml_file" | \
            sed "s/namespace: *default/namespace: $env/g" | \
            sed "s/NAMESPACE_PLACEHOLDER/$env/g" | \
            kubectl delete -n "$env" --ignore-not-found=true -f -
    done
    print_success "服務 [$service] 在環境 [$env] 中已完全刪除"
}

# 檢查服務狀態
check_service_status() {
    local env=$1
    local service=$2

    print_info "在環境 [$env] 中檢查服務 [$service] 狀態..."

    echo -e "\n${YELLOW}Pods:${NC}"
    kubectl get pods -n "$env" -l app=$service

    echo -e "\n${YELLOW}Services:${NC}"
    kubectl get service -n "$env" -l app=$service 2>/dev/null || kubectl get service -n "$env" | grep $service

    if [[ "$service" == "postgres" || "$service" == "elasticsearch" ]]; then
        echo -e "\n${YELLOW}Storage:${NC}"
        kubectl get pvc -n "$env" | grep $service
    fi
}

# 查看服務日誌
view_service_logs() {
    local env=$1
    local service=$2
    local follow=${3:-false}

    local pod=$(kubectl get pods -n "$env" -l app=$service -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)

    if [[ -z "$pod" ]]; then
        print_error "在環境 [$env] 中找不到服務 [$service] 的Pod"
        return 1
    fi

    print_info "查看環境 [$env] 中服務 [$service] 的日誌 (Pod: $pod)..."

    if [[ "$follow" == "true" ]]; then
        kubectl logs -n "$env" -f "$pod"
    else
        kubectl logs -n "$env" "$pod" --tail=50
    fi
}

# 重啟服務
restart_service() {
    local env=$1
    local service=$2

    print_warning "在環境 [$env] 中重啟服務 [$service]..."
    kubectl delete pod -n "$env" -l app=$service
    print_info "等待服務重新啟動..."
    sleep 5
    kubectl wait --for=condition=ready pod -n "$env" -l app=$service --timeout=60s
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
    echo "交互式選擇:"
    echo "  - 環境選擇: 輸入序號 (例如: 1)"
    echo "  - 服務選擇: 支持多選，輸入序號用空格分隔 (例如: 1 3 5) 或輸入 'all'"
    echo ""
    echo "示例:"
    echo "  $0 start demo all                    # 啟動 demo 環境的所有服務"
    echo "  $0 down product postgres -A          # 強制刪除 product 環境的 postgres"
    echo "  $0 down -A                           # 交互式選擇環境和服務（支持多選）"
    echo "  $0 list                              # 列出所有環境和服務狀態"
}

# 列出所有環境和服務
list_all() {
    print_header "可用的環境和服務"
    local max_len=20

    # 儲存每個服務的名稱和帶顏色的狀態
    declare -A service_details_map
    local environments_list=($(get_environments))

    for env in "${environments_list[@]}"; do
        local services_in_env=$(get_services "$env")
        for service in $services_in_env; do
            local status_text="${RED}未部署${NC}"
            
            # 獲取 Pods JSON (優先使用 app 標籤，其次使用 service 標籤用於群組服務)
            local pods_json=$(kubectl get pods -n "$env" -l "app=$service" -o json 2>/dev/null)
            local count=$(echo "$pods_json" | jq -r '(.items // []) | length' 2>/dev/null || echo "0")
            
            if [[ "$count" -eq 0 ]]; then
                pods_json=$(kubectl get pods -n "$env" -l "service=$service" -o json 2>/dev/null)
                count=$(echo "$pods_json" | jq -r '(.items // []) | length' 2>/dev/null || echo "0")
            fi

            if [[ -n "$pods_json" && "$count" -gt 0 ]]; then
                local states=$(echo "$pods_json" | jq -r '
                    (.items // []) | {
                        running: [ .[] | select(.status.phase == "Running") ] | length,
                        pending: [ .[] | select(.status.phase == "Pending") ] | length,
                        failed:  [ .[] | select(.status.phase == "Failed") ] | length,
                        succeeded: [ .[] | select(.status.phase == "Succeeded") ] | length,
                        error:   [ .[] | select(.status.containerStatuses[]? | .ready == false and .state.waiting.reason != null) ] | length
                    } | "\(.running) \(.pending) \(.failed) \(.succeeded) \(.error)"
                ' 2>/dev/null)

                read running_pods pending_pods failed_pods succeeded_pods error_pods <<< "$states"

                if [[ "$failed_pods" -gt 0 || "$error_pods" -gt 0 ]]; then
                    status_text="${RED}容器錯誤${NC}"
                elif [[ "$running_pods" -gt 0 ]]; then
                    status_text="${GREEN}運行中${NC}"
                elif [[ "$pending_pods" -gt 0 ]]; then
                    status_text="${YELLOW}啟動中${NC}"
                elif [[ "$succeeded_pods" -eq "$count" ]]; then
                    status_text="${BLUE}已完成${NC}"
                else
                    status_text="${NC}狀態未知${NC}"
                fi
            fi

            service_details_map["$env-$service"]="$service:$status_text"
            [[ ${#service} -gt $max_len ]] && max_len=${#service}
        done
    done

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
            print_warning "請選擇一個環境 (輸入序號):"
            select choice in "${environments[@]}"; do
                if [[ -n "$choice" ]]; then
                    env=$choice
                    break
                else
                    print_error "無效的選擇，請輸入序號 (1-${#environments[@]})"
                fi
            done
            [[ -z "$env" ]] && { print_error "未選擇環境，操作取消。"; exit 1; }
        fi
    fi

    # 處理服務未指定的情況（使用多選）
    if [[ -z "$service" ]]; then
        local available_services=($(get_services "$env"))
        if [[ "$command" == "logs" ]]; then
            # logs 命令只能選擇單一服務
            print_warning "請選擇要查看日誌的服務 (輸入序號):"
            select choice in "${available_services[@]}"; do
                if [[ -n "$choice" ]]; then
                    service=$choice
                    break
                else
                    print_error "無效的選擇，請輸入序號 (1-${#available_services[@]})"
                fi
            done
            [[ -z "$service" ]] && { print_error "未選擇服務，操作取消。"; exit 1; }
        else
            # 其他命令支持多選
            local selected_services=($(multi_select "請選擇要操作的服務:" "${available_services[@]}"))
            if [[ ${#selected_services[@]} -eq ${#available_services[@]} ]]; then
                service="all"
            else
                service="${selected_services[*]}"
            fi
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
        local services_to_process=""
        if [[ "$service" == "all" ]]; then
            services_to_process=$(get_services "$env")
        else
            services_to_process="$service"
        fi
        for svc in $services_to_process; do
            $command_func "$env" "$svc"
        done
    elif [[ "$command" == "down" ]]; then
        local force=false
        for opt in "${options[@]}"; do [[ "$opt" == "-A" ]] && force=true; done
        local services_to_process=""
        if [[ "$service" == "all" ]]; then
            services_to_process=$(get_services "$env")
        else
            services_to_process="$service"
        fi
        for svc in $services_to_process; do
            down_service "$env" "$svc" "$force"
        done
    elif [[ "$command" == "logs" ]]; then
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
