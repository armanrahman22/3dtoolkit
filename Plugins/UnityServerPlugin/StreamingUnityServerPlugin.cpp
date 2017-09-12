#define SUPPORT_D3D11 1
#define WEBRTC_WIN 1

#define SHOW_CONSOLE 0


#include <iostream>
#include <thread>
#include <string>
#include <fstream>
#include <cstdint>
#include <wrl.h>
#include <d3d11_2.h>
#include "IUnityGraphicsD3D11.h"
#include "IUnityGraphics.h"
#include "IUnityInterface.h"

#include "buffer_renderer.h"
#include "conductor.h"
#include "server_main_window.h"
#include "flagdefs.h"
#include "peer_connection_client.h"
#include "config_parser.h"
#include "webrtc/modules/video_coding/codecs/h264/h264_encoder_impl.h"
#include "webrtc/base/checks.h"
#include "webrtc/base/ssladapter.h"
#include "webrtc/base/win32socketinit.h"
#include "webrtc/base/win32socketserver.h"

#pragma warning( disable : 4100 )
#pragma comment(lib, "ws2_32.lib") 

#pragma comment(lib, "d3dcompiler.lib")
#pragma comment(lib, "dxguid.lib")
#pragma comment(lib, "comctl32.lib")
#pragma comment(lib, "imm32.lib")
#pragma comment(lib, "version.lib")
#pragma comment(lib, "usp10.lib")
#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "winmm.lib")

#pragma comment(lib, "dmoguids.lib")
#pragma comment(lib, "wmcodecdspuuid.lib")
#pragma comment(lib, "secur32.lib")
#pragma comment(lib, "msdmo.lib")
#pragma comment(lib, "strmiids.lib")

#pragma comment(lib, "common_video.lib")
#pragma comment(lib, "webrtc.lib")
#pragma comment(lib, "boringssl_asm.lib")
#pragma comment(lib, "field_trial_default.lib")
#pragma comment(lib, "metrics_default.lib")
#pragma comment(lib, "protobuf_full.lib")

using namespace Microsoft::WRL;
using namespace StreamingToolkit;

void(__stdcall*s_onInputUpdate)(const char *msg);
void(__stdcall*s_onLog)(const int level, const char *msg);

#define ULOG(sev, msg) if (s_onLog) { (*s_onLog)(sev, msg); } LOG(sev) << msg

DEFINE_GUID(IID_Texture2D, 0x6f15aaf2, 0xd208, 0x4e89, 0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c);

static IUnityInterfaces* s_UnityInterfaces = nullptr;
static IUnityGraphics* s_Graphics = nullptr;
static UnityGfxRenderer s_DeviceType = kUnityGfxRendererNull;
static ComPtr<ID3D11Device> s_Device;
static ComPtr<ID3D11DeviceContext> s_Context;

static rtc::scoped_refptr<Conductor> s_conductor = nullptr;

static BufferRenderer*		s_bufferRenderer = nullptr;
static ID3D11Texture2D*		s_frameBuffer = nullptr;
static WebRTCConfig			s_webrtcConfig;

ServerMainWindow *wnd;
std::thread *messageThread;

std::string s_server = "signalingserveruri";
uint32_t s_port = 3000;

bool s_closing = false;


void InitWebRTC()
{
	ULOG(INFO, __FUNCTION__);

	// Loads webrtc config file.
	ConfigParser::Parse("webrtcConfig.json", &s_webrtcConfig);

	rtc::EnsureWinsockInit();
	rtc::Win32Thread w32_thread;
	rtc::ThreadManager::Instance()->SetCurrentThread(&w32_thread);
	rtc::InitializeSSL();

	PeerConnectionClient client;

	wnd = new ServerMainWindow(
		FLAG_server,
		FLAG_port,
		FLAG_autoconnect,
		FLAG_autocall,
		true,
		1280,
		720);

	wnd->Create();

	s_server = s_webrtcConfig.server;
	s_port = s_webrtcConfig.port;
	client.SetHeartbeatMs(s_webrtcConfig.heartbeat);

	s_conductor = new rtc::RefCountedObject<Conductor>(
		&client,
		wnd,
		&s_webrtcConfig,
		s_bufferRenderer);
	
	// Handles input from client.
	InputDataHandler inputHandler([&](const std::string& message)
	{
		ULOG(INFO, __FUNCTION__);

		if (s_onInputUpdate)
		{
			ULOG(INFO, message.c_str());

			(*s_onInputUpdate)(message.c_str());
		}
	});

	s_conductor->SetInputDataHandler(&inputHandler);

	if (s_conductor != nullptr)
	{
		MainWindowCallback *callback = s_conductor;

		callback->StartLogin(s_server, s_port);
	}

	// Main loop.
	MSG msg;
	BOOL gm;
	while ((gm = ::GetMessage(&msg, NULL, 0, 0)) != 0 && gm != -1 && !s_closing)
	{
		if (!wnd->PreTranslateMessage(&msg))
		{
			try
			{
				::TranslateMessage(&msg);
				::DispatchMessage(&msg);
			}
			catch (const std::exception& e) { // reference to the base of a polymorphic object
				std::cout << e.what(); // information from length_error printed
				ULOG(LERROR, e.what());
			}
		}
	}
}


