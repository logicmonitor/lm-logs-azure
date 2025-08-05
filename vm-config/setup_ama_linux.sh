#!/bin/bash

# ----------- CONFIG SECTION ------------
SUBSCRIPTION_ID=""
RESOURCE_GROUP=""
VM_NAMES=("")
LAW_NAME=""
LOCATION=""
DCR_NAME="dcr-${RESOURCE_GROUP}"
DCR_FILE="dcr.json"
NAMESPACE=""
EVENTHUB_NAME="log-hub"
EXPORT_RULE_NAME=""
# ---------------------------------------

az config set extension.use_dynamic_install=yes_without_prompt
az extension add --name monitor-control-service --only-show-errors

echo "Checking/creating Log Analytics Workspace..."
az monitor log-analytics workspace show \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$LAW_NAME" &>/dev/null || \
az monitor log-analytics workspace create \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$LAW_NAME" \
  --location "$LOCATION"

LAW_ID=$(az monitor log-analytics workspace show \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$LAW_NAME" \
  --query id -o tsv)

sleep 30

echo "Writing DCR JSON..."
cat > "$DCR_FILE" <<EOF
{
  "location": "$LOCATION",
  "properties": {
    "dataSources": {
      "syslog": [
        {
          "name": "syslog-ds",
          "streams": ["Microsoft-Syslog"],
          "facilityNames": ["auth", "authpriv", "cron", "daemon", "kern", "lpr", "mail",
                            "news", "syslog", "user", "uucp",
                            "local0", "local1", "local2", "local3",
                            "local4", "local5", "local6", "local7"],
          "logLevels": ["Debug", "Info", "Notice", "Warning", "Error", "Critical", "Alert", "Emergency"]
        }
      ],
      "performanceCounters": [
        {
          "name": "perf-ds",
          "streams": ["Microsoft-InsightsMetrics"],
          "samplingFrequencyInSeconds": 60,
          "counterSpecifiers": [
            "\\\\processor\\\\percent_processor_time",
            "\\\\memory\\\\available_bytes",
            "\\\\memory\\\\percent_available_memory",
            "\\\\logicaldisk\\\\percent_free_space",
            "\\\\networkadapter\\\\bytes_total_per_sec"
          ]
        }
      ]
    },
    "destinations": {
      "logAnalytics": [
        {
          "workspaceResourceId": "$LAW_ID",
          "name": "loganalytics"
        }
      ]
    },
    "dataFlows": [
      {
        "streams": ["Microsoft-Syslog"],
        "destinations": ["loganalytics"]
      },
      {
        "streams": ["Microsoft-InsightsMetrics"],
        "destinations": ["loganalytics"]
      }
    ]
  }
}
EOF

# ------------------ Idempotent DCR Creation ------------------
echo "Checking for existing Data Collection Rule..."
if ! az monitor data-collection rule show --name "$DCR_NAME" --resource-group "$RESOURCE_GROUP" &>/dev/null; then
  echo "Creating Data Collection Rule..."
  az monitor data-collection rule create \
    --name "$DCR_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --rule-file "$DCR_FILE" \
    --only-show-errors
else
  echo "DCR '$DCR_NAME' already exists. Skipping creation."
fi
# -------------------------------------------------------------

for VM_NAME in "${VM_NAMES[@]}"; do
  echo "Processing VM: $VM_NAME"

  echo "Assigning system-assigned identity..."
  az vm identity assign --resource-group "$RESOURCE_GROUP" --name "$VM_NAME" --only-show-errors

  echo "Removing OMS agent if present..."
  az vm extension delete --vm-name "$VM_NAME" --resource-group "$RESOURCE_GROUP" --name OmsAgentForLinux &>/dev/null

  echo "Installing AMA..."
  az vm extension delete --vm-name "$VM_NAME" --resource-group "$RESOURCE_GROUP" --name AzureMonitorLinuxAgent &>/dev/null
  az vm extension set \
    --name AzureMonitorLinuxAgent \
    --publisher Microsoft.Azure.Monitor \
    --vm-name "$VM_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --enable-auto-upgrade true --only-show-errors

  sleep 30

  echo "Associating DCR with VM..."
  az monitor data-collection rule association create \
    --name "dcr-association-${VM_NAME}" \
    --resource "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Compute/virtualMachines/$VM_NAME" \
    --rule-id "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.Insights/dataCollectionRules/$DCR_NAME" \
    --only-show-errors
done

# ------------------ Idempotent Export Rule ------------------
echo "Checking for existing Data Export Rule..."
if ! az monitor log-analytics workspace data-export show \
  --resource-group "$RESOURCE_GROUP" \
  --workspace-name "$LAW_NAME" \
  --name "$EXPORT_RULE_NAME" &>/dev/null; then
  echo "Creating Data Export Rule to Event Hub..."
  az monitor log-analytics workspace data-export create \
    --resource-group "$RESOURCE_GROUP" \
    --workspace-name "$LAW_NAME" \
    --name "$EXPORT_RULE_NAME" \
    --destination "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.EventHub/namespaces/$NAMESPACE/eventhubs/$EVENTHUB_NAME" \
    --enable \
    --tables Syslog Perf Heartbeat
else
  echo "Export rule '$EXPORT_RULE_NAME' already exists. Skipping creation."
fi
# ------------------------------------------------------------

echo "Setup complete. Logs and metrics are being forwarded to Log Analytics and Event Hub."