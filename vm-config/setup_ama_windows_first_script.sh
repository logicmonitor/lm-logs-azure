#!/bin/bash

set -euo pipefail

# -------- CONFIGURATION --------
resourceGroup=""
location=""
workspaceName=""
vmNames=("vm1" "vm2")
retentionInDays=30
namespace=""
eventhubName="log-hub"
dataExportRuleName=""
# ------------------------------

echo "Getting subscription ID..."
subscriptionId=$(az account show --query id -o tsv)
az config set extension.use_dynamic_install=yes_without_prompt

echo "Creating Log Analytics Workspace: $workspaceName"
workspaceId=$(az monitor log-analytics workspace create \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --location "$location" \
  --query id -o tsv)

echo "Setting retention to $retentionInDays days..."
az monitor log-analytics workspace update \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --retention-time "$retentionInDays"

# Install AMA on each VM
for vmName in "${vmNames[@]}"; do
  echo "Checking if AMA is installed on $vmName..."
  if az vm extension show \
    --vm-name "$vmName" \
    --resource-group "$resourceGroup" \
    --name "AzureMonitorWindowsAgent" \
    --only-show-errors &>/dev/null; then

    echo "AMA already installed on $vmName"
  else
    echo "Installing AMA on $vmName..."
    az vm extension set \
      --vm-name "$vmName" \
      --resource-group "$resourceGroup" \
      --name AzureMonitorWindowsAgent \
      --publisher Microsoft.Azure.Monitor \
      --enable-auto-upgrade \
      --only-show-errors
  fi
done