static void UNITY_INTERFACE_API OnEncode(int eventID)
{
	ULOG(INFO, __FUNCTION__);

	if (s_Context)
	{
		ULOG(INFO, "s_Context is ~NULL");

		if (s_frameBuffer == nullptr)
		{
			ID3D11RenderTargetView* rtv(nullptr);
			ID3D11DepthStencilView* depthStencilView(nullptr);

			s_Context->OMGetRenderTargets(1, &rtv, &depthStencilView);

			if (rtv)
			{
				rtv->GetResource(reinterpret_cast<ID3D11Resource**>(&s_frameBuffer));
				rtv->Release();

				// Render loop.
				std::function<void()> frameRenderFunc = ([&]
				{
					ULOG(INFO, __FUNCTION__);
				});

				// Initializes the buffer renderer.
				s_bufferRenderer = new BufferRenderer(
					1280,
					720,
					s_Device.Get(),
					frameRenderFunc,
					s_frameBuffer);

				s_frameBuffer->Release();

				messageThread = new std::thread(InitWebRTC);
			}
		}

		return;
	}
}

extern "C" void UNITY_INTERFACE_API OnGraphicsDeviceEvent(UnityGfxDeviceEventType eventType)
{
	ULOG(INFO, __FUNCTION__);

	switch (eventType)
	{
		case kUnityGfxDeviceEventInitialize:
		{
			s_DeviceType = s_Graphics->GetRenderer();
			s_Device = s_UnityInterfaces->Get<IUnityGraphicsD3D11>()->GetDevice();
			s_Device->GetImmediateContext(&s_Context);

			break;
		}

		case kUnityGfxDeviceEventShutdown:
		{
			s_Context.Reset();
			s_Device.Reset();
			s_DeviceType = kUnityGfxRendererNull;

			break;
		}
	}
}



extern "C" void	UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API UnityPluginLoad(IUnityInterfaces* unityInterfaces)
{
	ULOG(INFO, __FUNCTION__);

#if SHOW_CONSOLE
	AllocConsole();
	FILE* out(nullptr);
	freopen_s(&out, "CONOUT$", "w", stdout);

	std::cout << "Console open..." << std::endl;
	ULOG(INFO, "Console open...")
#endif

	s_UnityInterfaces = unityInterfaces;
	s_Graphics = s_UnityInterfaces->Get<IUnityGraphics>();
	s_Graphics->RegisterDeviceEventCallback(OnGraphicsDeviceEvent);

	// Run OnGraphicsDeviceEvent(initialize) manually on plugin load
	OnGraphicsDeviceEvent(kUnityGfxDeviceEventInitialize);
}

extern "C" __declspec(dllexport) void Close()
{
	ULOG(INFO, __FUNCTION__);

	if (s_conductor != nullptr)
	{
		MainWindowCallback *callback = s_conductor;
		callback->DisconnectFromCurrentPeer();
		callback->DisconnectFromServer();

		callback->Close();

		s_conductor = nullptr;

		rtc::CleanupSSL();

		s_closing = true;
	}
}


extern "C" void UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API UnityPluginUnload()
{
	ULOG(INFO, __FUNCTION__);

	s_Graphics->UnregisterDeviceEventCallback(OnGraphicsDeviceEvent);

	Close();
}

extern "C" UnityRenderingEvent UNITY_INTERFACE_EXPORT UNITY_INTERFACE_API GetRenderEventFunc()
{
	ULOG(INFO, __FUNCTION__);

	return OnEncode;
}

extern "C" __declspec(dllexport) void SetInputDataCallback(void(__stdcall*onInputUpdate)(const char *msg))
{
	ULOG(INFO, __FUNCTION__);

	s_onInputUpdate = onInputUpdate;
}

extern "C" __declspec(dllexport) void SetLogCallback(void(__stdcall*onLog)(const int level, const char *msg))
{
	ULOG(INFO, __FUNCTION__);

	s_onLog = onLog;
}
