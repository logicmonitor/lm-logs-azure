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

variable "use_existing_event_hub" {
  type        = bool
  description = "If true, reuse an existing Event Hub instead of creating namespace/hub/consumer group."
  default     = false
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
  description = "Enable subscription Activity Logs to the Event Hub. Create mode uses LM namespace RootManageSharedAccessKey. Reuse mode requires existing_event_hub_send_authorization_rule."
  default     = true
}

variable "existing_event_hub_send_authorization_rule" {
  type        = string
  description = "Required when use_existing_event_hub=true and enable_activity_logs=true. Send-capable rule for Activity Logs. Not stored on the Function App."
  default     = ""
}

variable "existing_event_hub_send_auth_rule_scope" {
  type        = string
  description = "Namespace or EventHub scope for existing_event_hub_send_authorization_rule."
  default     = "Namespace"

  validation {
    condition     = contains(["Namespace", "EventHub"], var.existing_event_hub_send_auth_rule_scope)
    error_message = "existing_event_hub_send_auth_rule_scope must be Namespace or EventHub."
  }
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

resource "null_resource" "validate_activity_logs_send_rule" {
  count = var.use_existing_event_hub && var.enable_activity_logs && var.existing_event_hub_send_authorization_rule == "" ? 1 : 0

  provisioner "local-exec" {
    command = "echo 'ERROR: use_existing_event_hub=true with enable_activity_logs=true requires existing_event_hub_send_authorization_rule (Send). Set enable_activity_logs=false or provide a Send rule. The Function Listen rule is not used for Activity Logs.' && exit 1"
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
  name                     = length(local.storage) > 24 ? substr(local.storage, length(local.storage) - 24, 24) : local.storage
  resource_group_name      = azurerm_resource_group.lm_logs.name
  location                 = var.azure_region
  account_tier             = "Standard"
  account_replication_type = "LRS"
  tags                     = local.tags
}

## App Service Plan ##
resource "azurerm_app_service_plan" "lm_logs" {
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
  name                       = local.namespace
  resource_group_name        = azurerm_resource_group.lm_logs.name
  location                   = var.azure_region
  app_service_plan_id        = azurerm_app_service_plan.lm_logs.id
  storage_account_name       = azurerm_storage_account.lm_logs.name
  storage_account_access_key = azurerm_storage_account.lm_logs.primary_access_key
  os_type                    = "linux"
  https_only                 = true
  version                    = "~3"
  tags                       = local.tags
  depends_on = concat(
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
    FUNCTIONS_EXTENSION_VERSION  = "~3"
    WEBSITE_RUN_FROM_PACKAGE     = "https://github.com/logicmonitor/lm-logs-azure/raw/master/package/lm-logs-azure.zip"
    # EventHubName / EventHubConsumerGroup are required by the package bindings. Keep them in
    # app_settings so terraform apply migrates existing Function Apps before/with package updates.
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
  provisioner "local-exec" {
    command = "sleep 120 && az functionapp restart --resource-group ${azurerm_resource_group.lm_logs.name} --name ${azurerm_function_app.lm_logs.name}"
  }
}
