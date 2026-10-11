/*
 * Copyright (C) 2020 LogicMonitor, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

### Variables ###
variable "lm_company_name" {
  type        = string
  description = "LogicMonitor company name"
}

variable "lm_access_id" {
  type        = string
  description = "LogicMonitor access id"
}

variable "lm_access_key" {
  type        = string
  description = "LogicMonitor access key"
}

variable "azure_region" {
  type        = string
  description = "Azure region"
}

variable "azure_client_id" {
  type        = string
  description = "Azure Application Client ID"
}

variable "use_custom_storage_account_name" {
  type        = bool
  description = "If true, create the storage account with storage_account_name. If false, keep the generated storage account name."
  default     = false
}

variable "storage_account_name" {
  type        = string
  description = "Example: acmeeastus (company acme, region eastus). Required when use_custom_storage_account_name or use_existing_storage_account is true. Ignored when both are false. 3-24 characters, lowercase letters and numbers only."
  default     = ""
}

variable "use_existing_storage_account" {
  type        = bool
  description = "If true, use the storage account storage_account_name in existing_storage_account_resource_group. The account must already exist there. If false, create a storage account in the LM logs resource group."
  default     = false
}

variable "existing_storage_account_resource_group" {
  type        = string
  description = "Required when use_existing_storage_account=true. Resource group of the existing storage account. Same subscription as this deployment."
  default     = ""
}

variable "use_custom_function_app_name" {
  type        = bool
  description = "If true, create the Function App with function_app_name. If false, keep the generated Function App name."
  default     = false
}

variable "function_app_name" {
  type        = string
  description = "Example: lm-logs-acme-eastus (company acme, region eastus). Required when use_custom_function_app_name or use_existing_function_app is true. Ignored when both are false. 2-60 characters; letters, numbers, and hyphens; cannot start or end with a hyphen."
  default     = ""
}

variable "use_existing_function_app" {
  type        = bool
  description = "If true, use the Function App function_app_name in existing_function_app_resource_group. It must already exist there. Application settings on that Function App are replaced with the LM Logs settings. If false, create a Function App in the LM logs resource group."
  default     = false
}

variable "existing_function_app_resource_group" {
  type        = string
  description = "Required when use_existing_function_app=true. Resource group of the existing Function App. Same subscription as this deployment."
  default     = ""
}

variable "use_existing_event_hub" {
  type        = bool
  description = "If true, reuse an existing Event Hub instead of creating namespace/hub/consumer group."
  default     = false
}

variable "event_hub_name" {
  type        = string
  description = "Event Hub name for log ingestion. Created when use_existing_event_hub=false; must already exist when true."
  default     = "log-hub"
}

variable "event_hub_consumer_group" {
  type        = string
  description = "Event Hub consumer group for the Function trigger. Created when use_existing_event_hub=false and not $Default; must already exist when true."
  default     = "$Default"
}

variable "existing_event_hub_resource_group" {
  type        = string
  description = "Required when use_existing_event_hub=true. Resource group of the existing Event Hub namespace."
  default     = ""
}

variable "existing_event_hub_namespace" {
  type        = string
  description = "Required when use_existing_event_hub=true. Existing Event Hub namespace name."
  default     = ""
}

variable "existing_event_hub_authorization_rule" {
  type        = string
  description = "Listen-only authorization rule for LogsEventHubConnectionString. Prefer a hub-level listener rule."
  default     = "listener"
}

variable "existing_event_hub_auth_rule_scope" {
  type        = string
  description = "Namespace or EventHub scope for existing_event_hub_authorization_rule. Default EventHub matches a typical listener rule."
  default     = "EventHub"

  validation {
    condition     = contains(["Namespace", "EventHub"], var.existing_event_hub_auth_rule_scope)
    error_message = "existing_event_hub_auth_rule_scope must be Namespace or EventHub."
  }
}

variable "enable_activity_logs" {
  type        = bool
  description = "Reserved for parity with ARM. Terraform does not create subscription Activity Log diagnostic settings yet; leave false. Use ARM parent template for Activity Logs."
  default     = false
}

variable "existing_event_hub_send_authorization_rule" {
  type        = string
  description = "Namespace-level Send rule name for Activity Logs when using ARM. Unused by Terraform until Activity Logs parity is added."
  default     = ""
}

variable "tags" {
  description = "Tags given to the resources created by this template"
  type        = map(string)
  default     = {
    Application = "LM Logs Beta"
    Environment = "-"
    Criticality = "-"
    Owner       = "-"
  }
}

### Locals ###
locals {
  namespace = "lm-logs-${var.lm_company_name}-${replace(var.azure_region, " ", "")}"
  storage = lower(replace(replace(local.namespace, "2", "two"), "/[^A-Za-z]+/", ""))
  tags = merge(
    var.tags,
    {
      deployedBy = "Terraform"
    }
  )
  create_event_hub = !var.use_existing_event_hub
  existing_config_complete = (
    var.existing_event_hub_resource_group != "" &&
    var.existing_event_hub_namespace != "" &&
    var.event_hub_name != "" &&
    var.existing_event_hub_authorization_rule != ""
  )
  event_hub_name = var.event_hub_name
  event_hub_consumer_group = var.event_hub_consumer_group
  generated_storage_account_name = length(local.storage) > 24 ? substr(local.storage, length(local.storage) - 24, 24) : local.storage
  create_storage_account = !var.use_existing_storage_account
  create_function_app = !var.use_existing_function_app
  storage_account_name = (var.use_existing_storage_account || var.use_custom_storage_account_name) ? var.storage_account_name : local.generated_storage_account_name
  function_app_name = (var.use_existing_function_app || var.use_custom_function_app_name) ? var.function_app_name : local.namespace
  storage_account_access_key = local.create_storage_account ? join("", azurerm_storage_account.lm_logs.*.primary_access_key) : join("", data.azurerm_storage_account.existing.*.primary_access_key)
  azurewebjobs_storage = "DefaultEndpointsProtocol=https;AccountName=${local.storage_account_name};AccountKey=${local.storage_account_access_key}"
  event_hub_connection_string = (
    var.use_existing_event_hub
    ? (
      var.existing_event_hub_auth_rule_scope == "EventHub"
      ? data.azurerm_eventhub_authorization_rule.existing_hub[0].primary_connection_string
      : data.azurerm_eventhub_namespace_authorization_rule.existing_namespace[0].primary_connection_string
    )
    : azurerm_eventhub_authorization_rule.lm_logs_listener[0].primary_connection_string
  )
}

### Providers ###
provider "azurerm" {
  version = ">= 2.0.0"
  features {}
}

### Validation ###
resource "null_resource" "validate_existing_event_hub_inputs" {
  count = var.use_existing_event_hub && !local.existing_config_complete ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_existing_event_hub=true requires existing_event_hub_resource_group, existing_event_hub_namespace, event_hub_name, and existing_event_hub_authorization_rule.' && exit 1"
  }
}

resource "null_resource" "validate_custom_storage_account_name" {
  count = var.use_custom_storage_account_name && !var.use_existing_storage_account && var.storage_account_name == "" ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_custom_storage_account_name=true requires storage_account_name.' && exit 1"
  }
}

resource "null_resource" "validate_custom_function_app_name" {
  count = var.use_custom_function_app_name && !var.use_existing_function_app && var.function_app_name == "" ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_custom_function_app_name=true requires function_app_name.' && exit 1"
  }
}

resource "null_resource" "validate_existing_storage_account_inputs" {
  count = var.use_existing_storage_account && (var.storage_account_name == "" || var.existing_storage_account_resource_group == "") ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_existing_storage_account=true requires storage_account_name and existing_storage_account_resource_group.' && exit 1"
  }
}

resource "null_resource" "validate_existing_function_app_inputs" {
  count = var.use_existing_function_app && (var.function_app_name == "" || var.existing_function_app_resource_group == "") ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_existing_function_app=true requires function_app_name and existing_function_app_resource_group.' && exit 1"
  }
}

resource "null_resource" "validate_activity_logs_unsupported_in_tf" {
  count = var.enable_activity_logs ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: enable_activity_logs=true is not supported in deploy.tf yet (no diagnostic setting resource). Set enable_activity_logs=false and use ARM deployRGParent.json for Activity Logs, or wait for TF parity.' && exit 1"
  }
}

### Data sources for Mode B (fail deployment if missing) ###
data "azurerm_eventhub_namespace" "existing" {
  count               = var.use_existing_event_hub ? 1 : 0
  name                = var.existing_event_hub_namespace
  resource_group_name = var.existing_event_hub_resource_group

  depends_on = [null_resource.validate_existing_event_hub_inputs]
}

data "azurerm_eventhub" "existing" {
  count               = var.use_existing_event_hub ? 1 : 0
  name                = var.event_hub_name
  namespace_name      = var.existing_event_hub_namespace
  resource_group_name = var.existing_event_hub_resource_group

  depends_on = [data.azurerm_eventhub_namespace.existing]
}

data "azurerm_eventhub_consumer_group" "existing" {
  count               = var.use_existing_event_hub && var.event_hub_consumer_group != "$Default" ? 1 : 0
  name                = var.event_hub_consumer_group
  namespace_name      = var.existing_event_hub_namespace
  eventhub_name       = var.event_hub_name
  resource_group_name = var.existing_event_hub_resource_group

  depends_on = [data.azurerm_eventhub.existing]
}

data "azurerm_eventhub_namespace_authorization_rule" "existing_namespace" {
  count               = var.use_existing_event_hub && var.existing_event_hub_auth_rule_scope == "Namespace" ? 1 : 0
  name                = var.existing_event_hub_authorization_rule
  namespace_name      = var.existing_event_hub_namespace
  resource_group_name = var.existing_event_hub_resource_group

  depends_on = [data.azurerm_eventhub_namespace.existing]
}

data "azurerm_eventhub_authorization_rule" "existing_hub" {
  count               = var.use_existing_event_hub && var.existing_event_hub_auth_rule_scope == "EventHub" ? 1 : 0
  name                = var.existing_event_hub_authorization_rule
  namespace_name      = var.existing_event_hub_namespace
  eventhub_name       = var.event_hub_name
  resource_group_name = var.existing_event_hub_resource_group

  depends_on = [data.azurerm_eventhub.existing]
}

data "azurerm_storage_account" "existing" {
  count               = var.use_existing_storage_account ? 1 : 0
  name                = var.storage_account_name
  resource_group_name = var.existing_storage_account_resource_group

  depends_on = [null_resource.validate_existing_storage_account_inputs]
}

resource "null_resource" "validate_existing_function_app_exists" {
  count = var.use_existing_function_app && var.function_app_name != "" && var.existing_function_app_resource_group != "" ? 1 : 0

  depends_on = [null_resource.validate_existing_function_app_inputs]

  provisioner "local-exec" {
    command = "kind=$(az functionapp show --resource-group ${var.existing_function_app_resource_group} --name ${var.function_app_name} --query kind -o tsv) && echo \"$kind\" | grep -qi functionapp || (echo 'ERROR: use_existing_function_app=true requires an existing Function App at function_app_name in existing_function_app_resource_group.' && exit 1)"
  }
}

### Resources ###
## Resource Groups ##
resource "azurerm_resource_group" "lm_logs" {
  name     = "${local.namespace}-group"
  location = var.azure_region
  tags     = local.tags
}

## Event Hub ##
# Namespace #
resource "azurerm_eventhub_namespace" "lm_logs" {
  count               = local.create_event_hub ? 1 : 0
  name                = local.namespace
  resource_group_name = azurerm_resource_group.lm_logs.name
  location            = var.azure_region
  sku                 = "Standard"
  capacity            = 1
  tags                = local.tags
}

# Event Hub #
resource "azurerm_eventhub" "lm_logs" {
  count               = local.create_event_hub ? 1 : 0
  name                = var.event_hub_name
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs[0].name
  partition_count     = 1
  message_retention   = 1
}

# Event Hub Consumer Group (skipped when using built-in $Default) #
resource "azurerm_eventhub_consumer_group" "lm_logs" {
  count               = local.create_event_hub && var.event_hub_consumer_group != "$Default" ? 1 : 0
  name                = var.event_hub_consumer_group
  namespace_name      = azurerm_eventhub_namespace.lm_logs[0].name
  eventhub_name       = azurerm_eventhub.lm_logs[0].name
  resource_group_name = azurerm_resource_group.lm_logs.name
}

# Event Hub Authorization Sender Role #
resource "azurerm_eventhub_authorization_rule" "lm_logs_sender" {
  count               = local.create_event_hub ? 1 : 0
  name                = "sender"
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs[0].name
  eventhub_name       = azurerm_eventhub.lm_logs[0].name
  listen              = false
  send                = true
  manage              = false
}

# Event Hub Authorization Listener Role #
resource "azurerm_eventhub_authorization_rule" "lm_logs_listener" {
  count               = local.create_event_hub ? 1 : 0
  name                = "listener"
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs[0].name
  eventhub_name       = azurerm_eventhub.lm_logs[0].name
  listen              = true
  send                = false
  manage              = false
}

## Storage Account ##
resource "azurerm_storage_account" "lm_logs" {
  count                    = local.create_storage_account ? 1 : 0
  name                     = local.storage_account_name
  resource_group_name      = azurerm_resource_group.lm_logs.name
  location                 = var.azure_region
  account_tier             = "Standard"
  account_replication_type = "LRS"
  tags                     = local.tags

  depends_on = [null_resource.validate_custom_storage_account_name]
}

## App Service Plan ##
resource "azurerm_app_service_plan" "lm_logs" {
  count               = local.create_function_app ? 1 : 0
  name                = "${local.namespace}-service-plan"
  resource_group_name = azurerm_resource_group.lm_logs.name
  location            = var.azure_region
  kind                = "FunctionApp"
  reserved            = true
  tags                = local.tags
  sku {
    tier = "Standard"
    size = "S1"
  }
}

## Function App ##
resource "azurerm_function_app" "lm_logs" {
  count                      = local.create_function_app ? 1 : 0
  name                       = local.function_app_name
  resource_group_name        = azurerm_resource_group.lm_logs.name
  location                   = var.azure_region
  app_service_plan_id        = azurerm_app_service_plan.lm_logs[0].id
  storage_account_name       = local.storage_account_name
  storage_account_access_key = local.storage_account_access_key
  os_type                    = "linux"
  https_only                 = true
  version                    = "~4"
  tags                       = local.tags
  depends_on = concat(
    null_resource.validate_custom_function_app_name,
    azurerm_eventhub_consumer_group.lm_logs,
    data.azurerm_eventhub_consumer_group.existing,
    data.azurerm_eventhub.existing,
    data.azurerm_eventhub_namespace_authorization_rule.existing_namespace,
    data.azurerm_eventhub_authorization_rule.existing_hub,
  )
  site_config {
    always_on                    = true
    linux_fx_version             = "java|11"
    use_32_bit_worker_process    = false
  }
  app_settings = {
    FUNCTIONS_WORKER_RUNTIME     = "java"
    FUNCTIONS_EXTENSION_VERSION  = "~4"
    WEBSITE_RUN_FROM_PACKAGE     = "https://github.com/logicmonitor/lm-logs-azure/raw/master/package/lm-logs-azure-1.0.zip"
    # EventHubName / EventHubConsumerGroup are required by %EventHubName% / %EventHubConsumerGroup%
    # bindings. Defaults match Event_Hub_Name / Event_Hub_Consumer_Group (log-hub / $Default).
    LogsEventHubConnectionString = local.event_hub_connection_string
    EventHubName                 = local.event_hub_name
    EventHubConsumerGroup        = local.event_hub_consumer_group
    # false = historical checkpoint-on-error (possible loss). true = fail closed (possible duplicates).
    LM_FAIL_CLOSED_ON_INGEST     = "false"
    LogicMonitorCompanyName      = var.lm_company_name
    LogicMonitorAccessId         = var.lm_access_id
    LogicMonitorAccessKey        = var.lm_access_key
    AzureClientID                = var.azure_client_id
    /* Uncomment to set custom connection timeout */
    # LogApiClientConnectTimeout   = 10000

    /* Uncomment to set custom read timeout */
    # LogApiClientReadTimeout      = 10000

    /* Uncomment to turn on HTTP debugging */
    # LogApiClientDebugging        = true

    /* Uncomment to remove matching text from the logs */
    # LogRegexScrub                = "\\d+\\.\\d+\\.\\d+\\.\\d+"
  }
}

