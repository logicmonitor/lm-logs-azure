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

#### functions ####

# get_json_attr <JSON> <attribute name>
get_json_attr() {
    if [[ "$1" =~ '"'$2'":'[:space:]*'"'([^'"']+)'"' ]]
    then
        echo "${BASH_REMATCH[1]}"
        return 0
    else
        return 1
    fi
}

# event_hub_sas_token <Event Hub URI> <auth name> <auth key>
event_hub_sas_token() {
    local encoded_uri=$(url_encode $1)
    local expiry=$(date --date='10 years' +%s) # + 10 years
    local utf8_signature=$(printf "%s\n%s" $encoded_uri $expiry | iconv -t utf8)
    local hash=$(echo -n "$utf8_signature" | openssl sha256 -hmac $3 -binary | base64)
    local encoded_hash=$(echo $hash | sed "s#+#%2B#g" | sed "s#/#%2F#g" | sed "s#=#%3D#g")
    echo "sr=$encoded_uri&sig=$encoded_hash&se=$expiry&skn=$2"
    return 0
}

# url_encode <URL to encode>
url_encode() {
    local encoded=$(curl -s -o /dev/null -w %{url_effective} --get --data-urlencode "$1" "")
    echo "${encoded##/?}"
    return $?
}

#### main ####

lm_company_name=$1
if [ -z $lm_company_name ]
then
    echo "usage: $0 <LogicMonitor company name>"
    exit -1
fi

echo "getting the vm information"
vm_metadata=$(curl -s -H Metadata:true 169.254.169.254/metadata/instance?api-version=2017-08-01)
subscription_id=$(get_json_attr $vm_metadata "subscriptionId")
echo -e "\tsubscription id = $subscription_id"
vm_resource_group=$(get_json_attr $vm_metadata "resourceGroupName")
echo -e "\tresource group = $vm_resource_group"
vm_name=$(get_json_attr $vm_metadata "name")
echo -e "\tvm name = $vm_name"
location=$(get_json_attr $vm_metadata "location")
echo -e "\tlocation = $location"

vm_resource_id=$(az vm show --subscription $subscription_id -g $vm_resource_group -n $vm_name --query "id" -o tsv)
if [ -n $vm_resource_id ]
then
    echo -e "\tresource id = $vm_resource_id"
else
    echo "can't determine resource id"
    exit -1
fi

event_hub_namespace="lm-logs-${lm_company_name}-${location}"
event_hub_group="${event_hub_namespace}-group"
event_hub_name="log-hub"
event_hub_auth_name="sender"
echo -e "reading the event hub authorization key\n\tresource group = ${event_hub_group}\n\tevent hub = ${event_hub_namespace}/${event_hub_name}\n\tauth name = ${event_hub_auth_name}"
event_hub_auth_key=$(az eventhubs eventhub authorization-rule keys list --subscription $subscription_id --resource-group ${event_hub_group} --namespace-name $event_hub_namespace --eventhub-name $event_hub_name --name $event_hub_auth_name --query "primaryKey" -o tsv)
if [ -z $event_hub_auth_key ]
then
    echo "can't read the authorization key"
    exit -1
fi
event_hub_uri="https://${event_hub_namespace}.servicebus.windows.net/${event_hub_name}"
echo "generating the event hub sas uri"
event_hub_sas_uri="${event_hub_uri}?$(event_hub_sas_token $event_hub_uri $event_hub_auth_name $event_hub_auth_key)"

storage_name=$(echo "diag${location}${vm_name}" | tr -cd [:alpha:] | tr [:upper:] [:lower:])
storage_group=$vm_resource_group
if [ ${#storage_name} -gt 24 ]
then
    storage_name=${storage_name:$((${#storage_name} - 24))}
fi
echo -e "checking if the storage account exists\n\tresource group = ${storage_group}\n\tstorage account = ${storage_name}"
if [[ "$storage_name" != "$(az storage account show --subscription $subscription_id -g $storage_group -n $storage_name --query name -o tsv)" ]]
then
    echo "creating the storage account"
    az storage account create --subscription $subscription_id -g $storage_group -n $storage_name -l $location --sku Standard_LRS
    if [ $? -ne 0 ]
    then
        echo "couldn't create the storage account"
        exit -1
    fi
fi

echo "generating the storage account sas token"
storage_token_expiry=$(date --date='10 years' '+%Y-%m-%dT%H:%M:%SZ') # + 10 years
storage_account_sas_token=$(az storage account generate-sas --account-name $storage_name --expiry $storage_token_expiry --permissions wlacu --resource-types co --services bt -o tsv)
if [ $? -ne 0 ]
then
    echo "couldn't generate the token"
    exit -1
fi

echo "writing the protected settings"
wget -q --backups=2 https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/vm-config/lad_protected_settings.json
sed -i "s#__DIAGNOSTIC_STORAGE_ACCOUNT__#$storage_name#g" lad_protected_settings.json
sed -i "s#__DIAGNOSTIC_STORAGE_SAS_TOKEN__#${storage_account_sas_token//&/\\&}#g" lad_protected_settings.json

echo "writing the public settings"
sed -i "s#__LOGS_EVENT_HUB_URI__#${event_hub_sas_uri//&/\\&}#g" lad_protected_settings.json
wget -q --backups=2 https://raw.githubusercontent.com/logicmonitor/lm-logs-azure/master/vm-config/lad_public_settings.json
sed -i "s#__DIAGNOSTIC_STORAGE_ACCOUNT__#$storage_name#g" lad_public_settings.json
sed -i "s#__VM_RESOURCE_ID__#$vm_resource_id#g" lad_public_settings.json

echo -e "\nupdate your logging settings in lad_public_settings.json and execute the following command:"
echo "az vm extension set --publisher Microsoft.Azure.Diagnostics --name LinuxDiagnostic --version 3.0 --resource-group $vm_resource_group --vm-name $vm_name --protected-settings lad_protected_settings.json --settings lad_public_settings.json"
