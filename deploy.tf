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

### Terraform Setup ###
terraform {
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.50.0"
    }
  }
}


### Providers ###
provider "azurerm" {
  features {}
}

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

variable "lm_apiClientDebug" {
  type = bool
  description = "(optional) Enable API debugging"
  default = false
}

variable "lm_logLevel" {
  type        = string
  description = "(optional) LM Log App log level"
  default     = "WARNING"
  validation {
    condition = contains([
      "TRACE",
      "DEBUG",
      "INFORMATION",
      "WARNING",
      "ERROR",
      "CRITICAL",
      "NONE",
    ], upper(var.lm_logLevel) )
    error_message = "lm_logLevel must be one of `TRACE`,`DEBUG`,`INFORMATION`,`WARNING`,`ERROR`,`CRITICAL`,`NONE`"
  }
}

variable "lm_logRegexScrubPattern" {
  type        = string
  description = "(optional) Regex scrub string"
  default     = null
}

variable "lm_sourceCodeBranch" {
  type        = string
  description = "(optional) Code branch to deploy lm azure app from."
  default     = "master"
}

variable "lm_metadataKeys" {
  type        = string
  description = "(Optional) Metadata keys to include in records"
  default     = "resourceId"
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

  lm_auth_string      = "{ \"LM_ACCESS_ID\" : \"${var.lm_access_id}\", \"LM_ACCESS_KEY\" : \"${var.lm_access_key}\", \"LM_BEARER_TOKEN\" : \"\" }"
  lm_package_url      = "https://github.com/logicmonitor/lm-logs-azure/raw/${var.lm_sourceCodeBranch}/package/lm-logs-azure.zip"
  lm_web_job_storage  = "DefaultEndpointsProtocol=https;AccountName=${azurerm_storage_account.lm_logs.name};AccountKey=${azurerm_storage_account.lm_logs.primary_access_key}"
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

## Service Plan ##
resource "azurerm_service_plan" "lm_logs" {
  name                = "${local.namespace}-service-plan"
  resource_group_name = azurerm_resource_group.lm_logs.name
  location            = var.azure_region
  os_type             = "Linux"
  sku_name            = "S1" 
  tags                = local.tags

}

## Linux Function App ##
resource "azurerm_linux_function_app" "lm_logs" {
  name                       = local.namespace
  resource_group_name        = azurerm_resource_group.lm_logs.name
  location                   = var.azure_region
  service_plan_id            = azurerm_service_plan.lm_logs.id
  storage_account_name       = azurerm_storage_account.lm_logs.name
  storage_account_access_key = azurerm_storage_account.lm_logs.primary_access_key
  https_only                 = true
  tags                       = local.tags

  site_config {
    always_on         = true
    use_32_bit_worker = false
    http2_enabled     = true

    application_stack{
      java_version  = 11
    }
  }

  app_settings = {
    APPLICATION_NAME                = "lm-logs-azure"
    AzureClientID                   = var.azure_client_id
    AzureWebJobsStorage             = local.lm_web_job_storage
    FUNCTION_APP_EDIT_MODE          = "readwrite"
    FUNCTIONS_WORKER_RUNTIME        = "java"
    FUNCTIONS_WORKER_PROCESS_COUNT  = 1
    Include_Metadata_keys           = var.lm_metadataKeys
    LogsEventHubConnectionString    = azurerm_eventhub_authorization_rule.lm_logs_listener.primary_connection_string
    LM_COMPANY                      = var.lm_company_name
    LM_AUTH                         = local.lm_auth_string
    LogApiClientConnectTimeout      = 10000
    LogApiClientReadTimeout         = 10000
    LogApiClientDebugging           = var.lm_apiClientDebug
    LOG_LEVEL                       = var.lm_logLevel
    LogRegexScrub                   = var.lm_logRegexScrubPattern
    WEBSITE_RUN_FROM_PACKAGE        = local.lm_package_url
  }
}

### Misc ###
resource "null_resource" "restart_function_app_after_2_minutes" {
  provisioner "local-exec" {
    command = "sleep 120 && az functionapp restart --resource-group ${azurerm_resource_group.lm_logs.name} --name ${azurerm_function_app.lm_logs.name}"
  }
}
