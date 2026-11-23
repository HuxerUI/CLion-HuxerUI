#import <AppKit/AppKit.h>

#include <huxerui/huxerui.h>
#include <huxerui/macos/platform_registry.h>

#include <cstdio>
#include <cstdlib>

void CapturePreview(huxerui::WindowHandle handle) {
  if (NSWindow* window = huxerui::macos::GetPlatformWindow(handle)) {
    window.alphaValue = 0.0;
  }

  dispatch_async(dispatch_get_main_queue(), ^{
    const char* output = std::getenv("HUXERUI_PREVIEW_PNG");
    NSView* view = huxerui::macos::GetPlatformWindow(handle).contentView;
    [view layoutSubtreeIfNeeded];
    NSBitmapImageRep* bitmap = [view bitmapImageRepForCachingDisplayInRect:view.bounds];
    if (bitmap && output) {
      [view cacheDisplayInRect:view.bounds toBitmapImageRep:bitmap];
      NSData* png = [bitmap representationUsingType:NSBitmapImageFileTypePNG properties:@{}];
      if ([png writeToFile:[NSString stringWithUTF8String:output] atomically:YES]) {
        [NSApplication.sharedApplication terminate:nil];
        return;
      }
    }
    std::fputs("Could not write the macOS preview frame.\n", stderr);
    std::exit(EXIT_FAILURE);
  });
}
