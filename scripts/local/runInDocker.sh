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

HOME_IN_IMAGE=/root

docker run -it --rm \
  -v "$HOME/.sleeper/environments:/sleeper/environments" \
  -v "$HOME/.aws:$HOME_IN_IMAGE/.aws" \
  -e AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY \
  -e AWS_SESSION_TOKEN \
  -e AWS_PROFILE \
  -e AWS_REGION \
  -e AWS_DEFAULT_REGION \
  sleeper-local "$@"
