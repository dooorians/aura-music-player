#!/usr/bin/env bash
# Rebuilds native/third_party/llama.cpp: the part of llama.cpp the on-device playlist assistant needs, and nothing else.
#
#   scripts/vendor-llama.sh            # the pinned tag below
#   scripts/vendor-llama.sh b12200     # another tag (then update LLAMA_TAG and VENDOR.md)
#
# What is kept: libllama (include/, src/) and ggml with its CPU backend only (ggml/, ggml-cpu with the arm and x86
# kernels: arm for the phone, x86 so the engine can be built and tested on a workstation). What is dropped: every GPU
# backend (CUDA, Metal, Vulkan, OpenCL, SYCL, Hexagon...), the architectures no Android phone has (riscv, powerpc,
# loongarch, s390, wasm), KleidiAI (fetched at configure time, off here), and everything that is not the library
# (common/, tools/, examples/, tests/, the Python converters, the web UI).
set -euo pipefail

LLAMA_TAG="${1:-b12100}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/native/third_party/llama.cpp"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --quiet --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp "$WORK/src"
COMMIT="$(git -C "$WORK/src" rev-parse HEAD)"

rm -rf "$DEST"
mkdir -p "$DEST/ggml/src"
cp "$WORK/src/LICENSE" "$DEST/"
cp -r "$WORK/src/include" "$WORK/src/src" "$DEST/"
cp "$WORK/src/ggml/CMakeLists.txt" "$DEST/ggml/"
cp -r "$WORK/src/ggml/cmake" "$WORK/src/ggml/include" "$DEST/ggml/"
# ggml's own sources (ggml.c, gguf.cpp, the backend registry...) and the CPU backend, without the others.
find "$WORK/src/ggml/src" -maxdepth 1 -type f -exec cp {} "$DEST/ggml/src/" \;
cp -r "$WORK/src/ggml/src/ggml-cpu" "$DEST/ggml/src/"
rm -rf "$DEST/ggml/src/ggml-cpu/kleidiai" "$DEST/ggml/src/ggml-cpu/spacemit"
for arch in loongarch powerpc riscv s390 wasm; do rm -rf "$DEST/ggml/src/ggml-cpu/arch/$arch"; done
# The models' sources are compiled as a unity build by src/CMakeLists.txt; nothing else under src/ is optional.

cat > "$DEST/VENDOR.md" <<EOF
# llama.cpp (vendored, pruned)

- Upstream: https://github.com/ggml-org/llama.cpp
- Tag: \`$LLAMA_TAG\`
- Commit: \`$COMMIT\`
- License: MIT (see LICENSE)

Produced by \`scripts/vendor-llama.sh $LLAMA_TAG\`; do not edit by hand. Only libllama and ggml's CPU backend
(arm + x86 kernels) are kept. It is built by \`native/slm/CMakeLists.txt\`, linked statically into
\`libaura_llm.so\`.
EOF
echo "llama.cpp $LLAMA_TAG ($COMMIT) -> $DEST ($(du -sh "$DEST" | cut -f1))"
