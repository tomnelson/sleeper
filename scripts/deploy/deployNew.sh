#!/usr/bin/env bash
# Copyright 2022-2023 Crown Copyright
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e

if [ "$#" -lt 4 ] || [ "$#" -gt 6 ]; then
  echo "Usage: $0 <instance-id> <vpc> <subnet> <table-name> <optional-deploy-paused-flag> <optional-split-points-file>"
  exit 1
fi

INSTANCE_ID=$1
VPC=$2
SUBNET=$3
TABLE_NAME=$4
DEPLOY_PAUSED=$5
SPLIT_POINTS_FILE=$6

SCRIPTS_DIR=$(cd "$(dirname "$0")" && cd .. && pwd)
VERSION=$(cat "${SCRIPTS_DIR}/templates/version.txt")

java -cp "${SCRIPTS_DIR}/jars/clients-${VERSION}-utility.jar" sleeper.clients.deploy.DeployNewInstance "${SCRIPTS_DIR}" "${INSTANCE_ID}" "${VPC}" "${SUBNET}" "${TABLE_NAME}" "${DEPLOY_PAUSED}" "${SPLIT_POINTS_FILE}"
