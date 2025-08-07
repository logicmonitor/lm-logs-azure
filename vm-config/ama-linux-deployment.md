# 🚀 Linux AMA Migration, Deployment & Troubleshooting Guide

This guide walks you through:
- 🔄 Migrating from the legacy Linux Diagnostic Extension (LAD)
- ⚙️ Deploying Azure Monitor Agent (AMA) on Linux VMs
- 🛠️ Troubleshooting common issues
---

## ⚠️ 1. Migration from LAD to AMA

Microsoft is deprecating the **Linux Diagnostic Extension (LAD)** on March 31, 2026. It will no longer receive updates or support.

### 🔁 Step 1: Remove LAD from Linux VMs
```bash
az vm extension delete \
  --vm-name <vm-name> \
  --resource-group <resource-group> \
  --name LinuxDiagnostic
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
SUBSCRIPTION_ID="" #the subscription id where the vm and the above development is done.
RESOURCE_GROUP="" #the resource grp where you want to create the LAW and DCR.
VM_NAMES=("vm1" "vm2") #name of the linux vms from which you want to collect the logs.
LAW_NAME="" #the name of the Log Analytics Workspace that will be created.
LOCATION="" #the region of the resources to be created in. Note - the region should be same as the vm and the deployment resources in step 1.
NAMESPACE="" #the eventhub namespace created as a part of the deployment in step 1.
EVENTHUB_NAME="log-hub"
EXPORT_RULE_NAME="" #the name of the export rule which will be created in the LAW
```

### 📜 Deployment Script
- We provide a single script that:
- Creates LAW (if not present)
- Creates a Data Collection Rule (DCR). Please check the "facilityNames" and "logLevels" in the script and specify the ones as per your need.
- Installs the AMA agent
- Associates VMs with the DCR
- Creates an Event Hub export rule from tables like Syslog, Perf and Heartbeat. You can modify the tables as per your need
👉 [Download or view script](setup_ama_linux.sh)

### ▶️ Run the script
```bash
chmod +x setup_ama_linux.sh
./setup_ama_linux.sh
```

## 🔎 3. Validate AMA Setup
📈 Verify logs in Azure Monitor
Run this Kusto query (KQL) in Log Analytics:
```kql
Syslog
| where TimeGenerated > ago(30m)
| summarize count() by HostName, Facility
```

### 📤 Verify Event Hub Export
- Go to your Event Hub in Azure Portal
- View metrics: "Incoming Messages"
- Use an Event Hub consumer to see if data is flowing

## 🛠️ 4. Troubleshooting
### ❗ DCR creation fails with InvalidOutputTable
Cause: Azure hasn't provisioned the Syslog or InsightsMetrics tables yet.
Fix:
- Retry after 30–60 seconds
- Our script retries automatically 3 times with delay

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
  --name AzureMonitorLinuxAgent \
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