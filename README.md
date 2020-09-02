# lm-logs-azure(beta)

Azure integration for sending logs to LogicMonitor.
It's implemented as [Azure Function](https://azure.microsoft.com/en-us/services/functions/) consuming logs from an [Event Hub](https://azure.microsoft.com/en-us/services/event-hubs/), and forwarding them to LogicMonitor log ingestion REST API.

## Prerequisites

Azure configuration
* [Create an Event Hub](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-create) - Visual Studio and .NET SDK are NOT needed,
* [Forward logs to the Event Hub](https://docs.microsoft.com/en-us/azure/azure-monitor/platform/stream-monitoring-data-event-hubs)

LogicMonitor configuration
* [Create an API Token](https://www.logicmonitor.com/support/settings/users-and-roles/api-tokens)

Required by [Azure Functions gradle plugin](https://github.com/lenala/azure-gradle-plugins#azure-functions-plugin)
* [Azure Functions Core Tools 2.0 and above](https://www.npmjs.com/package/azure-functions-core-tools) to run the function locally,
* [Azure CLI](https://docs.microsoft.com/en-us/cli/azure/install-azure-cli?view=azure-cli-latest) to deploy the function.

## Configuration

[Application settings](https://docs.microsoft.com/en-us/azure/azure-functions/functions-how-to-use-azure-function-app-settings#settings)
* `LogsEventHubConnectionString` - Event Hub [connection string](https://docs.microsoft.com/en-us/azure/event-hubs/event-hubs-get-connection-string)
* `LogicMonitorCompanyName` - Company in the target URL '{company}.logicmonitor.com'
* `LogicMonitorAccessId` - LogicMonitor access ID
* `LogicMonitorAccessKey` - LogicMonitor access key
* `LogApiClientConnectTimeout` (optional) - Connection timeout in milliseconds (default 10000)
* `LogApiClientReadTimeout` (optional) - Read timeout in milliseconds (default 10000)
* `LogApiClientDebugging` (optional) - HTTP client debugging: true/false (default false)
* `LogRegexScrub` (optional) - regex pattern for removing text from the log messages

[host.json](https://docs.microsoft.com/en-us/azure/azure-functions/functions-bindings-event-hubs-trigger?tabs=csharp#functions-2x-and-higher) - file containing Event Hub consumer settings

## Running

Deploying to Azure
* login to Azure using `az login` command
* execute `./gradlew -DazureResourceGroup=<your Azure Function's Resource Group name> -DazureFunction=<your Azure Function name> azureFunctionsDeploy`
* if your account has multiple subscriptions, you need to add `-DazureSubscription=<subscription ID>`

Running locally
* copy the application settings to `local.settings.json` file
* execute `./gradlew -DazureFunction=<your Azure Function name> azureFunctionsRun`

## Logging

Logging type and level can be configured using [Azure CLI webapp log config command](https://docs.microsoft.com/en-us/cli/azure/webapp/log?view=azure-cli-latest#az-webapp-log-config), for example:

`az webapp log config --resource-group <your Azure Function's Resource Group name> --name <your Azure Function name> --application-logging true --level verbose --detailed-error-messages true`

Then they can be observed using [Azure CLI webapp log tail](https://docs.microsoft.com/en-us/cli/azure/webapp/log?view=azure-cli-latest#az-webapp-log-tail)

`az webapp log tail --resource-group <your Azure Function's Resource Group name> --name <your Azure Function name>`

