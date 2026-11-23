#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <d3d11.h>
#include <wincodec.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <huxerui/huxerui.h>
#include <huxerui/windows/platform_registry.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <thread>

namespace {

using namespace winrt;
using namespace Windows::Graphics::Capture;
using namespace Windows::Graphics::DirectX;
using namespace std::chrono_literals;

void SaveFrame(ID3D11Device& device, ID3D11DeviceContext& context, const Direct3D11CaptureFrame& frame) {
  const wchar_t* path = _wgetenv(L"HUXERUI_PREVIEW_PNG");
  if (!path) throw hresult_invalid_argument(L"Expected a preview PNG path.");

  auto surface = frame.Surface().as<::Windows::Graphics::DirectX::Direct3D11::IDirect3DDxgiInterfaceAccess>();
  com_ptr<ID3D11Texture2D> texture;
  check_hresult(surface->GetInterface(__uuidof(ID3D11Texture2D), texture.put_void()));
  D3D11_TEXTURE2D_DESC description{};
  texture->GetDesc(&description);
  const auto size = frame.ContentSize();
  if (size.Width <= 0 || size.Height <= 0 || static_cast<UINT>(size.Width) > description.Width
      || static_cast<UINT>(size.Height) > description.Height) {
    throw hresult_error(E_FAIL, L"The preview window changed size during capture.");
  }
  description.Usage = D3D11_USAGE_STAGING;
  description.BindFlags = 0;
  description.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
  description.MiscFlags = 0;
  com_ptr<ID3D11Texture2D> staging;
  check_hresult(device.CreateTexture2D(&description, nullptr, staging.put()));
  context.CopyResource(staging.get(), texture.get());

  auto factory = create_instance<IWICImagingFactory>(CLSID_WICImagingFactory);
  com_ptr<IWICStream> stream;
  check_hresult(factory->CreateStream(stream.put()));
  check_hresult(stream->InitializeFromFilename(path, GENERIC_WRITE));
  com_ptr<IWICBitmapEncoder> encoder;
  check_hresult(factory->CreateEncoder(GUID_ContainerFormatPng, nullptr, encoder.put()));
  check_hresult(encoder->Initialize(stream.get(), WICBitmapEncoderNoCache));
  com_ptr<IWICBitmapFrameEncode> png;
  check_hresult(encoder->CreateNewFrame(png.put(), nullptr));
  check_hresult(png->Initialize(nullptr));
  check_hresult(png->SetSize(size.Width, size.Height));
  auto pixel_format = GUID_WICPixelFormat32bppBGRA;
  check_hresult(png->SetPixelFormat(&pixel_format));
  if (!IsEqualGUID(pixel_format, GUID_WICPixelFormat32bppBGRA)) {
    throw hresult_error(E_FAIL, L"The PNG encoder could not use the preview pixel format.");
  }
  D3D11_MAPPED_SUBRESOURCE pixels{};
  check_hresult(context.Map(staging.get(), 0, D3D11_MAP_READ, 0, &pixels));
  const HRESULT written = png->WritePixels(size.Height, pixels.RowPitch,
      pixels.RowPitch * size.Height, static_cast<BYTE*>(pixels.pData));
  context.Unmap(staging.get(), 0);
  check_hresult(written);
  check_hresult(png->Commit());
  check_hresult(encoder->Commit());
}

void Capture(HWND window) {
  init_apartment();
  if (!GraphicsCaptureSession::IsSupported()) {
    throw hresult_error(E_NOTIMPL, L"Windows Graphics Capture is unavailable.");
  }
  com_ptr<ID3D11Device> device;
  com_ptr<ID3D11DeviceContext> context;
  check_hresult(D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, D3D11_CREATE_DEVICE_BGRA_SUPPORT,
      nullptr, 0, D3D11_SDK_VERSION, device.put(), nullptr, context.put()));
  com_ptr<::IInspectable> inspectable;
  check_hresult(CreateDirect3D11DeviceFromDXGIDevice(device.as<IDXGIDevice>().get(), inspectable.put()));
  auto graphics_device = inspectable.as<Direct3D11::IDirect3DDevice>();
  auto interop = get_activation_factory<GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
  GraphicsCaptureItem item{nullptr};
  check_hresult(interop->CreateForWindow(window, guid_of<IGraphicsCaptureItem>(), put_abi(item)));
  const auto size = item.Size();
  auto pool = Direct3D11CaptureFramePool::CreateFreeThreaded(graphics_device,
      DirectXPixelFormat::B8G8R8A8UIntNormalized, 2, size);
  auto session = pool.CreateCaptureSession(item);
  session.StartCapture();

  const auto deadline = std::chrono::steady_clock::now() + 8s;
  while (std::chrono::steady_clock::now() < deadline) {
    if (auto frame = pool.TryGetNextFrame()) {
      SaveFrame(*device.get(), *context.get(), frame);
      return;
    }
    std::this_thread::sleep_for(25ms);
  }
  throw hresult_error(HRESULT_FROM_WIN32(ERROR_TIMEOUT), L"Windows did not produce a preview frame.");
}

} // namespace

void CapturePreview(huxerui::WindowHandle handle) {
  HWND window = huxerui::windows::GetPlatformWindow(handle);
  if (!window) return;
  std::thread([window] {
    try {
      const auto deadline = std::chrono::steady_clock::now() + 5s;
      while (!IsWindowVisible(window) && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(25ms);
      }
      Capture(window);
      PostMessageW(window, WM_CLOSE, 0, 0);
    } catch (const winrt::hresult_error& error) {
      std::fprintf(stderr, "%s\n", winrt::to_string(error.message()).c_str());
      std::exit(EXIT_FAILURE);
    }
  }).detach();
}
