# Compose Preview on the Project's Own Compose - Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compile and run `@Preview` composables against the user project's own resolved Compose libraries, not the Compose bundled in the plugin or shipped in CoGo.

**Architecture:** Compile the preview source together with a generated host file (`CogoPreviewHost`) against the project's compile classpath. Load the result with a `DelegateLastClassLoader` over the preview dex plus the project's built dex, so every `androidx.*` class comes from the project's libraries and never from CoGo. The generated host builds the `ComposeView` inside that loader and hands the plugin a plain `android.view.View`. No Compose object crosses between plugin code and user code.

**Tech Stack:** Kotlin, Android (plugin minSdk 28), kotlinc + Compose compiler plugin 2.3.21 from the on-device `localMvnRepository`, D8, JUnit 4 for JVM unit tests.

**Spec:** [ADFA-5162](https://appdevforall.atlassian.net/browse/ADFA-5162) (CoGo GitHub #1683), plus Jira comments 40973 and 40975.

## Global Constraints

- Work in `plugins/Jetpack-Compose-Preview/` on branch `fix/ADFA-5162-compose-preview-project-deps`. Build: `cd plugins/Jetpack-Compose-Preview && ../../gradlew assemblePlugin`.
- CoGo builds offline. A project can only use Compose versions in the phone's `localMvnRepository`, which today has BOM `2022.10.00` (Compose 1.3.0, Material3 1.0.0).
- CoGo loads plugin classes parent-first through its own classloader, and CoGo ships Compose BOM `2024.02.00`. Nothing plugin-side may rely on CoGo's Compose for user code.
- No new runtime dependencies. `junit:junit:4.13.2` as `testImplementation` is the only addition.
- Every changed screen must work at font scale 1.0 and 2.0.
- PR body opens with `[ADFA-5162](https://appdevforall.atlassian.net/browse/ADFA-5162)`.

## Sequencing with addons PR #161

PR #161 (ADFA-7068, open) rewrites `CompilerDaemon.dex`/`buildD8Args`, the `DexCache` key and `initializeInfrastructure`, which Tasks 3-5 also touch. Start Task 0 now. Start Task 1 after #161 merges, rebased on `main`. Task 5's cache-key step is dropped if #161 already keys on the project dex.

## Review Focus

1. Project not built yet (no dependency dex): show "Build the project to preview", never a crash. Test in Task 3.
2. Project without `androidx.compose.runtime` on its classpath: clear message, no compile attempt. Test in Task 1.
3. `@PreviewParameter` previews render one card per value. Test in Task 2.
4. Compose compiler 2.3.21 output on Compose runtime 1.3.0 may need runtime APIs that 1.3.0 lacks. Task 0 checks this. If it fails, the preview must show the compiler or link error text, not crash CoGo.
5. The user changes Compose version and rebuilds: the next preview uses the new dex. The loader key is path+mtime of every dex, checked in Task 3.

---

### Task 0: Device spike (no code)

Settles two unverified facts the design rests on. Record the results in a Jira comment.

- [ ] **Step 1:** On the emulator (CoGo debug build), finish onboarding with `/sdcard/Download/assets-arm64-v8a.zip`, create a project from the Compose template, run a debug build and install the current Compose Preview plugin.
- [ ] **Step 2 - baseline:** open `MainActivity.kt` and tap preview. Capture the error (expected: a `NoSuchMethodError`, e.g. `darkColorScheme`) with a screenshot and logcat.
- [ ] **Step 3:** `adb shell su 0 find /data/user/0/com.itsaky.androidide/files/home/<project>/app/build/intermediates/dex/debug -name '*.dex'`. Then, with `dexdump`, check whether one of them defines `Landroidx/compose/runtime/Composer;`.
  - Pass: dependency dex exists (expected under `mergeExtDexDebug/`), and CoGo's `getRuntimeDexFiles` already returns it, because it scans `intermediates/dex/<variant>/**`.
  - Fail: Task 3 adds `dexProjectLibraries(compileJars, outDir)`. It runs D8 over the project compile jars, caches the output keyed on path+mtime of the jars, and the loader uses that dex instead.
- [ ] **Step 4:** In the project, compile a trivial `@Composable` with the plugin's kotlinc + Compose compiler 2.3.21 against the project's compile classpath, then run it. Confirm the generated code links against runtime 1.3.0. If it does not, stop and report: the fix then needs a Compose compiler matched to the project's runtime.

### Task 1: Compile against the project's classpath

**Files:**
- Modify: `src/main/kotlin/org/appdevforall/composepreview/compiler/ComposeClasspathManager.kt` (`getCompilationClasspath`, `getFullClasspath`)
- Modify: `build.gradle.kts` (add `testImplementation("junit:junit:4.13.2")`)
- Test: `src/test/kotlin/org/appdevforall/composepreview/compiler/CompilationClasspathTest.kt`

**Interfaces:**
- Produces: `internal fun compilationClasspath(androidJar: File, projectJars: List<File>): Result<List<File>>`, a top-level function in `ComposeClasspathManager.kt`. It returns `android.jar` followed by `projectJars`, minus missing files. It fails with `NoComposeRuntimeException` when no project jar contains `androidx/compose/runtime/Composer.class`.

- [ ] **Step 1:** Write `CompilationClasspathTest`:
  - `projectJarsOnly`: with temp jars, the result is `[androidJar, p1, p2]` and contains no file under `COMPOSE_HOME`.
  - `missingJarsDropped`
  - `noComposeRuntimeFails`: `isFailure` and the exception is `NoComposeRuntimeException`.
- [ ] **Step 2:** Run `../../gradlew testDebugUnitTest --tests '*CompilationClasspathTest'`. Expected: FAIL, unresolved reference.
- [ ] **Step 3:** Implement it, and switch `getCompilationClasspath` to it. The bundled jars leave the compile path.
- [ ] **Step 4:** Run the test again. Expected: PASS.
- [ ] **Step 5:** `git add` the three files, then commit `ADFA-5162: Compile previews against the project's classpath only`.

### Task 2: Generated preview host

**Files:**
- Create: `src/main/kotlin/org/appdevforall/composepreview/compiler/PreviewHostSource.kt`
- Test: `src/test/kotlin/org/appdevforall/composepreview/compiler/PreviewHostSourceTest.kt`

**Interfaces:**
- Consumes: `ParsedPreviewSource` (`domain/model`): package, file class, function name, and the `@PreviewParameter` provider FQN if present.
- Produces: `object PreviewHostSource { const val CLASS_NAME = "org.appdevforall.composepreview.generated.CogoPreviewHost"; fun generate(source: ParsedPreviewSource, fn: PreviewFunction): String }`. The generated Kotlin declares `@JvmStatic fun create(context: android.content.Context, index: Int): android.view.View`. It also declares `@JvmStatic fun count(): Int`, which is 1, or the provider's `values.count()`.

`create` must:
- build a `ComposeView`;
- create its own `LifecycleOwner` (set to `RESUMED`), `ViewModelStoreOwner` and `SavedStateRegistryOwner`, and set them on that view with `setViewTreeLifecycleOwner`, `setViewTreeViewModelStoreOwner` and `setViewTreeSavedStateRegistryOwner`. CoGo's owners are of CoGo's `androidx.lifecycle` classes, so the project's Compose cannot see them.
- call `setContent { CompositionLocalProvider(LocalInspectionMode provides true) { <call> } }`.

No `MaterialTheme` or `Surface` wrapper: the project may not use Material3, and Studio's preview adds none.

- [ ] **Step 1:** Write `PreviewHostSourceTest` with string assertions:
  - top-level `fun Greeting()` in `com.example` produces `com.example.Greeting()`;
  - a function in `object Previews` produces `com.example.Previews.Greeting()`;
  - a provider produces `com.example.Greeting(com.example.NameProvider().values.elementAt(index))`, and `count()` uses `NameProvider().values.count()`;
  - the output contains `setViewTreeLifecycleOwner` and no `MaterialTheme`.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Implement it as a template string with the call substituted in.
- [ ] **Step 4:** Run it. Expected: PASS.
- [ ] **Step 5:** Commit `ADFA-5162: Generate a preview host compiled with the user's code`.

### Task 3: Isolated classloader

**Files:**
- Modify: `src/main/kotlin/org/appdevforall/composepreview/runtime/ComposeClassLoader.kt` (`getOrCreateLoader`, `setRuntimeDex`, the comment at :94-96)
- Modify: `data/repository/ComposePreviewRepositoryImpl.kt` (stop calling `getOrCreateRuntimeDex`)
- Test: `src/test/kotlin/org/appdevforall/composepreview/runtime/PreviewDexPathTest.kt`

**Interfaces:**
- Produces: `internal fun previewDexPath(previewDex: File, projectDex: List<File>): Result<List<File>>`. It returns the preview dex first, then the existing project dex. It fails with `ProjectNotBuiltException` when no project dex defines Compose. Use the `Composer` check from Task 0 Step 3; checking by name is enough if Task 0 confirmed the dex name (`mergeExtDexDebug`).
- `getOrCreateLoader` returns `dalvik.system.DelegateLastClassLoader(dexPath, pluginClassLoader)`. Delegate-last means: boot classpath first, then the preview and project dex, then CoGo. So `androidx.*` and `kotlin.*` come from the project. Rewrite the stale comment to say why.

- [ ] **Step 1:** Write `PreviewDexPathTest`: order is preserved, missing files are dropped, and an empty project dex gives `ProjectNotBuiltException`.
- [ ] **Step 2:** Run it. Expected: FAIL.
- [ ] **Step 3:** Implement it, switch the loader, and remove the runtime-dex plumbing. Map `ProjectNotBuiltException` to the existing needs-build UI state.
- [ ] **Step 4:** Run it. Expected: PASS.
- [ ] **Step 5:** Commit `ADFA-5162: Load previews delegate-last over the project's dex`.

### Task 4: Render through the generated host

**Files:**
- Modify: `ComposePreviewFragment.kt` (`loadAndRender`, card creation at ~164/~495)
- Modify: `ComposePreviewRepositoryImpl.kt` (`compilePreview`: add the `PreviewHostSource.generate(...)` output as a second source file)
- Delete: `runtime/ComposableInvoker.kt`, `runtime/ComposeSignature.kt`, the user-content path in `runtime/ComposableRenderer.kt`. Keep its error UI, which is plugin-side Compose drawing plugin-only content.
- Modify: `ui/BoundedComposeView.kt`. It becomes a `FrameLayout` that bounds a child `View`.

**Interfaces:**
- Consumes: `PreviewHostSource.CLASS_NAME`, `create(Context, Int)`, `count()` (Task 2); the loader (Task 3).
- The fragment calls `loader.loadClass(CLASS_NAME)`, reads `count()`, and adds each `create(requireContext(), i)` result to a card. A throwable from `create` or from first layout shows in that card's error UI.

- [ ] **Step 1:** Implement it, then build: `../../gradlew assemblePlugin`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2:** On the Task 0 project, the preview renders the template's `Greeting`. A `@PreviewParameter` preview shows one card per value. logcat shows the classes loaded from `mergeExtDexDebug` dex, not `base.apk`.
- [ ] **Step 3:** Commit `ADFA-5162: Render previews through the generated host view`.

### Task 5: D8 against the project, drop the bundle

**Files:**
- Modify: `compiler/CompilerDaemon.kt` (`buildD8Args`), `compiler/ComposeDexCompiler.kt`. The D8 `--classpath` becomes the project compile jars instead of `getRuntimeJars()`.
- Modify: `compiler/DexCache.kt` call site. The key becomes the source plus path+mtime of the project dex, unless #161 already does this.
- Delete: `src/main/assets/compose/compose-jars.zip` (about 30 MB) and the extraction code (`ensureComposeJarsExtracted`, `extractComposeJars`, `getRuntimeJars`, `getOrCreateRuntimeDex`).
- Modify: `build.gradle.kts`. Keep the Compose dependencies the plugin's own UI needs, and fix the "Compose is BUNDLED" comment.
- Modify: `README.md`, `jetpack-compose-preview.html`, `src/main/assets/docs/index.html`. A preview now needs a built project, and it uses the project's Compose.

- [ ] **Step 1:** Implement it, build, and confirm the `.cgp` is about 30 MB smaller.
- [ ] **Step 2:** Edit the preview source and preview again. The new code shows, and the old cached dex isn't reused.
- [ ] **Step 3:** Commit the code (`ADFA-5162: Dex against the project and remove the bundled Compose`) and the docs (`ADFA-5162: Docs: previews use the project's Compose`) as separate commits.

### Task 6: Verify, QA steps, PR

- [ ] **Step 1:** Run the Step 2 scenarios on the emulator at font scale 1.0 and 2.0, with CoGo debug and release builds. Release builds need CoGo #2220. Attach screenshots and logcat to ADFA-5162.
- [ ] **Step 2:** Write the ticket's Steps to QA field as Gherkin:
  - baseline (current plugin: `NoSuchMethodError`);
  - template project renders;
  - project not built (asks for a build);
  - `@PreviewParameter`;
  - Compose version changed and rebuilt.
- [ ] **Step 3:** Push, open the PR into `main`, and move ADFA-5162 to Code review.
