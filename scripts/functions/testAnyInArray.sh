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

A=("A")
Z_A=("Z" "A")
A_B_C=("A" "B" "C")
D_E_F=("D" "E" "F")
any_in_array A A_B_C || echo "A should be in A_B_C"
any_in_array Z_A A_B_C || echo "Z_A should be in A_B_C"
any_in_array A D_E_F && echo "A should not be in D_E_F"
any_in_array A_B_C D_E_F && echo "A_B_C should not be in D_E_F"