### Misc ###
resource "null_resource" "restart_function_app_after_2_minutes" {
  count = local.create_function_app ? 1 : 0

  provisioner "local-exec" {
    command = "sleep 120 && az functionapp restart --resource-group ${azurerm_resource_group.lm_logs.name} --name ${azurerm_function_app.lm_logs[0].name}"
  }
}

resource "null_resource" "configure_existing_function_app" {
  count = var.use_existing_function_app ? 1 : 0

  depends_on = [
    null_resource.validate_existing_function_app_inputs,
    null_resource.validate_existing_storage_account_inputs,
    null_resource.validate_existing_function_app_exists,
    data.azurerm_storage_account.existing,
    azurerm_storage_account.lm_logs,
    azurerm_eventhub_authorization_rule.lm_logs_listener,
    data.azurerm_eventhub_authorization_rule.existing_hub,
    data.azurerm_eventhub_namespace_authorization_rule.existing_namespace,
  ]

  triggers = {
    function_app_name    = local.function_app_name
    storage_account_name = local.storage_account_name
    event_hub_name       = local.event_hub_name
  }

  provisioner "local-exec" {
    command = "az functionapp config appsettings set --resource-group \"$FUNCTION_RG\" --name \"$FUNCTION_NAME\" --settings FUNCTIONS_EXTENSION_VERSION=~4 FUNCTIONS_WORKER_RUNTIME=java WEBSITE_RUN_FROM_PACKAGE=https://github.com/logicmonitor/lm-logs-azure/raw/master/package/lm-logs-azure-1.0.zip EventHubName=\"$EVENT_HUB_NAME\" EventHubConsumerGroup=\"$EVENT_HUB_CONSUMER_GROUP\" LM_FAIL_CLOSED_ON_INGEST=false LogicMonitorCompanyName=\"$LM_COMPANY\" LogicMonitorAccessId=\"$LM_ACCESS_ID\" LogicMonitorAccessKey=\"$LM_ACCESS_KEY\" AzureClientID=\"$AZURE_CLIENT_ID\" AzureWebJobsStorage=\"$AZUREWEBJOBS_STORAGE\" LogsEventHubConnectionString=\"$EVENT_HUB_CONNECTION\" && az functionapp restart --resource-group \"$FUNCTION_RG\" --name \"$FUNCTION_NAME\""

    environment = {
      FUNCTION_RG              = var.existing_function_app_resource_group
      FUNCTION_NAME            = local.function_app_name
      EVENT_HUB_NAME           = local.event_hub_name
      EVENT_HUB_CONSUMER_GROUP = local.event_hub_consumer_group
      LM_COMPANY               = var.lm_company_name
      LM_ACCESS_ID             = var.lm_access_id
      LM_ACCESS_KEY            = var.lm_access_key
      AZURE_CLIENT_ID          = var.azure_client_id
      AZUREWEBJOBS_STORAGE     = local.azurewebjobs_storage
      EVENT_HUB_CONNECTION     = local.event_hub_connection_string
    }
  }
}
