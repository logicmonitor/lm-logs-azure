#
# Copyright (C) 2020 LogicMonitor, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.
#
Param (
   [Parameter(Mandatory = $true)]
   [string] $lm_company_name
)

Write-Host "[Info]:Collecting VM Info" -ForegroundColor White -BackgroundColor Black
$vm_info = ((Invoke-WebRequest -Uri 169.254.169.254/metadata/instance?api-version=2017-08-01 -Headers @{"Metadata"="true"}).Content | ConvertFrom-Json).compute

$subscription_id = $vm_info.subscriptionId
Write-Host "[Info]:subscription id = $subscription_id" -ForegroundColor White -BackgroundColor Black
$vm_resource_group= $vm_info.resourceGroupName
Write-Host "[Info]:resource group = $vm_resource_group" -ForegroundColor White -BackgroundColor Black
$vm_name= $vm_info.name
Write-Host "[Info]:vm name = $vm_name" -ForegroundColor White -BackgroundColor Black
$location= $vm_info.location
Write-Host "[Info]:location = $location" -ForegroundColor White -BackgroundColor Black

$vm_resource_id=$(az vm show --subscription $subscription_id -g $vm_resource_group -n $vm_name --query "id" -o tsv)
if ($vm_resource_id){
    Write-Host "[Info]:resource id = $vm_resource_id" -ForegroundColor White -BackgroundColor Black
}
else {
    Write-Host "[Error]:can't determine resource id" -ForegroundColor Red -BackgroundColor Black
    exit -1
}

$event_hub_namespace="lm-logs-$lm_company_name-$location"
$event_hub_group="$event_hub_namespace-group"
$event_hub_name="log-hub"
$event_hub_auth_name="sender"

Write-Host "[Info]:reading the event hub authorization key:" -ForegroundColor White -BackgroundColor Black
Write-Host "    resource group = $event_hub_group"  -ForegroundColor White -BackgroundColor Black
Write-Host "    event hub = $event_hub_namespace/$event_hub_name" -ForegroundColor White -BackgroundColor Black
Write-Host "    auth name = $event_hub_auth_name" -ForegroundColor White -BackgroundColor Black
$event_hub_auth_key= $(az eventhubs eventhub authorization-rule keys list --subscription $subscription_id --resource-group $event_hub_group --namespace-name $event_hub_namespace --eventhub-name $event_hub_name --name $event_hub_auth_name --query "primaryKey" -o tsv)
if (!$event_hub_auth_key) {
    Write-Host "[Error]:can't read the authorization key" -ForegroundColor Red -BackgroundColor Black
    exit -1
}

$event_hub_uri="https://$event_hub_namespace.servicebus.windows.net/$event_hub_name"
Write-Host "[Info]:generating the event hub sas uri" -ForegroundColor White -BackgroundColor Black

$storage_name=($("diag$location$vm_name") -replace "\W").ToLower()
$storage_group=$vm_resource_group
if ($storage_name.Length -gt 24){
    $storage_name=$storage_name.substring(0, 24)
}

Write-Host "[Info]:checking if the storage account exists:" -ForegroundColor White -BackgroundColor Black
Write-Host "    resource group = $storage_group" -ForegroundColor White -BackgroundColor Black
Write-Host "    storage account = $storage_name" -ForegroundColor White -BackgroundColor Black
if ($storage_name -ne $(az storage account show --subscription $subscription_id -g $storage_group -n $storage_name --query name -o tsv)){
    Write-Host "[Info]:creating the storage account" -ForegroundColor White -BackgroundColor Black
    $storage_account = $(az storage account create --subscription $subscription_id -g $storage_group -n $storage_name -l $location --sku Standard_LRS)
    if(!$?){
        Write-Host "[Error]:couldn't create the storage account" -ForegroundColor Red -BackgroundColor Black
        exit -1
    }
}

Write-Host "[Info]:reading the storage account access key" -ForegroundColor White -BackgroundColor Black
$storage_account_key=$(az storage account keys list --subscription $subscription_id -g $storage_group -n $storage_name --query [0].value -o tsv)
if (!$?){
    Write-Host "[Error]:couldn't read the storage account access key" -ForegroundColor Red -BackgroundColor Black
    exit -1
}


Try{
    Write-Host "[Info]:writing the protected settings" -ForegroundColor White -BackgroundColor Black
    $wad_protected_settings = (Invoke-WebRequest -Uri https://raw.githubusercontent.com/ckcompton/lm-logs-azure/master/vm-config/wad_protected_settings.json).Content | Out-File wad_protected_settings.json

    (Get-Content wad_protected_settings.json).Replace('__DIAGNOSTIC_STORAGE_ACCOUNT__', $storage_name) | Set-Content wad_protected_settings.json
    (Get-Content wad_protected_settings.json).Replace('__DIAGNOSTIC_STORAGE_KEY__', $storage_account_key) | Set-Content wad_protected_settings.json
    (Get-Content wad_protected_settings.json).Replace('__LOGS_EVENT_HUB_URI__', $event_hub_uri) | Set-Content wad_protected_settings.json
    (Get-Content wad_protected_settings.json).Replace('__LOGS_EVENT_HUB_ACCESS_KEY__', $event_hub_auth_key) | Set-Content wad_protected_settings.json
}
Catch{
    Write-Host "[Error]:Unable to write configuration file for protected settings" -ForegroundColor Red -BackgroundColor Black
}

Try{
    Write-Host "[Info]:writing the public settings" -ForegroundColor White -BackgroundColor Black
    $wad_public_settings = (Invoke-WebRequest -Uri https://raw.githubusercontent.com/ckcompton/lm-logs-azure/master/vm-config/wad_public_settings.json).Content | Out-File wad_public_settings.json
    
    (Get-Content wad_public_settings.json).Replace('__LOGS_EVENT_HUB_URI__', $event_hub_uri) | Set-Content wad_public_settings.json
    (Get-Content wad_public_settings.json).Replace('__DIAGNOSTIC_STORAGE_ACCOUNT__', $storage_name) | Set-Content wad_public_settings.json
    (Get-Content wad_public_settings.json).Replace('__VM_RESOURCE_ID__', $vm_resource_id) | Set-Content wad_public_settings.json
}
Catch {
    Write-Host "[Error]:Unable to write configuration file for public settings" -ForegroundColor Red -BackgroundColor Black
}

Write-Host "[Info]:update your logging settings in wad_public_settings.json and execute the following command:" -ForegroundColor White -BackgroundColor Black
Write-Host "    az vm extension set --publisher Microsoft.Azure.Diagnostics --name IaaSDiagnostics --version 1.18 --resource-group $vm_resource_group --vm-name $vm_name --protected-settings wad_protected_settings.json --settings wad_public_settings.json" -ForegroundColor White -BackgroundColor Black
