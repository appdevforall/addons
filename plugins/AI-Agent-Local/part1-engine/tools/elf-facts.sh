#!/bin/bash
# elf-facts.sh BINARY...: print the ELF facts the P1-03 checks need (machine, type, PIE flag, LOAD alignment, NEEDED, interpreter, Android API note, dynamic-symbol imports).
# Uses llvm-readelf from the installed NDK. Exit 1 if a binary fails a rule: AArch64, ET_DYN with DF_1_PIE, every LOAD align >= 0x4000, NEEDED subset of {libc,libm,libdl,libvulkan,liblog? (no)}.
NDK=${NDK:-$(ls -d "$HOME"/Android/Sdk/ndk/*/ | sort -V | tail -1)}; RE=${NDK%/}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
rc=0
for f in "$@"; do
  echo "=== $(basename "$f")"; sz=$(stat -c %s "$f"); echo "size_bytes: $sz"
  H=$("$RE" -h "$f"); echo "$H" | grep -E "Class:|Data:|Type:|Machine:|Entry point"
  echo "$H" | grep -q "Machine: .*AArch64" || { echo "RULE FAIL: machine is not AArch64"; rc=1; }
  echo "$H" | grep -q "Type: .*DYN" || { echo "RULE FAIL: type is not DYN"; rc=1; }
  D=$("$RE" -d "$f"); echo "-- dynamic"; echo "$D" | grep -E "NEEDED|FLAGS_1|SONAME|RUNPATH|RPATH"
  echo "$D" | grep -q "PIE" || { echo "RULE FAIL: DF_1_PIE not set"; rc=1; }
  echo "-- program headers (LOAD alignment must be >= 0x4000 = 16 KB)"; "$RE" -lW "$f" | grep -E "LOAD|INTERP|Requesting program interpreter|GNU_RELRO|GNU_STACK"
  for a in $("$RE" -lW "$f" | awk '/ LOAD /{print $NF}'); do [ $((a)) -ge 16384 ] || { echo "RULE FAIL: a LOAD segment is aligned below 16 KB ($a)"; rc=1; }; done
  echo "-- needed libraries allowed: libc.so libm.so libdl.so (+ libvulkan.so for libpart1_gpubw.so)"
  for n in $(echo "$D" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do case "$n" in libc.so|libm.so|libdl.so) ;; libvulkan.so) case "$(basename "$f")" in *gpubw*) ;; *) echo "RULE FAIL: unexpected NEEDED $n";rc=1;; esac;; *) echo "RULE FAIL: unexpected NEEDED $n"; rc=1;; esac; done
  echo "-- Android ident note (minimum API level the binary was built for)"
  api=$("$RE" -nW "$f" | awk '/description data:/ && !d {d=1; print $3}')   # first 4 bytes, little endian
  echo "android_api_level_in_note: $((16#$api))"; [ $((16#$api)) -ge 33 ] || { echo "RULE FAIL: note API level below 33"; rc=1; }
  echo "-- undefined (imported) dynamic symbols that are not libc/libm basics: Vulkan / perf / dma"; "$RE" --dyn-syms -W "$f" | awk '$7=="UND"{print $8}' | grep -E "^vk|perf_event|dma|prctl" | sort | tr '\n' ' '; echo
done
exit $rc
