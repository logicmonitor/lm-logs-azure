# 🚀 Windows AMA Migration, Deployment & Troubleshooting Guide

This guide walks you through:
- 🔄 Migrating from the legacy Windows Diagnostic Extension (WAD)
- ⚙️ Deploying Azure Monitor Agent (AMA) on Windows VMs
- 🛠️ Troubleshooting common issues

## ⚠️ 1. Migration from WAD to AMA

Microsoft is deprecating the **Windows Diagnostic Extension (WAD)** on March 31, 2026. It will no longer receive updates or support.

### 🔁 Step 1: Remove WAD from Windows VMs
```bash
az vm extension delete \
  --vm-name <vm-name> \
  --resource-group <resource-group> \
  --name IaaSDiagnostics
```
⚠️ Do this before installing AMA to avoid conflicts.


## ⚙️ 2. Deploy AMA and Configure Logging
### 🧾 Prerequisites
- Azure CLI installed and authenticated
- `monitor-control-service` extension installed
- Event Hub ready
- The VM OS is supported for AMA. Ref - https://learn.microsoft.com/en-us/azure/azure-monitor/agents/azure-monitor-agent-supported-operating-systems

### ⚙️ Configurable Variables (edit in the script)
```bash
resourceGroup="" #the resource grp where you want to create the LAW and DCR.
vmNames=("vm1" "vm2") #name of the windows vms from which you want to collect the logs.
workspaceName="" #the name of the Log Analytics Workspace that will be created.
location="" #the region of the resources to be created in. Note - the region should be same as the vm and the deployment resources in step 1.
namespace="" #the eventhub namespace created as a part of the deployment in step 1.
eventhubName="log-hub"
dataExportRuleName="" #the name of the export rule which will be created in the LAW
```

### ⚙️ Step 1: Deployment Script to Create LAW and Install AMA
Save the following script [setup_ama_windows_first_script.sh](./setup_ama_windows_first_script.sh) and run it in Cloud Shell:
```bash
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
```

### ⚙️ Step 2: Create a Data Collection Rule (DCR)
You must use the Azure Portal to create the DCR that connects the VMs to the LAW:
1. Go to Azure Monitor > Data Collection Rules
2. Click “Create”
3. Choose: 
      - Platform Type: Windows
      - Region: Must match your LAW and VM
4. On the Resources tab: Add the Windows VM(s) where AMA is installed
5. On Collect and Deliver: 
      - Add Data Source → Type: Windows Event Logs
      - Add Destination → Select the LAW created above
6. Review and create the DCR
   💡 This ensures the Event table is created and AMA-enabled in the workspace.

### ⚙️ Step 3: Send a Test Event + Create Export Rule
After the DCR is created, use the following script [setup_ama_windows_second_script.sh](./setup_ama_windows_second_script.sh) to:
- Send a test Windows Event
- Create a data export rule to send logs to Event Hub (optional)
```bash
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

  sleep 30
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
```

### 📈 Step 4: Validate Logs with KQL
Once the test event is sent, validate that it reached your LAW.

In Azure Portal:
1. Go to Log Analytics Workspace > Logs 
2. Run the following query:
```kql
   Event
   | where TimeGenerated > ago(30m)
   | sort by TimeGenerated desc
```
You should see your test event with the source DCRValidation.

### 📤 Verify Event Hub Export
- Go to your Event Hub in Azure Portal
- View metrics: "Incoming Messages"
- Use an Event Hub consumer to see if data is flowing

## 🛠️ 4. Troubleshooting
### ❗ DCR creation fails with InvalidOutputTable
Cause: Azure hasn't provisioned the Event tables yet.
Fix:
- Retry after 30–60 seconds

### ❗ Logs not appearing in LAW
- Confirm DCR is associated with the VM:
```bash
az monitor data-collection rule association list \
  --resource "/subscriptions/<sub>/resourceGroups/<rg>/providers/Microsoft.Compute/virtualMachines/<vm>"
```
- Check that the VM has a system-assigned identity
- Ensure AMA extension is running:
```bash
az vm extension show \
  --name AzureMonitorWindowsAgent \
  --resource-group <your-resource-group> \
  --vm-name <your-vm-name> \
  --output json
```

### ❗ Event Hub has no incoming messages
- Confirm the export rule was created correctly
  Use Azure CLI:
```bash
az monitor log-analytics workspace data-export list \
--resource-group <rg> \
--workspace-name <law-name>
```
