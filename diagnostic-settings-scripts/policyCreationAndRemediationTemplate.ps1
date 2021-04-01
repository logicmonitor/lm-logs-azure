param
(
    [Parameter(Mandatory = $True)]
    [string]$resourceGroup,

    [Parameter(Mandatory =$True)]
    [string]$lmCompanyName,

    [Parameter(Mandatory =$True)]
    [string]$subscriptionId,

    [Parameter(Mandatory = $True)]
    [string]$location

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

    # Establish REST Token
    $azProfile = [Microsoft.Azure.Commands.Common.Authentication.Abstractions.AzureRmProfileProvider]::Instance.Profile
    $profileClient = New-Object -TypeName Microsoft.Azure.Commands.ResourceManager.Common.RMProfileClient -ArgumentList ($azProfile)
    $token = $profileClient.AcquireAccessToken($currentContext.Subscription.TenantId)
}
catch
{
    $null = Login-AzAccount -Environment $Environment
    $AzureLogin = Get-AzSubscription
    $currentContext = Get-AzContext

    # Establish REST Token
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

$targetResourceGroup = 'lm-logs-' + $lmCompanyName + '-' + $location + '-group'
$eventhubNameSpace = $targetResourceGroup.replace('-group','')
$eventhubName = 'log-hub'
$eventhubAuthorizationId = 'RootManageSharedAccessKey'

Get-AzResourceGroup -Name $targetResourceGroup -ErrorVariable notPresent -ErrorAction SilentlyContinue
if ($notPresent) {
    Write-Host "target resource group not present..."
    exit;
}
else{
    New-AzDeployment -TemplateUri "https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/arm-template-deployment/ARMTemplateExport.json" -Location "West US" -Verbose
    $policyAssignments = ./policyAssignment.ps1 -resourceGroup $resourceGroup -location $location -eventhubName $eventhubName -eventhubNameSpace $eventhubNameSpace -eventhubAuthorizationId $eventhubAuthorizationId -targetResourceGroup $targetResourceGroup
    Write-Host "Running compliance result for $($policyAssignments.PolicyAssignmentId)" -ForegroundColor Cyan
    Start-AzPolicyComplianceScan -ResourceGroupName $policyAssignments.ResourceGroupName
    Start-Sleep -s 30
    $Null = New-AzRoleAssignment -ObjectId $policyAssignments.Identity.principalId  -RoleDefinitionName Contributor
    Start-Sleep -s 20
    ./policyInitiativeRemediation.ps1 -force -SubscriptionId $subscriptionId -PolicyAssignmentId $policyAssignments.PolicyAssignmentId -ResourceGroupName $policyAssignments.ResourceGroupName
}

