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
MAX_RETRIES=3
RETRY_INTERVAL=20
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

# ------------------ Retry Block for DCR ------------------
ATTEMPT=1
while [ $ATTEMPT -le $MAX_RETRIES ]; do
  echo "Attempt $ATTEMPT: Creating Data Collection Rule..."
  az monitor data-collection rule create \
    --name "$DCR_NAME" \
    --resource-group "$RESOURCE_GROUP" \
    --rule-file "$DCR_FILE" \
    --only-show-errors

  if [ $? -eq 0 ]; then
    echo "DCR created successfully on attempt $ATTEMPT."
    break
  else
    echo "DCR creation failed (likely due to table unavailability). Retrying in $RETRY_INTERVAL seconds..."
    sleep $RETRY_INTERVAL
    ((ATTEMPT++))
  fi
done

if [ $ATTEMPT -gt $MAX_RETRIES ]; then
  echo "ERROR: DCR creation failed after $MAX_RETRIES attempts. Exiting."
  exit 1
fi
# ----------------------------------------------------------

for VM_NAME in "${VM_NAMES[@]}"; do
  echo "Processing VM: $VM_NAME"

  echo "Assigning system-assigned identity..."
  az vm identity assign --resource-group "$RESOURCE_GROUP" --name "$VM_NAME" --only-show-errors

  echo "Removing OMS agent if present..."
  az vm extension delete --vm-name "$VM_NAME" --resource-group "$RESOURCE_GROUP" --name OmsAgentForLinux &>/dev/null

  echo "Reinstalling AMA..."
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

echo "Creating Data Export Rule to Event Hub..."
az monitor log-analytics workspace data-export create \
  --resource-group $RESOURCE_GROUP \
  --workspace-name $LAW_NAME \
  --name MyLinuxExporttoLM \
  --destination "/subscriptions/$SUBSCRIPTION_ID/resourceGroups/$RESOURCE_GROUP/providers/Microsoft.EventHub/namespaces/$NAMESPACE/eventhubs/$EVENTHUB_NAME" \
  --enable \
  --tables Syslog Perf Heartbeat

echo "Setup complete for all VMs. Logs and metrics are now forwarded to Log Analytics and Event Hub."
