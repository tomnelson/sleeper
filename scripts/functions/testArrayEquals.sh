#!/usr/bin/env bash
# Copyright 2022 Crown Copyright
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

THIS_DIR=$(cd $(dirname $0) && pwd)
source "${THIS_DIR}/arrayUtils.sh"

A_B=("A" "B")
A_B_C=("A" "B" "C")
C_D=("C" "D")
EMPTY=()

array_equals A_B C_D && echo "A_B should not equal C_D"
array_equals A_B A_B_C && echo "A_B should not equal A_B_C"
array_equals A_B A_B || echo "A_B should equal A_B"
array_equals EMPTY EMPTY || echo "EMPTY should equal EMPTY"
array_equals A_B EMPTY && echo "A_B should not equal EMPTY"
