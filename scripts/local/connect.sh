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

THIS_DIR=$(cd "$(dirname "$0")" && pwd)
ENVIRONMENTS_DIR="$THIS_DIR/environments"
INSTANCE_ID=$(cat "$ENVIRONMENTS_DIR/current.txt")
OUTPUTS_FILE="$ENVIRONMENTS_DIR/$INSTANCE_ID-outputs.json"
KNOWN_HOSTS_FILE="$ENVIRONMENTS_DIR/$INSTANCE_ID-known_hosts"
PRIVATE_KEY_FILE="$ENVIRONMENTS_DIR/$INSTANCE_ID-BuildEC2.pem"

USER=$(jq ".[\"$INSTANCE_ID-BuildEC2\"].LoginUser" "$OUTPUTS_FILE" --raw-output)
EC2_IP=$(jq ".[\"$INSTANCE_ID-BuildEC2\"].PublicIP" "$OUTPUTS_FILE" --raw-output)

ssh -i "$PRIVATE_KEY_FILE" -o "UserKnownHostsFile=$KNOWN_HOSTS_FILE" -t "$USER@$EC2_IP" screen -d -RR
