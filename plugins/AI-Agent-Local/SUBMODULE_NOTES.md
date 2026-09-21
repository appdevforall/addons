# llama.cpp Submodule Notes

## Structure

The llama.cpp source is managed as a git submodule pointing to:
- **Repository:** `appdevforall/llama.cpp`
- **Branch:** `androidide-custom`
- **Path:** `subprojects/llama.cpp/`

## Updating the Submodule

When llama.cpp fork is updated:

```bash
cd subprojects/llama.cpp
git fetch origin
git checkout androidide-custom
git pull origin androidide-custom
cd ../..
git add subprojects/llama.cpp
git commit -m "chore: Update llama.cpp submodule to latest androidide-custom"
git push
```

Or use git's built-in submodule update:

```bash
git submodule update --remote subprojects/llama.cpp
git add subprojects/llama.cpp
git commit -m "chore: Update llama.cpp submodule"
git push
```

## Cloning This Repository

When cloning plugin-examples, initialize submodules:

```bash
git clone git@github.com:appdevforall/plugin-examples.git
cd plugin-examples/ai-assistant
git submodule update --init --recursive
```

Or clone with submodules in one command:

```bash
git clone --recurse-submodules git@github.com:appdevforall/plugin-examples.git
```

## Build Requirements

The submodule must be initialized before building:

```bash
# Check submodule status
git submodule status

# If submodule is not initialized (shows '-' prefix):
git submodule update --init --recursive
```

## CMake Path

The CMakeLists.txt references the submodule:
```cmake
add_subdirectory(../../../../subprojects/llama.cpp/ build-llama)
```

This path is relative from `llama-impl/src/main/cpp/CMakeLists.txt` to `subprojects/llama.cpp/`.

## Troubleshooting

### Submodule shows modified but you didn't change it

```bash
cd subprojects/llama.cpp
git status
git diff
```

If changes exist, either commit them or reset:
```bash
git reset --hard origin/androidide-custom
```

### Build fails with "llama not found"

Ensure submodule is initialized:
```bash
git submodule update --init --recursive
ls -la subprojects/llama.cpp/
```

### Want to switch to different commit

```bash
cd subprojects/llama.cpp
git checkout <commit-hash>
cd ../..
git add subprojects/llama.cpp
git commit -m "chore: Pin llama.cpp to specific commit"
```
