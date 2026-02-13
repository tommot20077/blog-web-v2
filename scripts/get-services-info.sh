#!/bin/bash

#=============================================================================
# 名稱: get-services-info.sh
# 描述: 顯示所有K8s服務的連接信息
# 作者: Yuan
# 版本: 1.0.0
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
KUBECONFIG=${KUBECONFIG:-/etc/rancher/k3s/k3s.yaml}

# 獲取WSL2 IP地址
get_wsl_ip() {
    hostname -I | awk '{print $1}'
}

# 輸出函數
print_header() {
    echo -e "${BLUE}==============================================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}==============================================================================${NC}"
}

print_service_info() {
    local service_name=$1
    local service_desc=$2
    echo -e "\n${CYAN}▶ $service_desc${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────────────────────────${NC}"
}

# 檢查服務是否運行
check_service_running() {
    local service=$1
    if kubectl get pods -l app=$service 2>/dev/null | grep -q Running; then
        echo -e "${GREEN}運行中${NC}"
    else
        echo -e "${RED}未運行${NC}"
        return 1
    fi
}

# 獲取服務端口信息
get_service_ports() {
    local service=$1
    kubectl get service | grep $service | awk '{print $5}' | cut -d'/' -f1
}

# 主函數
main() {
    # 檢查kubectl權限
    if ! kubectl get nodes &> /dev/null; then
        echo -e "${YELLOW}⚠ 修復kubectl權限...${NC}"
        sudo chmod 644 /etc/rancher/k3s/k3s.yaml
        export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
    fi
    
    # 獲取WSL2 IP
    WSL_IP=$(get_wsl_ip)
    
    print_header "K8s服務連接信息"
    echo -e "${YELLOW}WSL2 IP地址: ${WSL_IP}${NC}"
    echo -e "${YELLOW}日期時間: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
    
    # PostgreSQL信息
    print_service_info "postgres" "PostgreSQL數據庫"
    if check_service_running "postgres"; then
        # 獲取NodePort
        NODE_PORT=$(kubectl get service postgres-service -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
        
        # 從Secret獲取用戶信息
        DB_USER=$(kubectl get secret postgres-secret -o jsonpath='{.data.POSTGRES_USER}' 2>/dev/null | base64 -d)
        DB_NAME=$(kubectl get secret postgres-secret -o jsonpath='{.data.POSTGRES_DB}' 2>/dev/null | base64 -d)
        
        echo -e "  ${GREEN}主機:${NC} $WSL_IP 或 localhost"
        echo -e "  ${GREEN}端口:${NC} $NODE_PORT"
        echo -e "  ${GREEN}用戶:${NC} $DB_USER"
        echo -e "  ${GREEN}數據庫:${NC} $DB_NAME"
        echo -e "  ${GREEN}密碼:${NC} [查看postgres-secret]"
        echo ""
        echo -e "  ${PURPLE}連接字符串:${NC}"
        echo -e "  ${YELLOW}JDBC:${NC} jdbc:postgresql://$WSL_IP:$NODE_PORT/$DB_NAME"
        echo -e "  ${YELLOW}CLI:${NC}  psql -h $WSL_IP -p $NODE_PORT -U $DB_USER -d $DB_NAME"
        echo -e "  ${YELLOW}K8s內部:${NC} postgres-service:5432"
    fi
    
    # Redis信息
    print_service_info "redis" "Redis緩存"
    if check_service_running "redis"; then
        # 獲取NodePort
        NODE_PORT=$(kubectl get service redis-service -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
        
        echo -e "  ${GREEN}主機:${NC} $WSL_IP 或 localhost"
        echo -e "  ${GREEN}端口:${NC} $NODE_PORT"
        echo -e "  ${GREEN}密碼:${NC} 無"
        echo ""
        echo -e "  ${PURPLE}連接字符串:${NC}"
        echo -e "  ${YELLOW}CLI:${NC}  redis-cli -h $WSL_IP -p $NODE_PORT"
        echo -e "  ${YELLOW}Spring:${NC} redis://$WSL_IP:$NODE_PORT"
        echo -e "  ${YELLOW}K8s內部:${NC} redis-service:6379"
    fi
    
    # Spring Boot應用配置示例
    echo -e "\n${CYAN}▶ Spring Boot配置示例${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────────────────────────${NC}"
    cat << EOF
spring:
  datasource:
    url: jdbc:postgresql://postgres-service:5432/$DB_NAME
    username: $DB_USER
    password: \${POSTGRES_PASSWORD}
    
  data:
    redis:
      host: redis-service
      port: 6379
      
# 外部連接配置（開發環境）
# datasource.url: jdbc:postgresql://$WSL_IP:$(kubectl get service postgres-service -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)/$DB_NAME
# redis.host: $WSL_IP
# redis.port: $(kubectl get service redis-service -o jsonpath='{.spec.ports[0].nodePort}' 2>/dev/null)
EOF

    # 快捷命令
    echo -e "\n${CYAN}▶ 快捷命令${NC}"
    echo -e "${BLUE}────────────────────────────────────────────────────────────────────────────${NC}"
    echo -e "${YELLOW}查看所有Pod:${NC}       kubectl get pods"
    echo -e "${YELLOW}查看所有Service:${NC}   kubectl get services"
    echo -e "${YELLOW}查看Pod日誌:${NC}       kubectl logs <pod-name>"
    echo -e "${YELLOW}進入Pod Shell:${NC}     kubectl exec -it <pod-name> -- /bin/sh"
    echo -e "${YELLOW}端口轉發:${NC}          kubectl port-forward service/postgres-service 5432:5432"
    
    # 保存到文件提示
    echo -e "\n${GREEN}💡 提示:${NC} 可以將此信息保存到文件以便複製使用:"
    echo -e "   ${YELLOW}$0 > ~/k8s-services.txt${NC}"
}

# 執行主函數
main "$@"