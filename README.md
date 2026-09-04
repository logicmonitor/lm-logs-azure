# lm-logs-azure

> ⚠️ **Deprecation Notice**
>
> Support for the **Linux Diagnostic Extension (LAD)** on Linux VMs and **Windows Diagnostic Extension (WAD)** on Windows VMs is scheduled to be **fully deprecated on March 31, 2026**. Please **migrate to the Azure Monitor Agent (AMA)** to ensure ongoing support and compatibility. See our [AMA Deployment For Linux Guide](./vm-config/ama-linux-deployment.md) and [AMA Deployment For Windows Guide](./vm-config/ama-windows-deployment.md).


Azure integration for sending logs to LogicMonitor.
It's implemented as [Azure Function](https://azure.microsoft.com/en-us/services/functions/) consuming logs from an [Event Hub](https://azure.microsoft.com/en-us/services/event-hubs/), and forwarding them to LogicMonitor log ingestion REST API.

## Prerequisites

* [Create a LogicMonitor API Token](https://www.logicmonitor.com/support/settings/users-and-roles/api-tokens)
* [Install Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest)
* [Sign to Azure in with Azure CLI](https://docs.microsoft.com/en-us/cli/azure/authenticate-azure-cli?view=azure-cli-latest): execute `az login`

## Deployment

Each Azure region requires a separate deployment. This is because devices can only send logs to Event Hubs within the same region.

### Deploying using Terraform

* Download [deploy.tf file](https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/deploy.tf)
* Choose Event Hub mode:
  * **Create (default):** leave `use_existing_event_hub=false`. Optionally set `event_hub_name` / `event_hub_consumer_group` (defaults: `log-hub`, `$Default`).
  * **Reuse existing:** set `use_existing_event_hub=true` and provide `existing_event_hub_resource_group`, `existing_event_hub_namespace`, `event_hub_name`, `event_hub_consumer_group`, and `existing_event_hub_authorization_rule` (default `listener` at `EventHub` scope). For Activity Logs, set `enable_activity_logs=true` and `existing_event_hub_send_authorization_rule` (Send). Deployment fails if required resources/params are missing.
* (optional) Update `app_settings` in the file to set the optional parameters
* Exceute `terraform init`
* Execute `terraform plan --var-file terraform.tfvars -out tf.plan`
* Execute `terraform apply tf.plan`

*NOTE: the deployed function usually doesn't start, please see* [this issue](https://github.com/terraform-providers/terraform-provider-azurerm/issues/8546) *for the details.*<br>
*As a workaround, please restart the Function App on the Azure Portal.*

### Deploying using ARM

Parent template: `arm-template-deployment/deployRGParent.json`.

* **Create (default):** `Use_Existing_Event_Hub=No`. Creates namespace `lm-logs-<company>-<region>`, hub `Event_Hub_Name`, and consumer group when not `$Default`.
* **Reuse existing:** `Use_Existing_Event_Hub=Yes` and set:
  * `Existing_Event_Hub_Resource_Group`
  * `Existing_Event_Hub_Namespace`
  * `Event_Hub_Name`
  * `Event_Hub_Consumer_Group`
  * `Existing_Event_Hub_Authorization_Rule` (default `listener`, Listen only)
  * `Existing_Event_Hub_Auth_Rule_Scope` (default `EventHub`)
  * For Activity Logs: set `Enable_Activity_Logs=Yes` **and** `Existing_Event_Hub_Send_Authorization_Rule` (Send). Activity Logs do **not** use the Function Listen rule and do **not** assume `RootManageSharedAccessKey`.

In reuse mode the template does **not** create Event Hub resources. It validates the namespace, hub, consumer group, and auth rule via `reference`/`listKeys`. Missing resources or incomplete parameters fail the deployment. Function settings `LogsEventHubConnectionString`, `EventHubName`, and `EventHubConsumerGroup` are wired to the existing hub.

### Upgrading an existing Function App

The published zip binds `log-hub` / `$Default` (same as today). Existing apps that only have `LogsEventHubConnectionString` **keep working** when the zip is updated — no new app settings required.

Custom Event Hub **name**: use a **hub-level** Listen connection string (`EntityPath=...`). Azure overrides the trigger hub name from the connection string. Create mode and reuse-with-`listener` already do this. A namespace-level connection string has no EntityPath; the Function stays on `log-hub`.

Custom **consumer group on the Function**: the default zip always uses `$Default` (it still receives the same events as any other group). To make the Function itself join a named group, rebuild with `./gradlew azureFunctionsPackageZip -PeventHubAppSettings=true` and set `EventHubName` / `EventHubConsumerGroup` before deploying that zip.

Optional: `LM_FAIL_CLOSED_ON_INGEST=true` fails the Function on incomplete LM ingest so Event Hub retries (possible duplicates). Default `false` keeps prior behavior (log and checkpoint; possible loss).

### Deploying using Gradle

#### Azure configuration

Gradle plugin can only build the function package and deploy it to Azure. Before it can be used, you need to create an [Event Hub](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-create) and [Function App](https://docs.microsoft.com/en-us/azure/azure-functions/functions-create-function-app-portal).
The runtime stack should be set to Java version 11. The function uses the following [Application settings](https://docs.microsoft.com/en-us/azure/azure-functions/functions-how-to-use-azure-function-app-settings#settings)
* `LogsEventHubConnectionString` - Event Hub [connection string](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-get-connection-string)
* `EventHubName` - Optional. ARM/TF set this; the default zip trigger is `log-hub`. A hub-level connection string EntityPath overrides the trigger name.
* `EventHubConsumerGroup` - Optional. The default zip trigger is `$Default`. Named Function consumer groups require `-PeventHubAppSettings=true`.
* `LogicMonitorCompanyName` - Company in the target URL '{company}.logicmonitor.com'
* `LogicMonitorAccessId` - LogicMonitor access ID
* `LogicMonitorAccessKey` - LogicMonitor access key
* `AzureClientID` - Azure Application Client ID
* `LogApiClientConnectTimeout` (optional) - Connection timeout in milliseconds (default 10000)
* `LogApiClientReadTimeout` (optional) - Read timeout in milliseconds (default 10000)
* `LogApiClientDebugging` (optional) - HTTP client debugging: true/false (default false)
* `LogRegexScrub` (optional) - regex pattern for removing text from the log messages
* `LM_FAIL_CLOSED_ON_INGEST` (optional) - true/false (default false). See upgrade section above.

The default zip does not require `EventHubName` / `EventHubConsumerGroup`. See the upgrade section above.

#### Deployment

* execute `./gradlew -DazureResourceGroup=<your Azure Function's Resource Group name> -DazureFunction=<your Azure Function name> azureFunctionsDeploy`
* if your account has multiple subscriptions, you need to add `-DazureSubscription=<subscription ID>`

#### Running locally

Gradle can be also run the function locally for debugging purposes.

* Install [Azure Functions Core Tools 2.0 and above](https://www.npmjs.com/package/azure-functions-core-tools)
* copy the application settings to `local.settings.json` file
* execute `./gradlew azureFunctionsRun`
* you can use remote debugging on port 5005 (it can be modified in `build.gradle` file, setting `localDebug`)

## Logging

Logging type and level can be configured using [Azure CLI webapp log config](https://docs.microsoft.com/en-us/cli/azure/webapp/log?view=azure-cli-latest#az-webapp-log-config) command, for example:

`az webapp log config --resource-group <your Azure Function's Resource Group name> --name <your Azure Function name> --application-logging true --level verbose --detailed-error-messages true`

Then they can be observed using [Azure CLI webapp log tail](https://docs.microsoft.com/en-us/cli/azure/webapp/log?view=azure-cli-latest#az-webapp-log-tail)

`az webapp log tail --resource-group <your Azure Function's Resource Group name> --name <your Azure Function name>`

## Forwarding Azure logs to Event Hub

After the deployment is complete, the Azure function listens for logs from the Event Hub. We need to redirect them there from resources.
For most of them, this can be done by [creating diagnostic settings](https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostic-settings). If the function was deployed using Terraform or ARM in **create** mode, send logs to the configured Event Hub (default name `log-hub`) in namespace `lm-logs-<LM company name>-<Azure region>`. In **reuse** mode, send logs to the existing Event Hub / namespace you configured.

### Linux Virtual Machines (using Linux Diagnostic Extension (LAD))

Forwarding Linux VM's system and application logs requires [installation of diagnostic extension](https://docs.microsoft.com/en-us/azure/virtual-machines/extensions/diagnostics-linux#installing-the-extension-in-your-vm) on the machine.

#### Prerequisites

* [Install Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest)
* [Sign to Azure in with Azure CLI](https://docs.microsoft.com/en-us/cli/azure/authenticate-azure-cli?view=azure-cli-latest): execute `az login`
* Install wget: execute `sudo apt-get install wget`.

#### Configuration

* Download the configuration script: `wget https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/vm-config/configure-lad.sh`
* execute it to create the storage account needed by the extension, and the configuration files: `./configure-lad.sh <LM company name>`
* update `lad_public_settings.json` to configure types of system logs and their levels (`syslogEvents`), and application logs (`fileLogs`) to collect
* execute `az vm extension set --publisher Microsoft.Azure.Diagnostics --name LinuxDiagnostic --version 3.0 --resource-group <your VM's Resource Group name> --vm-name <your VM name> --protected-settings lad_protected_settings.json --settings lad_public_settings.json` - the exact command was printed by the `configure-lad.sh` script

### Windows Virtual Machines 

Forwarding Windows VM's system and application logs requires [installation of diagnostic extension](https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostics-extension-windows-install) on the machine.

#### Prerequisites

* [Install Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest)
* Install Azure CLI via PowerShell:
`Invoke-WebRequest -Uri https://aka.ms/installazurecliwindows -OutFile .\AzureCLI.msi; Start-Process msiexec.exe -Wait -ArgumentList '/I AzureCLI.msi /quiet'; rm .\AzureCLI.msi`
* [Sign to Azure in with Azure CLI](https://docs.microsoft.com/en-us/cli/azure/authenticate-azure-cli?view=azure-cli-latest): execute `az login`

#### Configuration

* Download the configuration script: `Invoke-WebRequest -Uri https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/vm-config/configure-wad.ps1 -OutFile .\configure-wad.ps1`
* execute it to create the storage account needed by the extension, and the configuration files: `.\configure-wad.ps1 -lm_company_name <LM company name>`
* update `wad_public_settings.json` to configure types of [event logs](https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostics-extension-schema-windows#windowseventlog-element) (`Applicaiton, System, Setup, Security, etc`) and their levels (`Info, Warning, Critical`) to collect
* execute `az vm extension set --publisher Microsoft.Azure.Diagnostics --name IaaSDiagnostics --version 1.18 --resource-group <your VM's Resource Group name> --vm-name <your VM name> --protected-settings wad_protected_settings.json --settings wad_public_settings.json` - the exact command was printed by the `configure-wad.ps1` script


