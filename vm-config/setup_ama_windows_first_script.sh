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
location="eastus"
workspaceName="law-lm-logs-qauatfeature03-eastus"
vmNames=("test-ama-windows-new")
retentionInDays=30
# ------------------------------

# -------- PARAMETER VALIDATION --------
required_vars=("resourceGroup" "location" "workspaceName")

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

# --------------------------------------

log_info "Getting subscription ID..."
subscriptionId=$(az account show --query id -o tsv)
az config set extension.use_dynamic_install=yes_without_prompt

log_info "Creating Log Analytics Workspace: $workspaceName"
workspaceId=$(az monitor log-analytics workspace create \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --location "$location" \
  --query id -o tsv)

log_info "Setting retention to $retentionInDays days..."
az monitor log-analytics workspace update \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --retention-time "$retentionInDays"

# Install AMA on each VM
for vmName in "${vmNames[@]}"; do
  log_info "Checking if AMA is installed on $vmName..."
  if az vm extension show \
    --vm-name "$vmName" \
    --resource-group "$resourceGroup" \
    --name "AzureMonitorWindowsAgent" \
    --only-show-errors &>/dev/null; then

    log_info "AMA already installed on $vmName"
  else
    log_info "Installing AMA on $vmName..."
    az vm extension set \
      --vm-name "$vmName" \
      --resource-group "$resourceGroup" \
      --name AzureMonitorWindowsAgent \
      --publisher Microsoft.Azure.Monitor \
      --enable-auto-upgrade \
      --only-show-errors
  fi
done