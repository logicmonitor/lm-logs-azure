param
(
    [Parameter(Mandatory = $True)]
    [string]$resourceGroup,

    [Parameter(Mandatory = $True)]
    [string]$location,

    [Parameter(Mandatory = $True)]
    [string]$eventhubName,

    [Parameter(Mandatory = $True)]
    [string]$eventhubNameSpace,

    [Parameter(Mandatory = $True)]
    [string]$eventhubAuthorizationId,

    [Parameter(Mandatory =$True)]
    [string]$targetResourceGroup
)

$definition = Get-AzPolicySetDefinition | Where-Object { $_.Properties.DisplayName -eq 'Azure Diagnostics Policy Initiative to LM' }

Write-Host "Resolving Event Hub for diagnostic policies: rg=$targetResourceGroup ns=$eventhubNameSpace hub=$eventhubName rule=$eventhubAuthorizationId"

$eventHubNamespaceId = Get-AzEventHubNamespace -ResourceGroupName $targetResourceGroup -NamespaceName $eventhubNameSpace
$eventHubId = Get-AzEventHub -ResourceGroupName $targetResourceGroup -NamespaceName $eventhubNameSpace -EventHubName $eventhubName
# Namespace-level Send rule (Activity Logs / diagnostic settings require namespace auth rule id)
$eventHubAuthorizationIdParam = Get-AzEventHubAuthorizationRule -ResourceGroupName $targetResourceGroup -NamespaceName $eventhubNameSpace -Name $eventhubAuthorizationId

$eventHubParam = @{
    'eventHubName' = ($eventHubId.Id)
    'eventHubRuleId' = ($eventHubAuthorizationIdParam.Id)
    'azureRegions' = (-split $location)
    'profileName' = ($resourceGroup)
    'metricsEnabled' = ('True')
}
$resource = Get-AzResourceGroup -Name $resourceGroup

$existingAssignment = Get-AzPolicyAssignment -Name $resourceGroup -Scope $resource.ResourceId -ErrorAction SilentlyContinue
if ($existingAssignment) {
    Write-Host "Updating existing policy assignment '$resourceGroup' with new Event Hub parameters" -ForegroundColor Cyan
    try {
        $assignment = Set-AzPolicyAssignment -Id $existingAssignment.PolicyAssignmentId -PolicyParameterObject $eventHubParam -ErrorAction Stop
    } catch {
        # Az 5.6 / older modules may expose ResourceId instead of PolicyAssignmentId
        $assignmentId = $existingAssignment.PolicyAssignmentId
        if ([string]::IsNullOrWhiteSpace($assignmentId)) { $assignmentId = $existingAssignment.ResourceId }
        $assignment = Set-AzPolicyAssignment -Id $assignmentId -PolicyParameterObject $eventHubParam
    }
} else {
    Write-Host "Creating policy assignment '$resourceGroup'" -ForegroundColor Cyan
    $assignment = New-AzPolicyAssignment -Name $resourceGroup -DisplayName $resourceGroup -Scope $resource.ResourceId -PolicySetDefinition $definition -Location $location -PolicyParameterObject $eventHubParam -AssignIdentity
}

return $assignment
