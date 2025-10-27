########### THIS IS THE SCRIPT THE gh-argocd-full PIPELINE calls to execute the gitops folders and file creations as well as the image insertion.

#!/bin/bash

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# Check if app_name parameter is provided
if [ -z "$1" ]; then
    echo -e "${RED}Error: Application name parameter is required.${NC}"
    echo "Usage: $0 <app_name>"
    exit 1
fi

function create() {
    local app_name="$1"  # Accept app_name as a function parameter
    local image_name="$2" # Accept image_name as a function parameter

    cp template-application.yaml sandbox/applications/"${app_name}".yaml
    cd sandbox/applications/

    # If the file has been created
    if [ -f "${app_name}.yaml" ]; then
        echo -e "${GREEN} ${app_name}.yaml has been created.${NC}"

        #### Update ARGOCD applications manifest
        sed -i "s/name: app_name/name: ${app_name}/" "${app_name}.yaml"
        grep name "${app_name}.yaml"

        sed -i "s#path: sandbox/manifest/app_name#path: sandbox/manifest/${app_name}#" "${app_name}.yaml"
        grep path "${app_name}.yaml"
        #### End of ARGOCD applications manifest

        #### Update k8s manifest
        cd ../manifest
        mkdir "${app_name}"
        cd "${app_name}"
        cp ../template-object.yaml "${app_name}.yaml"
        sed -i "s/app_name/${app_name}/" "${app_name}.yaml"
        sed -i "s/reimage/${image_name}/" "${app_name}.yaml"

    else
        # If file does not exist
        echo -e "${RED} file does not exist.${NC}"
    fi
}

# Call the function with the command-line parameter
create "$1" "$2"
