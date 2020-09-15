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
}

### Providers ###
provider "azurerm" {
  version = ">= 2.0.0"
  features {}
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
  name                = local.namespace
  resource_group_name = azurerm_resource_group.lm_logs.name
  location            = var.azure_region
  sku                 = "Standard"
  capacity            = 1
  tags                = local.tags
}

# Event Hub #
resource "azurerm_eventhub" "lm_logs" {
  name                = "log-hub"
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs.name
  partition_count     = 1
  message_retention   = 1
}

# Event Hub Authorization Sender Role #
resource "azurerm_eventhub_authorization_rule" "lm_logs_sender" {
  name                = "sender"
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs.name
  eventhub_name       = azurerm_eventhub.lm_logs.name
  listen              = false
  send                = true
  manage              = false
}

# Event Hub Authorization Listener Role #
resource "azurerm_eventhub_authorization_rule" "lm_logs_listener" {
  name                = "listener"
  resource_group_name = azurerm_resource_group.lm_logs.name
  namespace_name      = azurerm_eventhub_namespace.lm_logs.name
  eventhub_name       = azurerm_eventhub.lm_logs.name
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
  site_config {
    always_on                = true
    linux_fx_version         = "java|11"
  }
  app_settings = {
    FUNCTIONS_WORKER_RUNTIME     = "java"
    FUNCTIONS_EXTENSION_VERSION  = "~3"
    WEBSITE_RUN_FROM_PACKAGE     = "https://github.com/logicmonitor/lm-logs-azure/raw/master/package/lm-logs-azure.zip"
    LogsEventHubConnectionString = azurerm_eventhub_authorization_rule.lm_logs_listener.primary_connection_string
    LogicMonitorCompanyName      = var.lm_company_name
    LogicMonitorAccessId         = var.lm_access_id
    LogicMonitorAccessKey        = var.lm_access_key

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
