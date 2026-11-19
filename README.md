# HuxerUI for CLion

Create, configure, navigate, build, run, debug, and package HuxerUI projects using CLion's native project wizard,
run configurations, and Build and Run tool windows.

## Create or open a project

Choose **New Project | HuxerUI | HuxerUI App** or **HuxerUI Library**. Choose CMake or the desktop mcpp preview.
CMake apps let you select target platforms and enter an optional reverse-domain project ID. For CMake libraries,
select only the platforms where the library needs native code. No platforms are selected by default. mcpp apps
offer the App and Navigation templates.

The wizard detects HuxerUI SDKs with a 0.5 or newer CLI and selects the SDK when only one is available.
Use **Add SDK** to download the latest release, browse for an SDK, or select a source checkout. Selecting a
checkout can build its standalone CLI in CLion's cache. The wizard uses the selected SDK's templates and binds
that SDK to the new project. Running or building an existing mcpp project requires `mcpp` on the execution host's
`PATH`.

CLion finds CMake apps and libraries from active `huxerui_add_app` and `huxerui_add_library` calls in the
project's root CMake file. Build and Run use a library's `examples/preview` app.
The plugin also recognizes standalone HuxerUI mcpp projects from their manifest or build rules.

## Build, run, and package

- Use **Run Target** next to Run and Debug to select a desktop or Web target directly, or pick a device
  under Android or iOS. The same popup refreshes mobile devices and shows their readiness.
- Use CLion's **Run** and **Debug** with the desktop CMake configuration. Web and mobile run configurations
  launch through the HuxerUI CLI. **Run | HuxerUI Run with Watch** starts a CLI session that rebuilds and relaunches
  when source files change. Use CLion's **Stop** to end a managed session. An app detached by an ordinary launch
  can be closed through its own UI.
- Edit a HuxerUI Run configuration to save its target, configuration, ABI list, optimization, LTO, and Watch setting.
- Use the HuxerUI button beside Run or **Build | HuxerUI Build and Package** for Debug or Release builds and
  packages. Select **Build with Options** or **Package with Options** to override ABI, optimization, and LTO.
  Packages default to Release. **Open Artifacts** opens the app or Preview's `dist` directory.
- Build and toolchain commands stream output to the **Build** window. Compiler diagnostics with source locations
  become navigable entries. Run output appears in the **Run** window.

Desktop builds use CLion's CMake profiles and debugger. CLion configures a native profile for the host desktop
platform and a Web profile when Emscripten's toolchain is available. Gradle and Xcode own mobile builds.

The C++ editor also completes and navigates `strings::name`, `images::name`, and `raw::name` references directly
to their source resources. Namespace-qualified references such as `app::strings::name` are supported.

## SDK and toolchain

- **Settings | Build, Execution, Deployment | HuxerUI** sets the IDE-wide SDK default. The plugin also looks
  for `HUXERUI_HOME`, an installed SDK, or a nearby source checkout with a built CLI.
- **Tools | HuxerUI | Select Project SDK** binds the current project to an installed SDK or a source checkout.
  Pick **Use IDE Default** to clear the project binding. **Install SDK Version** installs and binds a specific
  release. **Install HuxerUI SDK** installs the latest release.
- **Build CLI and Use Source Checkout** and **Rebuild Source CLI** build the standalone CLI with the host's CMake
  and C++ toolchain. The plugin caches the last successful build outside the checkout.
- **Diagnose Environment** checks prerequisites for Build, Run, or Package on the selected target.
  **Set Up Toolchain** previews the CLI's setup plan and can install supported prerequisites after confirmation.
  CLion project trust controls whether the plugin runs project tools.

The installer downloads the host archive from the `HuxerUI/HuxerUI` GitHub Release through CLion's configured proxy,
verifies the release's SHA-256 checksum, and stores releases side by side under `~/.huxerui/sdk`.
Use an SDK or checkout with a **0.5 or newer CLI** for the current build and run commands.

## Requirements

- CLion 2025.3 or later.
- JDK 21 for building the plugin.
- Native tools for the selected HuxerUI platform on the CLion execution host.

## Build the plugin

```bash
./gradlew -PclionPath=/path/to/clion test buildPlugin
```

Without `clionPath`, Gradle downloads CLion 2025.3 for the compatibility baseline. The packaged plugin is written
to `build/distributions/`.

When compiling against a local CLion 2026.2 installation, pass `-PjavaToolchainVersion=25`. The plugin targets
Java 21 bytecode for the CLion 2025.3 compatibility baseline.

## License

[MIT](LICENSE)
