#!/bin/bash

set -euo pipefail
trap 'log_error "Script failed at line $LINENO: $BASH_COMMAND"; exit 1' ERR

log_info() {
  echo "[INFO] $(date '+%Y-%m-%d %H:%M:%S') $*"
}

log_error() {
  echo "[ERROR] $(date '+%Y-%m-%d %H:%M:%S') $*" >&2
}

# -------- CONFIGURATION --------
resourceGroup="lm-logs-qauatfeature03-eastus-group"
workspaceName="law-lm-logs-qauatfeature03-eastus"
vmNames=("test-ama-windows-new")
namespace="lm-logs-qauatfeature03-eastus"
eventhubName="log-hub"
dataExportRuleName="der-lm-logs-qauatfeature03-eastus"
# ------------------------------

# -------- PARAMETER VALIDATION --------
required_vars=("resourceGroup" "workspaceName" "namespace" "dataExportRuleName" "eventhubName")

for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]]; then
    log_error "Error: Required variable '$var_name' is not set."
    exit 1
  fi
done

if [[ ${#vmNames[@]} -eq 0 ]]; then
  log_error "Error: vmNames array is empty."
  exit 1
fi

log_info "Getting subscription ID..."
subscriptionId=$(az account show --query id -o tsv)

# Send test event to VMs
for vmName in "${vmNames[@]}"; do
  log_info "Sending test event from $vmName..."
  az vm run-command invoke \
    --resource-group "$resourceGroup" \
    --name "$vmName" \
    --command-id RunPowerShellScript \
    --scripts 'eventcreate /ID 1000 /L APPLICATION /T INFORMATION /SO "DCRValidation" /D "Test event for AMA verification"' \
    --only-show-errors

  sleep 45
done

# Create Data Export Rule
log_info "Creating Data Export Rule to Event Hub..."
az monitor log-analytics workspace data-export create \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --name "$dataExportRuleName" \
  --destination "/subscriptions/$subscriptionId/resourceGroups/$resourceGroup/providers/Microsoft.EventHub/namespaces/$namespace/eventhubs/$eventhubName" \
  --enable \
  --tables Event