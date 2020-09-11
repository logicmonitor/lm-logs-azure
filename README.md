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
* Execute `terraform apply --var 'lm_company_name=<LM company name>' --var 'lm_access_id=<LM access ID>' --var 'lm_access_key=<LM access key>' --var 'azure_region=<region>'`

### Deploying using Gradle

#### Azure configuration

Gradle plugin can only build the function package and deploy it to Azure. Before it can be used, you need to create an [Event Hub](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-create) and [Function App](https://docs.microsoft.com/en-us/azure/azure-functions/functions-create-function-app-portal).
The runtime stack should be set to Java version 11. The function uses the following [Application settings](https://docs.microsoft.com/en-us/azure/azure-functions/functions-how-to-use-azure-function-app-settings#settings)
* `LogsEventHubConnectionString` - Event Hub [connection string](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-get-connection-string)
* `LogicMonitorCompanyName` - Company in the target URL '{company}.logicmonitor.com'
* `LogicMonitorAccessId` - LogicMonitor access ID
* `LogicMonitorAccessKey` - LogicMonitor access key
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

