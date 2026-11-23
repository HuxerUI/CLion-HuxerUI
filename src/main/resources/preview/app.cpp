#include <huxerui/huxerui.h>

#include <cstdlib>

namespace huxerui_clion_preview {
huxerui::View Render();
}

void CapturePreview(huxerui::WindowHandle handle);

namespace {

float PreviewDimension(const char* variable, float fallback) {
  const char* value = std::getenv(variable);
  if (!value) return fallback;

  char* end = nullptr;
  const long size = std::strtol(value, &end, 10);
  return end != value && *end == '\0' && size > 0 && size <= 4096 ? static_cast<float>(size) : fallback;
}

huxerui::View PreviewRoot() {
  auto view = huxerui_clion_preview::Render();
  static bool capture_requested = false;
  if (!capture_requested) {
    capture_requested = true;
    CapturePreview(huxerui::UseWindow());
  }
  return view;
}

const huxerui::Application application{
    PreviewRoot,
    {
        .window = {
            .title = "HuxerUI Preview",
            .initial_size = {PreviewDimension("HUXERUI_PREVIEW_WIDTH", 390.0F),
                             PreviewDimension("HUXERUI_PREVIEW_HEIGHT", 844.0F)},
            .content_mode = huxerui::WindowContentMode::EdgeToEdge,
            .chrome_mode = huxerui::WindowChromeMode::None,
            .activatable = false,
            .pointer_pass_through = true,
            .resizable = false,
        },
        .show_debug_overlay = false,
    },
};

} // namespace
