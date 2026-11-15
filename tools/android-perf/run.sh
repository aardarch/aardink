#!/usr/bin/env bash
# Copyright 2026 Aardarch
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# The Android gate's runs, on the emulator CI's android-perf job has started: main's benchmarks,
# then the change's, one after the other on the same emulator, then the change's baseline profile.
# Results go to results/main and results/change for compare.mjs.
#
#   tools/android-perf/run.sh <main checkout> <change checkout> <results dir>

set -euo pipefail

main_dir=$1
change_dir=$2
results=$3
outputs=sample-benchmark/build/outputs/connected_android_test_additional_output

benchmark() {
  local checkout=$1 name=$2
  (
    cd "$checkout"
    ./gradlew --console=plain :sample-benchmark:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
  )
  mkdir -p "$results/$name"
  find "$checkout/$outputs" -name '*-benchmarkData.json' -exec cp {} "$results/$name/" \;
}

# main before :sample-benchmark existed has nothing to run: compare.mjs then only records.
if [ -d "$main_dir/sample-benchmark" ]; then
  benchmark "$main_dir" main
fi
benchmark "$change_dir" change

# The profile this change would ship, for comparing with the committed one.
(cd "$change_dir" && ./gradlew --console=plain :sample:generateBaselineProfile)
mkdir -p "$results/profile"
cp "$change_dir"/sample/src/release/generated/baselineProfiles/*.txt "$results/profile/"
