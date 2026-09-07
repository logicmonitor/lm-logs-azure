param
(
    [Parameter(Mandatory = $True)]
    [string]$resourceGroup,

    [Parameter(Mandatory =$True)]
    [string]$lmCompanyName,

    [Parameter(Mandatory =$True)]
    [string]$subscriptionId,

    [Parameter(Mandatory = $True)]
    [string]$location,

    [Parameter(Mandatory = $True)]
    [string]$sourceCodeBranch,

    # Must align with deployRGParent Event_Hub_Name / reuse fields
    [Parameter(Mandatory = $False)]
    [string]$eventHubName = "log-hub",

    [Parameter(Mandatory = $False)]
    [string]$eventHubNamespace = "",

    [Parameter(Mandatory = $False)]
    [string]$eventHubResourceGroup = "",

    [Parameter(Mandatory = $False)]
    [string]$eventHubAuthorizationRule = "RootManageSharedAccessKey"
)

Write-Host $resourceGroup
Write-Host $location
If($ADO){write-host "ADO switch deprecated and no longer necessary" -ForegroundColor Yellow}
Write-Host "Authenticating to Azure..." -ForegroundColor Cyan

$Environment = "Azure Cloud"
try
{
    $AzureLogin = Get-AzSubscription
    $currentContext = Get-AzContext

    $azProfile = [Microsoft.Azure.Commands.Common.Authentication.Abstractions.AzureRmProfileProvider]::Instance.Profile
    $profileClient = New-Object -TypeName Microsoft.Azure.Commands.ResourceManager.Common.RMProfileClient -ArgumentList ($azProfile)
    $token = $profileClient.AcquireAccessToken($currentContext.Subscription.TenantId)
}
catch
{
    $null = Login-AzAccount -Environment $Environment
    $AzureLogin = Get-AzSubscription
    $currentContext = Get-AzContext

    $azProfile = [Microsoft.Azure.Commands.Common.Authentication.Abstractions.AzureRmProfileProvider]::Instance.Profile
    $profileClient = New-Object -TypeName Microsoft.Azure.Commands.ResourceManager.Common.RMProfileClient -ArgumentList ($azProfile)
    $token = $profileClient.AcquireAccessToken($currentContext.Subscription.TenantId)
}

Try
{
    $Subscription = Get-AzSubscription -SubscriptionId $subscriptionId
}
catch
{
    Write-Host "Subscription not found"
    break
}

$lmResourceGroup = 'lm-logs-' + $lmCompanyName + '-' + $location + '-group'
$lmEventHubNamespace = $lmResourceGroup.Replace('-group','')
$functionAppName = $lmEventHubNamespace

if ([string]::IsNullOrWhiteSpace($eventHubName)) { $eventHubName = "log-hub" }
if ([string]::IsNullOrWhiteSpace($eventHubAuthorizationRule)) { $eventHubAuthorizationRule = "RootManageSharedAccessKey" }

Get-AzResourceGroup -Name $lmResourceGroup -ErrorVariable notPresent -ErrorAction SilentlyContinue
if ($notPresent) {
    Write-Host "LM target resource group not present: $lmResourceGroup"
    exit;
}

# Optional sync from Function App (updated by deployRGParent) so default→custom redeploy retargets diagnostics
$faHubName = $null
$faNamespace = $null
try {
    $appSettingsResource = Get-AzResource -ResourceType "Microsoft.Web/sites/config" -ResourceGroupName $lmResourceGroup -Name "$functionAppName/appsettings" -ApiVersion "2022-03-01" -ErrorAction SilentlyContinue
    if ($appSettingsResource -and $appSettingsResource.Properties) {
        $props = $appSettingsResource.Properties
        if ($props.EventHubName) { $faHubName = [string]$props.EventHubName }
        $faConn = [string]$props.LogsEventHubConnectionString
        if ($faConn -and $faConn -match 'Endpoint=sb://([^.]+)\.servicebus') {
            $faNamespace = $Matches[1]
        }
    }
} catch {
    Write-Host "Function App Event Hub sync skipped: $($_.Exception.Message)" -ForegroundColor Yellow
}

# Resolution precedence:
# - Hub name: explicit non-default ARM param > Function App EventHubName > ARM/default log-hub
# - Namespace / RG / auth: explicit ARM (reuse) > Function namespace from connection string > LM create-mode defaults
$resolvedHubName = $eventHubName
if ($eventHubName -eq "log-hub" -and -not [string]::IsNullOrWhiteSpace($faHubName)) {
    $resolvedHubName = $faHubName
    Write-Host "Event hub name synced from Function App setting EventHubName=$faHubName" -ForegroundColor Cyan
} elseif ($eventHubName -ne "log-hub") {
    Write-Host "Event hub name from template parameter: $eventHubName" -ForegroundColor Cyan
}

if (-not [string]::IsNullOrWhiteSpace($eventHubNamespace)) {
    $resolvedHubNamespace = $eventHubNamespace
} elseif (-not [string]::IsNullOrWhiteSpace($faNamespace)) {
    $resolvedHubNamespace = $faNamespace
    Write-Host "Event Hub namespace synced from Function connection string: $faNamespace" -ForegroundColor Cyan
} else {
    $resolvedHubNamespace = $lmEventHubNamespace
}

if (-not [string]::IsNullOrWhiteSpace($eventHubResourceGroup)) {
    $resolvedHubRg = $eventHubResourceGroup
} else {
    $resolvedHubRg = $lmResourceGroup
}

$resolvedAuthRule = $eventHubAuthorizationRule

Write-Host "Diagnostic Event Hub target:" -ForegroundColor Cyan
Write-Host "  resourceGroup=$resolvedHubRg"
Write-Host "  namespace=$resolvedHubNamespace"
Write-Host "  eventHub=$resolvedHubName"
Write-Host "  authRule=$resolvedAuthRule (namespace Send)"

$templateUri = 'https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/'+$sourceCodeBranch+'/arm-template-deployment/ARMTemplateExport.json'

New-AzDeployment -TemplateUri $templateUri -Location "West US" -Verbose
$policyAssignments = ./policyAssignment.ps1 -resourceGroup $resourceGroup -location $location -eventhubName $resolvedHubName -eventhubNameSpace $resolvedHubNamespace -eventhubAuthorizationId $resolvedAuthRule -targetResourceGroup $resolvedHubRg
Write-Host "Running compliance result for $($policyAssignments.PolicyAssignmentId)" -ForegroundColor Cyan
Start-AzPolicyComplianceScan -ResourceGroupName $policyAssignments.ResourceGroupName
Start-Sleep -s 30
try {
    $Null = New-AzRoleAssignment -ObjectId $policyAssignments.Identity.principalId -RoleDefinitionName Contributor -ErrorAction Stop
} catch {
    Write-Host "Contributor role assignment note: $($_.Exception.Message)" -ForegroundColor Yellow
}
Start-Sleep -s 20
./policyInitiativeRemediation.ps1 -force -SubscriptionId $subscriptionId -PolicyAssignmentId $policyAssignments.PolicyAssignmentId -ResourceGroupName $policyAssignments.ResourceGroupName
