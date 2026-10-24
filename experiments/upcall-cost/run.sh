#!/usr/bin/env bash
# Builds and runs experiment E0. Everything lands in experiments/upcall-cost/build/.
set -euo pipefail
cd "$(dirname "$0")"
G="${GRAALVM_HOME:-$(ls -d "$HOME"/Library/Java/JavaVirtualMachines/bellsoft-liberica-vm-full-openjdk25*/Contents/Home | tail -1)}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:--XX:ActiveProcessorCount=2}"
B=build; rm -rf $B; mkdir -p $B/classes
cc -O2 -arch arm64 -dynamiclib -o $B/libprobehelper.dylib -install_name @rpath/libprobehelper.dylib helper.c
"$G/bin/javac" -d $B/classes src/Probe.java
"$G/bin/native-image" --shared --parallelism=2 -O2 -cp $B/classes -o $B/libprobe \
  "-H:NativeLinkerOption=-Wl,-undefined,dynamic_lookup" \
  "-H:NativeLinkerOption=-Wl,-install_name,@rpath/libprobe.dylib"
cc -O2 -arch arm64 -I$B -o $B/driver driver.c -L$B -lprobe -lprobehelper -Wl,-rpath,@executable_path
$B/driver
