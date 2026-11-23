#include <gtk/gtk.h>

#include <huxerui/huxerui.h>
#include <huxerui/linux/platform_registry.h>

#include <cstdio>
#include <cstdlib>

void CapturePreview(huxerui::WindowHandle handle) {
  g_timeout_add_full(G_PRIORITY_DEFAULT_IDLE, 25, [](gpointer data) -> gboolean {
    const auto& handle = *static_cast<const huxerui::WindowHandle*>(data);
    GtkWindow* window = huxerui::linux::GetPlatformWindow(handle);
    if (!window) return G_SOURCE_CONTINUE;
    GtkWidget* child = gtk_window_get_child(window);
    GskRenderer* renderer = gtk_native_get_renderer(GTK_NATIVE(window));
    if (!renderer || !child || !gtk_widget_is_drawable(child)) return G_SOURCE_CONTINUE;
    const int width = gtk_widget_get_width(child);
    const int height = gtk_widget_get_height(child);
    if (width <= 0 || height <= 0) return G_SOURCE_CONTINUE;

    GdkPaintable* paintable = gtk_widget_paintable_new(child);
    GtkSnapshot* snapshot = gtk_snapshot_new();
    gdk_paintable_snapshot(paintable, GDK_SNAPSHOT(snapshot), width, height);
    g_object_unref(paintable);
    GskRenderNode* node = gtk_snapshot_free_to_node(snapshot);
    if (!node) return G_SOURCE_CONTINUE;

    GdkTexture* frame = gsk_renderer_render_texture(renderer, node, nullptr);
    gsk_render_node_unref(node);
    if (!frame) return G_SOURCE_CONTINUE;
    const char* output = std::getenv("HUXERUI_PREVIEW_PNG");
    const bool saved = output && gdk_texture_save_to_png(frame, output);
    g_object_unref(frame);
    if (!saved) {
      std::fputs("Could not write the GTK preview frame.\n", stderr);
      std::exit(EXIT_FAILURE);
    }
    gtk_window_close(window);
    return G_SOURCE_REMOVE;
  }, new huxerui::WindowHandle(handle), [](gpointer data) {
    delete static_cast<huxerui::WindowHandle*>(data);
  });
}
