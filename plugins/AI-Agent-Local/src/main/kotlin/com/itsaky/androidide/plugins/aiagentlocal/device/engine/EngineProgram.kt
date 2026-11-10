package com.itsaky.androidide.plugins.aiagentlocal.device.engine

/** The two programs of the Part 1 engine (`part1-engine/`), shipped as `lib<name>.so` files in the plugin's native directory. */
enum class EngineProgram(val libraryName: String) {
    /** CPU read, write, copy, sweep, pin probe and static probe. */
    MEMBW("part1_membw"),

    /** Vulkan compute read and the GPU probe. */
    GPUBW("part1_gpubw");

    /** The file name the host's plugin loader extracts: `lib<name>.so`. */
    val fileName: String get() = "lib$libraryName.so"
}
