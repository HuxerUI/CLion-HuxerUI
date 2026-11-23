# HuxerUI for CLion

Create, build, and run [HuxerUI](https://github.com/HuxerUI/HuxerUI) projects from CLion. The plugin integrates SDK selection, project creation, platform and device selection, and C++ editor support.

## Install

Use **Settings → Plugins → Marketplace** to find HuxerUI. To install a local build, choose **Install Plugin from Disk** from the gear menu and select the plugin ZIP.

- CLion 2025.3 or later
- [HuxerUI SDK](https://github.com/HuxerUI/HuxerUI/releases) or a source checkout with a HuxerUI 0.5.0 or newer CLI for project creation and CMake workflows
- Native tools for your target platform, plus `mcpp` on `PATH` for mcpp builds and runs

## Create or open a project

1. In **New Project**, choose **HuxerUI App** or **HuxerUI Library** and select **CMake** or **mcpp**.
2. For a CMake app, select its target platforms, including Web if needed. For a CMake library, select only the platforms that need native code. A library starts with no native platforms selected.
3. Choose a HuxerUI SDK. The wizard selects it automatically when only one is available. **Add SDK** can download a release, browse for a local SDK, or select a source checkout and build its CLI.
4. Click **Create**. The plugin generates the project with the selected SDK and saves that SDK for the project.

To open an existing project, use **File → Open** and select its root directory. The plugin recognizes CMake apps and libraries from their HuxerUI CMake commands and mcpp projects from their manifest or build rules. A CMake library can run its `examples/preview` app.

## Run, build, and package

- In the Run toolbar, use **Run Target** to choose the local desktop, Web, or an Android or iOS device. The button displays the selected target. Ready devices appear first within each platform. Use **Refresh Devices** to scan again.
- Use CLion's **Run** and **Debug** for desktop CMake apps. Web and mobile targets run through the HuxerUI CLI. Use **Run → HuxerUI Run with Watch** to rebuild and relaunch a CMake app when sources change.
- To debug Android C++ code, select the device in **Run Target** and click **Debug**. CLion builds and launches a Debug APK for the device's ABI and attaches its bundled LLDB. The Android NDK and ADB must be available. Set a breakpoint in code you can trigger after LLDB attaches.
- To debug iOS C++ code on macOS, select a booted Xcode simulator in **Run Target**, set your breakpoints, and click **Debug**. The plugin builds and installs a Debug app and attaches CLion's bundled LLDB before the app starts running.
- Use **Build → HuxerUI Build and Package** for Debug and Release builds, packaging, and artifact access. **Build with Options** and **Package with Options** let you set ABIs, optimization, and LTO. Compiler diagnostics in the **Build** window link to their source locations.
- Use **Tools → HuxerUI → Toolchain** to diagnose the environment or preview and apply a supported toolchain setup plan. CLion project trust controls access to project tools.

## Edit C++ code

- Invoke code completion after `[[` or `[[huxerui::` to insert `[[huxerui::composable]]`.
- In a recognized CMake project, complete resource names such as `app::strings::app_name`, `images::logo`, and `raw::sample_config_json`.
- Invoke **Go to Declaration** or Ctrl/Cmd-click a resource name to open its source file. String references open the corresponding key in the source catalog.

## Preview a View

In a HuxerUI CMake project, click the preview icon in the gutter beside a function definition that returns `huxerui::View` or `View`. The **HuxerUI Preview** tool window opens and renders as soon as it has a complete sample call.

The preview uses declared C++ defaults and fills common parameters such as numbers, booleans, and strings. Edit **Sample call** and press Enter to try another value or complete a call that needs custom data or a member object. A function callable without arguments starts with the editor collapsed. Use **Edit Sample Call** in the toolbar to open it. For an aliased or deduced return type, right-click inside the function and select **Preview as HuxerUI View**.

```cpp
#include <huxerui/huxerui.h>
#include <string>

[[huxerui::composable]]
huxerui::View Counter(int value) {
    return huxerui::Text(std::to_string(value));
}

```

The initial sample call is `::Counter(1)`. To preview another value, change it to `::Counter(42)` and press Enter. The panel refreshes when visible and you save the selected file or save under `src/`, `include/`, `resources/`, `preview/`, or the library's `examples/preview/` directory. It updates automatically generated calls when the function signature changes and keeps calls you have edited. Use **Clear Preview** in the toolbar to stop the current build and end refresh on save until you select a View again. If a build fails, it keeps the previous frame and makes the diagnostics available under **Show output**.

Preview renders natively on the current computer. Choose a phone, large phone, tablet, or desktop viewport, or apply a custom width and height between 1 and 4096 logical pixels. Changing the size reuses the compiled preview app. The preview parser is bundled for macOS and Linux on x64 and arm64, and Windows on x64. Use **Run Target** to run on simulators and physical devices.

The plugin builds a temporary preview app with the selected file and the project's C++ sources under `src/`. It reads literal `RESOURCES` and `RESOURCE_NAMESPACE` arguments from the project's `huxerui_add_app()` or `huxerui_add_library()` call. A View in a library's `examples/preview` can use resources from both the example app and the library. For computed CMake arguments, set `HUXERUI_PREVIEW_RESOURCE_DIR` and `HUXERUI_PREVIEW_RESOURCE_NAMESPACE` in `preview/preview.cmake`. Use the same file to supply extra sources, include paths, or libraries.

```cmake
list(APPEND HUXERUI_PREVIEW_SOURCES "${HUXERUI_PREVIEW_PROJECT_DIR}/components/format.cpp")
```

## Manage SDKs

Set **Default SDK** under **Settings → Build, Execution, Deployment → HuxerUI**. The setting applies to projects without a separate SDK binding. The plugin can also discover SDKs from `HUXERUI_HOME`, installed releases, or a nearby source checkout with a built CLI.

Use **Tools → HuxerUI → SDK → Select Project SDK** to change the current project's SDK or choose **Use IDE Default**. **Install SDK Version** downloads a specific release. Downloads are verified with the release's SHA-256 checksum. The SDK menu also offers commands to build or rebuild a source checkout's CLI.

## Build from source

Run Gradle with JDK 21.

```sh
./gradlew test buildPlugin verifyPluginStructure
```

Gradle downloads CLion 2025.3 by default. To use a local installation, pass `-PclionPath=/path/to/clion`. To compile against a local CLion that requires JDK 25, also pass `-PjavaToolchainVersion=25`. The plugin targets Java 21 bytecode. The ZIP is written to `build/distributions/`.

## License

[MIT](LICENSE)
