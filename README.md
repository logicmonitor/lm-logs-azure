# lm-logs-azure(beta)

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
* (optional) Update `app_settings` in the file to set the optional parameters
* Exceute `terraform init`
* Execute `terraform plan --var-file terraform.tfvars -out tf.plan`
* Execute `terraform apply tf.plan`

*NOTE: the deployed function usually doesn't start, please see* [this issue](https://github.com/terraform-providers/terraform-provider-azurerm/issues/8546) *for the details.*<br>
*As a workaround, please restart the Function App on the Azure Portal.*

### Deploying using Gradle

#### Azure configuration

Gradle plugin can only build the function package and deploy it to Azure. Before it can be used, you need to create an [Event Hub](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-create) and [Function App](https://docs.microsoft.com/en-us/azure/azure-functions/functions-create-function-app-portal).
The runtime stack should be set to Java version 11. The function uses the following [Application settings](https://docs.microsoft.com/en-us/azure/azure-functions/functions-how-to-use-azure-function-app-settings#settings)
* `LogsEventHubConnectionString` - Event Hub [connection string](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-get-connection-string)
* `LogicMonitorCompanyName` - Company in the target URL '{company}.logicmonitor.com'
* `LogicMonitorAccessId` - LogicMonitor access ID
* `LogicMonitorAccessKey` - LogicMonitor access key
* `AzureClientID` - Azure Application Client ID
* `LogApiClientConnectTimeout` (optional) - Connection timeout in milliseconds (default 10000)
* `LogApiClientReadTimeout` (optional) - Read timeout in milliseconds (default 10000)
* `LogApiClientDebugging` (optional) - HTTP client debugging: true/false (default false)
* `LogRegexScrub` (optional) - regex pattern for removing text from the log messages

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
For most of them, this can be done by [creating diagnostic settings](https://docs.microsoft.com/en-us/azure/azure-monitor/platform/diagnostic-settings). If the function was deployed using Terraform, the logs should be sent to Event Hub named `log-hub` in namespace `lm-logs-<LM company name>-<Azure region>`.

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

> ⚠️ **Deprecation Notice**
>
> Support for the **Linux Diagnostic Extension (LAD)** on Linux VMs is scheduled to be **fully deprecated on March 31, 2026**. Please **migrate to the Azure Monitor Agent (AMA)** to ensure ongoing support and compatibility. See our [AMA Deployment For Linux Guide](./vm-config/ama-linux-deployment.md).
