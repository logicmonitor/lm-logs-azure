#!/bin/bash

# -------- CONFIGURATION --------
resourceGroup=""
workspaceName=""
vmNames=("vm1" "vm2")
namespace=""
eventhubName="log-hub"
dataExportRuleName=""
# ------------------------------

echo "Getting subscription ID..."
subscriptionId=$(az account show --query id -o tsv)

# Send test event to VMs
for vmName in "${vmNames[@]}"; do
  echo "Sending test event from $vmName..."
  az vm run-command invoke \
    --resource-group "$resourceGroup" \
    --name "$vmName" \
    --command-id RunPowerShellScript \
    --scripts 'eventcreate /ID 1000 /L APPLICATION /T INFORMATION /SO "DCRValidation" /D "Test event for AMA verification"' \
    --only-show-errors

  sleep 45
done

# Create Data Export Rule
echo "Creating Data Export Rule to Event Hub..."
az monitor log-analytics workspace data-export create \
  --resource-group "$resourceGroup" \
  --workspace-name "$workspaceName" \
  --name "$dataExportRuleName" \
  --destination "/subscriptions/$subscriptionId/resourceGroups/$resourceGroup/providers/Microsoft.EventHub/namespaces/$namespace/eventhubs/$eventhubName" \
  --enable \
  --tables Event