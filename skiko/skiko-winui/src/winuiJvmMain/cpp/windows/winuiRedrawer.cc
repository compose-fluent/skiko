#ifdef SK_DIRECT3D

#ifdef SKIKO_WINUI_MINGW
#ifndef __REQUIRED_RPCNDR_H_VERSION__
#define __REQUIRED_RPCNDR_H_VERSION__ 475
#endif
#ifndef _In_count_
#define _In_count_(size)
#endif
#ifndef _In_opt_count_
#define _In_opt_count_(size)
#endif
#include <windows.h>
#include <ole2.h>
#else
#include <Windows.h>
#include <microsoft.ui.xaml.media.dxinterop.h>
#endif

#include <d3d12.h>
#include <dxgi1_4.h>
#include <dxgi1_6.h>
#ifndef SKIKO_WINUI_MINGW
#include <jni.h>
#include <string>
#endif
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#include <wrl/client.h>

#ifdef SKIKO_WINUI_MINGW
struct ISwapChainPanelNative : IUnknown {
    virtual HRESULT STDMETHODCALLTYPE SetSwapChain(IDXGISwapChain *swapChain) = 0;
};

static const IID SKIKO_IID_ID3D12Device = {
    0x189819f1,
    0x1db6,
    0x4b57,
    {0xbe, 0x54, 0x18, 0x21, 0x33, 0x9b, 0x85, 0xf7}
};
static const IID SKIKO_IID_ID3D12CommandQueue = {
    0x0ec870a6,
    0x5d7e,
    0x4c22,
    {0x8c, 0xfc, 0x5b, 0xaa, 0xe0, 0x76, 0x16, 0xed}
};
static const IID SKIKO_IID_ID3D12Fence = {
    0x0a753dcf,
    0xc4d8,
    0x4b91,
    {0xad, 0xf6, 0xbe, 0x5a, 0x60, 0xd9, 0x5a, 0x76}
};
static const IID SKIKO_IID_ID3D12Resource = {
    0x696442be,
    0xa72e,
    0x4059,
    {0xbc, 0x79, 0x5b, 0x5c, 0x98, 0x04, 0x0f, 0xad}
};
static const IID SKIKO_IID_IDXGISwapChain3 = {
    0x94d99bdb,
    0xf1f8,
    0x4ab0,
    {0xb2, 0x36, 0x7d, 0xa0, 0x17, 0x0e, 0xda, 0xb1}
};
static const IID SKIKO_IID_IDXGIFactory6 = {
    0xc1b6694f,
    0xff09,
    0x44a9,
    {0xb0, 0x3c, 0x77, 0x90, 0x0a, 0x0a, 0x1d, 0x17}
};
static const IID SKIKO_IID_IDXGIAdapter1 = {
    0x29038f61,
    0x3839,
    0x4626,
    {0x91, 0xfd, 0x08, 0x68, 0x79, 0x01, 0x1a, 0x05}
};

enum SkikoWinUIGpuPreference {
    SkikoWinUIGpuPreferenceUnspecified = 0,
    SkikoWinUIGpuPreferenceMinimumPower = 1,
    SkikoWinUIGpuPreferenceHighPerformance = 2
};

struct SkikoIDXGIFactory6 : IDXGIFactory5 {
    virtual HRESULT STDMETHODCALLTYPE EnumAdapterByGpuPreference(
        UINT Adapter,
        SkikoWinUIGpuPreference GpuPreference,
        REFIID riid,
        void **ppvAdapter
    ) = 0;
};
#endif

extern "C" HRESULT D3D12CreateDevice(
    IUnknown *pAdapter,
    D3D_FEATURE_LEVEL MinimumFeatureLevel,
    REFIID riid,
    void **ppDevice);

extern "C" HRESULT CreateDXGIFactory1(
    REFIID riid,
    void **ppFactory);

extern "C" HRESULT CreateDXGIFactory2(
    UINT Flags,
    REFIID riid,
    void **ppFactory);

namespace {
    const int WinUIBuffersCount = 2;
#ifdef SKIKO_WINUI_MINGW
    thread_local char lastErrorMessage[512] = {};

    void clearLastError() {
        lastErrorMessage[0] = '\0';
    }

    void setLastErrorMessage(const char *operation, const char *message) {
        snprintf(lastErrorMessage, sizeof(lastErrorMessage), "%s failed: %s", operation, message);
    }

    void setLastErrorMessage(const char *operation, const char *message, HRESULT result) {
        snprintf(
            lastErrorMessage,
            sizeof(lastErrorMessage),
            "%s failed: %s returned HRESULT 0x%08lX",
            operation,
            message,
            static_cast<unsigned long>(result)
        );
    }

    void setLastWindowsErrorMessage(const char *operation, const char *message, DWORD errorCode) {
        snprintf(
            lastErrorMessage,
            sizeof(lastErrorMessage),
            "%s failed: %s returned Windows error %lu",
            operation,
            message,
            static_cast<unsigned long>(errorCode)
        );
    }
#else
    void clearLastError() {
    }
#endif

    HRESULT createD3D12Device(
        IUnknown *adapter,
        D3D_FEATURE_LEVEL minimumFeatureLevel,
        REFIID riid,
        void **device
    ) {
        return D3D12CreateDevice(adapter, minimumFeatureLevel, riid, device);
    }

    HRESULT createD3D12Device(
        IUnknown *adapter,
        D3D_FEATURE_LEVEL minimumFeatureLevel,
        ID3D12Device **device
    ) {
#ifdef SKIKO_WINUI_MINGW
        return createD3D12Device(
            adapter,
            minimumFeatureLevel,
            SKIKO_IID_ID3D12Device,
            reinterpret_cast<void **>(device)
        );
#else
        return createD3D12Device(
            adapter,
            minimumFeatureLevel,
            _uuidof(ID3D12Device),
            reinterpret_cast<void **>(device)
        );
#endif
    }

#ifdef SKIKO_WINUI_MINGW
    HRESULT createD3D12DeviceRaw(
        IUnknown *adapter,
        D3D_FEATURE_LEVEL minimumFeatureLevel,
        ID3D12Device **device
    ) {
        return createD3D12Device(
            adapter,
            minimumFeatureLevel,
            SKIKO_IID_ID3D12Device,
            reinterpret_cast<void **>(device)
        );
    }

    HRESULT createCommandQueueRaw(
        ID3D12Device *device,
        const D3D12_COMMAND_QUEUE_DESC *desc,
        ID3D12CommandQueue **queue
    ) {
        return device->CreateCommandQueue(
            desc,
            SKIKO_IID_ID3D12CommandQueue,
            reinterpret_cast<void **>(queue)
        );
    }

    HRESULT createFenceRaw(
        ID3D12Device *device,
        UINT64 initialValue,
        D3D12_FENCE_FLAGS flags,
        ID3D12Fence **fence
    ) {
        return device->CreateFence(
            initialValue,
            flags,
            SKIKO_IID_ID3D12Fence,
            reinterpret_cast<void **>(fence)
        );
    }

    HRESULT getSwapChainBufferRaw(
        IDXGISwapChain3 *swapChain,
        UINT index,
        ID3D12Resource **resource
    ) {
        return swapChain->GetBuffer(
            index,
            SKIKO_IID_ID3D12Resource,
            reinterpret_cast<void **>(resource)
        );
    }

    HRESULT querySwapChain3Raw(
        IDXGISwapChain1 *swapChain,
        IDXGISwapChain3 **swapChain3
    ) {
        return swapChain->QueryInterface(
            SKIKO_IID_IDXGISwapChain3,
            reinterpret_cast<void **>(swapChain3)
        );
    }
#endif

    HRESULT createCommandQueue(
        ID3D12Device *device,
        const D3D12_COMMAND_QUEUE_DESC *desc,
        ID3D12CommandQueue **queue
    ) {
#ifdef SKIKO_WINUI_MINGW
        return device->CreateCommandQueue(
            desc,
            SKIKO_IID_ID3D12CommandQueue,
            reinterpret_cast<void **>(queue)
        );
#else
        return device->CreateCommandQueue(desc, IID_PPV_ARGS(queue));
#endif
    }

    HRESULT createFence(
        ID3D12Device *device,
        UINT64 initialValue,
        D3D12_FENCE_FLAGS flags,
        ID3D12Fence **fence
    ) {
#ifdef SKIKO_WINUI_MINGW
        return device->CreateFence(
            initialValue,
            flags,
            SKIKO_IID_ID3D12Fence,
            reinterpret_cast<void **>(fence)
        );
#else
        return device->CreateFence(initialValue, flags, IID_PPV_ARGS(fence));
#endif
    }

    HRESULT getSwapChainBuffer(
        IDXGISwapChain3 *swapChain,
        UINT index,
        ID3D12Resource **resource
    ) {
#ifdef SKIKO_WINUI_MINGW
        return swapChain->GetBuffer(
            index,
            SKIKO_IID_ID3D12Resource,
            reinterpret_cast<void **>(resource)
        );
#else
        return swapChain->GetBuffer(index, IID_PPV_ARGS(resource));
#endif
    }

    HRESULT querySwapChain3(
        IDXGISwapChain1 *swapChain,
        IDXGISwapChain3 **swapChain3
    ) {
#ifdef SKIKO_WINUI_MINGW
        return swapChain->QueryInterface(
            SKIKO_IID_IDXGISwapChain3,
            reinterpret_cast<void **>(swapChain3)
        );
#else
        return swapChain->QueryInterface(IID_PPV_ARGS(swapChain3));
#endif
    }

    template <typename T>
    T fromNativePointer(void *pointer) {
        return reinterpret_cast<T>(pointer);
    }

    template <typename T>
    void *toNativePointer(T pointer) {
        return reinterpret_cast<void *>(pointer);
    }

#ifndef SKIKO_WINUI_MINGW
    template <typename T>
    T fromJavaPointer(jlong pointer) {
        return reinterpret_cast<T>(static_cast<intptr_t>(pointer));
    }

    template <typename T>
    jlong toJavaPointer(T pointer) {
        return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
    }

    void throwWinUIException(JNIEnv *env, const char *function, const char *message) {
        char fullMessage[768];
        snprintf(fullMessage, sizeof(fullMessage) - 1, "%s: %s", function, message);
        jclass exceptionClass = env->FindClass("org/jetbrains/skiko/winui/WinUIRenderException");
        if (exceptionClass == nullptr) {
            env->ExceptionClear();
            exceptionClass = env->FindClass("java/lang/IllegalStateException");
        }
        if (exceptionClass != nullptr) {
            env->ThrowNew(exceptionClass, fullMessage);
        }
    }

#endif

    class WinUIDirectXDevice {
    public:
        Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
        Microsoft::WRL::ComPtr<ID3D12Device> device;
        Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue;
        Microsoft::WRL::ComPtr<ISwapChainPanelNative> panelNative;
        Microsoft::WRL::ComPtr<IDXGISwapChain3> swapChain;
        Microsoft::WRL::ComPtr<ID3D12Resource> buffers[WinUIBuffersCount];
        Microsoft::WRL::ComPtr<ID3D12Fence> fence;
        uint64_t fenceValues[WinUIBuffersCount];
        HANDLE fenceEvent = NULL;
        unsigned int bufferIndex = 0;

        ~WinUIDirectXDevice() {
            if (fenceEvent != NULL) {
                CloseHandle(fenceEvent);
            }
            for (int i = 0; i < WinUIBuffersCount; i++) {
                buffers[i].Reset();
            }
            fence.Reset();
            if (panelNative.Get() != nullptr) {
                panelNative->SetSwapChain(nullptr);
            }
            swapChain.Reset();
            panelNative.Reset();
            queue.Reset();
            device.Reset();
            adapter.Reset();
        }
    };

    bool createSwapChainForComposition(
        WinUIDirectXDevice *device,
        UINT width,
        UINT height,
        IDXGISwapChain1 **swapChain1
    ) {
        Microsoft::WRL::ComPtr<IDXGIFactory4> swapChainFactory4;
        if (FAILED(CreateDXGIFactory2(0, IID_PPV_ARGS(&swapChainFactory4)))) {
            return false;
        }

        DXGI_SWAP_CHAIN_DESC1 swapChainDesc = {};
        swapChainDesc.Width = width;
        swapChainDesc.Height = height;
        swapChainDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
        swapChainDesc.SampleDesc.Count = 1;
        swapChainDesc.SampleDesc.Quality = 0;
        swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
        swapChainDesc.BufferCount = WinUIBuffersCount;
        swapChainDesc.Scaling = DXGI_SCALING_STRETCH;
        swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
        swapChainDesc.AlphaMode = DXGI_ALPHA_MODE_PREMULTIPLIED;

        return SUCCEEDED(swapChainFactory4->CreateSwapChainForComposition(
            device->queue.Get(),
            &swapChainDesc,
            nullptr,
            swapChain1
        ));
    }

    bool setSwapChainTransform(
        WinUIDirectXDevice *device,
        float contentScaleX,
        float contentScaleY
    ) {
        if (contentScaleX <= 0.0f) {
            contentScaleX = 1.0f;
        }
        if (contentScaleY <= 0.0f) {
            contentScaleY = 1.0f;
        }
        Microsoft::WRL::ComPtr<IDXGISwapChain2> swapChain2;
        if (FAILED(device->swapChain.As(&swapChain2))) {
            return false;
        }
        DXGI_MATRIX_3X2_F matrix = {};
        matrix._11 = 1.0f / contentScaleX;
        matrix._22 = 1.0f / contentScaleY;
        return SUCCEEDED(swapChain2->SetMatrixTransform(&matrix));
    }

#ifdef SKIKO_WINUI_MINGW
    const UINT WinUIMingwDxgiAdapterFlagSoftware = 2;
    const int WinUIGpuPriorityIntegrated = 1;

    bool canCreateD3D12Device(IDXGIAdapter1 *adapter, D3D_FEATURE_LEVEL featureLevel) {
        return SUCCEEDED(createD3D12Device(adapter, featureLevel, SKIKO_IID_ID3D12Device, nullptr));
    }

    bool isBetterAdapterForPriority(
        const DXGI_ADAPTER_DESC1 &candidate,
        const DXGI_ADAPTER_DESC1 &current,
        int32_t adapterPriority
    ) {
        if (adapterPriority == WinUIGpuPriorityIntegrated) {
            return candidate.DedicatedVideoMemory < current.DedicatedVideoMemory;
        }
        return candidate.DedicatedVideoMemory > current.DedicatedVideoMemory;
    }

    void *chooseAdapterByGpuPreference(Microsoft::WRL::ComPtr<IDXGIFactory4> deviceFactory, int32_t adapterPriority) {
        Microsoft::WRL::ComPtr<SkikoIDXGIFactory6> factory6;
        HRESULT queryResult = deviceFactory->QueryInterface(
            SKIKO_IID_IDXGIFactory6,
            reinterpret_cast<void **>(factory6.GetAddressOf())
        );
        if (FAILED(queryResult)) {
            return nullptr;
        }

        for (UINT adapterIndex = 0;; ++adapterIndex) {
            IDXGIAdapter1 *adapter = nullptr;
            HRESULT enumResult = factory6->EnumAdapterByGpuPreference(
                adapterIndex,
                static_cast<SkikoWinUIGpuPreference>(adapterPriority),
                SKIKO_IID_IDXGIAdapter1,
                reinterpret_cast<void **>(&adapter)
            );
            if (FAILED(enumResult)) {
                break;
            }

            DXGI_ADAPTER_DESC1 desc;
            adapter->GetDesc1(&desc);
            const bool isSoftware = (desc.Flags & WinUIMingwDxgiAdapterFlagSoftware) != 0;
            if (!isSoftware && canCreateD3D12Device(adapter, D3D_FEATURE_LEVEL_11_0)) {
                return toNativePointer(adapter);
            }
            adapter->Release();
        }

        return nullptr;
    }
#else
    bool canCreateD3D12Device(IDXGIAdapter1 *adapter, D3D_FEATURE_LEVEL featureLevel) {
        return SUCCEEDED(createD3D12Device(adapter, featureLevel, _uuidof(ID3D12Device), nullptr));
    }
#endif
}

extern "C" {
    void *skiko_winui_chooseAdapter(int32_t adapterPriority);
    void *skiko_winui_createDirectXDeviceForSwapChainPanel(void *adapterPtr, void *panelPtr);
    void *skiko_winui_getAdapterPtr(void *devicePtr);
    void *skiko_winui_getDevicePtr(void *devicePtr);
    void *skiko_winui_getQueuePtr(void *devicePtr);
    bool skiko_winui_initSwapChain(void *devicePtr, int32_t width, int32_t height);
    bool skiko_winui_initFence(void *devicePtr);
    void *skiko_winui_getBufferResourcePtr(void *devicePtr, int32_t index);
    void skiko_winui_releaseBufferResources(void *devicePtr);
    int32_t skiko_winui_getBufferIndex(void *devicePtr);
    bool skiko_winui_present(void *devicePtr, bool isVsyncEnabled);
    bool skiko_winui_resizeBuffers(void *devicePtr, int32_t width, int32_t height);
    bool skiko_winui_setSwapChainTransform(void *devicePtr, float contentScaleX, float contentScaleY);
    void skiko_winui_disposeDevice(void *devicePtr);
    int32_t skiko_winui_getAdapterName(void *adapterPtr, char *buffer, int32_t bufferSize);
    int64_t skiko_winui_getAdapterMemorySize(void *adapterPtr);
    int32_t skiko_winui_getLastErrorMessage(char *buffer, int32_t bufferSize);
    void skiko_winui_throwRenderExceptionForSmoke(const char *message);

#ifndef SKIKO_WINUI_MINGW
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_chooseAdapter(
        JNIEnv *env,
        jobject,
        jint adapterPriority
    ) {
        return toJavaPointer(skiko_winui_chooseAdapter(adapterPriority));
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_createDirectXDeviceForSwapChainPanel(
        JNIEnv *env,
        jobject,
        jlong adapterPtr,
        jlong panelPtr
    ) {
        void *device = skiko_winui_createDirectXDeviceForSwapChainPanel(
            fromJavaPointer<void *>(adapterPtr),
            fromJavaPointer<void *>(panelPtr)
        );
        if (device == nullptr) {
            throwWinUIException(env, __FUNCTION__, "Failed to create Direct3D device for SwapChainPanel");
        }
        return toJavaPointer(device);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterPtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        return toJavaPointer(skiko_winui_getAdapterPtr(fromJavaPointer<void *>(devicePtr)));
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getDevicePtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        return toJavaPointer(skiko_winui_getDevicePtr(fromJavaPointer<void *>(devicePtr)));
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getQueuePtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        return toJavaPointer(skiko_winui_getQueuePtr(fromJavaPointer<void *>(devicePtr)));
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_initSwapChain(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint width,
        jint height
    ) {
        if (!skiko_winui_initSwapChain(fromJavaPointer<void *>(devicePtr), width, height)) {
            throwWinUIException(env, __FUNCTION__, "Failed to initialize swap chain");
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_initFence(
        JNIEnv *env,
        jobject,
        jlong devicePtr
    ) {
        if (!skiko_winui_initFence(fromJavaPointer<void *>(devicePtr))) {
            throwWinUIException(env, __FUNCTION__, "Failed to initialize D3D12 fence");
        }
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getBufferResourcePtr(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint index
    ) {
        void *resource = skiko_winui_getBufferResourcePtr(fromJavaPointer<void *>(devicePtr), index);
        if (resource == nullptr) {
            throwWinUIException(env, __FUNCTION__, "Failed to get swap chain back buffer resource");
        }
        return toJavaPointer(resource);
    }

    JNIEXPORT jint JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getBufferIndex(
        JNIEnv *env,
        jobject,
        jlong devicePtr
    ) {
        return skiko_winui_getBufferIndex(fromJavaPointer<void *>(devicePtr));
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_releaseBufferResources(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        skiko_winui_releaseBufferResources(fromJavaPointer<void *>(devicePtr));
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_present(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jboolean isVsyncEnabled
    ) {
        if (!skiko_winui_present(fromJavaPointer<void *>(devicePtr), isVsyncEnabled)) {
            throwWinUIException(env, __FUNCTION__, "Failed to present swap chain");
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_resizeBuffers(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint width,
        jint height
    ) {
        if (!skiko_winui_resizeBuffers(fromJavaPointer<void *>(devicePtr), width, height)) {
            throwWinUIException(env, __FUNCTION__, "Failed to resize swap chain buffers");
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_setSwapChainTransform(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jfloat contentScaleX,
        jfloat contentScaleY
    ) {
        if (!skiko_winui_setSwapChainTransform(fromJavaPointer<void *>(devicePtr), contentScaleX, contentScaleY)) {
            throwWinUIException(env, __FUNCTION__, "IDXGISwapChain2::SetMatrixTransform failed");
        }
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_disposeDevice(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        skiko_winui_disposeDevice(fromJavaPointer<void *>(devicePtr));
    }

    JNIEXPORT jstring JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterName(
        JNIEnv *env,
        jobject,
        jlong adapterPtr
    ) {
        int32_t requiredSize = skiko_winui_getAdapterName(fromJavaPointer<void *>(adapterPtr), nullptr, 0);
        if (requiredSize <= 0) {
            return env->NewStringUTF("");
        }
        std::string name(static_cast<size_t>(requiredSize + 1), '\0');
        skiko_winui_getAdapterName(
            fromJavaPointer<void *>(adapterPtr),
            name.data(),
            requiredSize + 1
        );
        return env->NewStringUTF(name.c_str());
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterMemorySize(
        JNIEnv *,
        jobject,
        jlong adapterPtr
    ) {
        return static_cast<jlong>(skiko_winui_getAdapterMemorySize(fromJavaPointer<void *>(adapterPtr)));
    }

    JNIEXPORT jstring JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getLastErrorMessage(
        JNIEnv *env,
        jobject
    ) {
        int32_t requiredSize = skiko_winui_getLastErrorMessage(nullptr, 0);
        if (requiredSize <= 0) {
            return env->NewStringUTF("");
        }
        std::string message(static_cast<size_t>(requiredSize + 1), '\0');
        skiko_winui_getLastErrorMessage(message.data(), requiredSize + 1);
        return env->NewStringUTF(message.c_str());
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_throwRenderExceptionForSmoke(
        JNIEnv *env,
        jobject,
        jstring message
    ) {
        const char *rawMessage = env->GetStringUTFChars(message, nullptr);
        if (rawMessage == nullptr) {
            return;
        }
        throwWinUIException(env, __FUNCTION__, rawMessage);
        env->ReleaseStringUTFChars(message, rawMessage);
    }
#endif

    void *skiko_winui_chooseAdapter(int32_t adapterPriority) {
        clearLastError();
        Microsoft::WRL::ComPtr<IDXGIFactory4> deviceFactory;
        HRESULT factoryResult = CreateDXGIFactory1(IID_PPV_ARGS(&deviceFactory));
        if (FAILED(factoryResult)) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "CreateDXGIFactory1", factoryResult);
#endif
            return nullptr;
        }

#ifdef SKIKO_WINUI_MINGW
        void *adapterByGpuPreference = chooseAdapterByGpuPreference(deviceFactory, adapterPriority);
        if (adapterByGpuPreference != nullptr) {
            return adapterByGpuPreference;
        }

        IDXGIAdapter1 *bestAdapter = nullptr;
        DXGI_ADAPTER_DESC1 bestDesc = {};
        bestDesc.DedicatedVideoMemory = adapterPriority == WinUIGpuPriorityIntegrated
            ? static_cast<SIZE_T>(-1)
            : 0;
        for (UINT adapterIndex = 0;; ++adapterIndex) {
            IDXGIAdapter1 *adapter = nullptr;
            if (!SUCCEEDED(deviceFactory->EnumAdapters1(adapterIndex, &adapter))) {
                break;
            }

            DXGI_ADAPTER_DESC1 desc;
            adapter->GetDesc1(&desc);
            const bool isSoftware = (desc.Flags & WinUIMingwDxgiAdapterFlagSoftware) != 0;
            if (!isSoftware && canCreateD3D12Device(adapter, D3D_FEATURE_LEVEL_11_0)) {
                if (bestAdapter == nullptr || isBetterAdapterForPriority(desc, bestDesc, adapterPriority)) {
                    if (bestAdapter != nullptr) {
                        bestAdapter->Release();
                    }
                    bestAdapter = adapter;
                    bestDesc = desc;
                } else {
                    adapter->Release();
                }
            } else {
                adapter->Release();
            }
        }
        if (bestAdapter != nullptr) {
            return toNativePointer(bestAdapter);
        }
        setLastErrorMessage(__FUNCTION__, "No non-software D3D12 adapter found");
#else
        Microsoft::WRL::ComPtr<IDXGIFactory6> factory6;
        if (!SUCCEEDED(deviceFactory->QueryInterface(IID_PPV_ARGS(&factory6)))) {
            return nullptr;
        }

        for (UINT adapterIndex = 0;; ++adapterIndex) {
            IDXGIAdapter1 *adapter = nullptr;
            if (!SUCCEEDED(factory6->EnumAdapterByGpuPreference(
                    adapterIndex,
                    static_cast<DXGI_GPU_PREFERENCE>(adapterPriority),
                    IID_PPV_ARGS(&adapter)
                ))) {
                break;
            }

            DXGI_ADAPTER_DESC1 desc;
            adapter->GetDesc1(&desc);
            const bool isSoftware = (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) != 0;
            if (!isSoftware && SUCCEEDED(D3D12CreateDevice(
                    adapter,
                    D3D_FEATURE_LEVEL_11_0,
                    _uuidof(ID3D12Device),
                    nullptr
                ))) {
                return toNativePointer(adapter);
            }
            adapter->Release();
        }
#endif

        return nullptr;
    }

    void *skiko_winui_createDirectXDeviceForSwapChainPanel(
        void *adapterPtr,
        void *panelPtr
    ) {
        clearLastError();
        if (adapterPtr == nullptr || panelPtr == nullptr) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "Adapter or SwapChainPanel pointer is null");
#endif
            return nullptr;
        }

        Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
        adapter.Attach(fromNativePointer<IDXGIAdapter1 *>(adapterPtr));
        ISwapChainPanelNative *panelNativeRaw = fromNativePointer<ISwapChainPanelNative *>(panelPtr);
        panelNativeRaw->AddRef();
        Microsoft::WRL::ComPtr<ISwapChainPanelNative> panelNative;
        panelNative.Attach(panelNativeRaw);

        D3D_FEATURE_LEVEL maxSupportedFeatureLevel = D3D_FEATURE_LEVEL_12_0;
        D3D_FEATURE_LEVEL featureLevels[] = {
            D3D_FEATURE_LEVEL_12_1,
            D3D_FEATURE_LEVEL_12_0
        };

        for (int i = 0; i < _countof(featureLevels); i++) {
            if (canCreateD3D12Device(adapter.Get(), featureLevels[i])) {
                maxSupportedFeatureLevel = featureLevels[i];
                break;
            }
        }

        Microsoft::WRL::ComPtr<ID3D12Device> d3dDevice;
#ifdef SKIKO_WINUI_MINGW
        ID3D12Device *d3dDeviceRaw = nullptr;
        HRESULT deviceResult = createD3D12DeviceRaw(adapter.Get(), maxSupportedFeatureLevel, &d3dDeviceRaw);
        if (FAILED(deviceResult)) {
            setLastErrorMessage(__FUNCTION__, "D3D12CreateDevice", deviceResult);
            return nullptr;
        }
        d3dDevice.Attach(d3dDeviceRaw);
#else
        if (FAILED(createD3D12Device(adapter.Get(), maxSupportedFeatureLevel, &d3dDevice))) {
            return nullptr;
        }
#endif

        D3D12_COMMAND_QUEUE_DESC queueDesc = {};
        queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
        queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;

        Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue;
#ifdef SKIKO_WINUI_MINGW
        ID3D12CommandQueue *queueRaw = nullptr;
        HRESULT queueResult = createCommandQueueRaw(d3dDevice.Get(), &queueDesc, &queueRaw);
        if (FAILED(queueResult)) {
            setLastErrorMessage(__FUNCTION__, "ID3D12Device::CreateCommandQueue", queueResult);
            return nullptr;
        }
        queue.Attach(queueRaw);
#else
        if (FAILED(createCommandQueue(d3dDevice.Get(), &queueDesc, &queue))) {
            return nullptr;
        }
#endif

        WinUIDirectXDevice *result = new WinUIDirectXDevice();
        result->adapter = adapter;
        result->device = d3dDevice;
        result->queue = queue;
        result->panelNative = panelNative;

        return toNativePointer(result);
    }

    void *skiko_winui_getAdapterPtr(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        return toNativePointer(device->adapter.Get());
    }

    void *skiko_winui_getDevicePtr(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        return toNativePointer(device->device.Get());
    }

    void *skiko_winui_getQueuePtr(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        return toNativePointer(device->queue.Get());
    }

    bool skiko_winui_initSwapChain(void *devicePtr, int32_t width, int32_t height) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        Microsoft::WRL::ComPtr<IDXGISwapChain1> swapChain1;
        if (!createSwapChainForComposition(device, static_cast<UINT>(width), static_cast<UINT>(height), &swapChain1)) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "CreateSwapChainForComposition");
#endif
            return false;
        }
#ifdef SKIKO_WINUI_MINGW
        IDXGISwapChain3 *swapChain3Raw = nullptr;
        HRESULT queryResult = querySwapChain3Raw(swapChain1.Get(), &swapChain3Raw);
        if (FAILED(queryResult)) {
            setLastErrorMessage(__FUNCTION__, "QueryInterface(IDXGISwapChain3)", queryResult);
            return false;
        }
        device->swapChain.Attach(swapChain3Raw);
#else
        if (FAILED(querySwapChain3(swapChain1.Get(), &device->swapChain))) {
            return false;
        }
#endif
        HRESULT setResult = device->panelNative->SetSwapChain(device->swapChain.Get());
#ifdef SKIKO_WINUI_MINGW
        if (FAILED(setResult)) {
            setLastErrorMessage(__FUNCTION__, "ISwapChainPanelNative::SetSwapChain", setResult);
        }
#endif
        return SUCCEEDED(setResult);
    }

    bool skiko_winui_initFence(void *devicePtr) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        for (int i = 0; i < WinUIBuffersCount; i++) {
            device->fenceValues[i] = 10000;
        }
#ifdef SKIKO_WINUI_MINGW
        ID3D12Fence *fenceRaw = nullptr;
        HRESULT fenceResult = createFenceRaw(device->device.Get(), device->fenceValues[0], D3D12_FENCE_FLAG_NONE, &fenceRaw);
        if (FAILED(fenceResult)) {
            setLastErrorMessage(__FUNCTION__, "ID3D12Device::CreateFence", fenceResult);
            return false;
        }
        device->fence.Attach(fenceRaw);
#else
        if (FAILED(createFence(device->device.Get(), device->fenceValues[0], D3D12_FENCE_FLAG_NONE, &device->fence))) {
            return false;
        }
#endif
        device->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
#ifdef SKIKO_WINUI_MINGW
        if (device->fenceEvent == NULL) {
            setLastWindowsErrorMessage(__FUNCTION__, "CreateEvent", GetLastError());
        }
#endif
        return device->fenceEvent != NULL;
    }

    void *skiko_winui_getBufferResourcePtr(void *devicePtr, int32_t index) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        if (index < 0 || index >= WinUIBuffersCount) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "Back buffer index is out of range");
#endif
            return nullptr;
        }
        device->buffers[index].Reset();
        HRESULT bufferResult = getSwapChainBuffer(device->swapChain.Get(), index, &device->buffers[index]);
        if (FAILED(bufferResult)) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "IDXGISwapChain::GetBuffer", bufferResult);
#endif
            return nullptr;
        }
        return toNativePointer(device->buffers[index].Get());
    }

    void skiko_winui_releaseBufferResources(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        if (device == nullptr) {
            return;
        }
        for (int i = 0; i < WinUIBuffersCount; i++) {
            device->buffers[i].Reset();
        }
    }

    int32_t skiko_winui_getBufferIndex(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        const UINT64 fenceValue = device->fenceValues[device->bufferIndex];
        device->bufferIndex = device->swapChain->GetCurrentBackBufferIndex();
        if (device->fence->GetCompletedValue() < fenceValue) {
            device->fence->SetEventOnCompletion(fenceValue, device->fenceEvent);
            WaitForSingleObjectEx(device->fenceEvent, INFINITE, FALSE);
        }
        device->fenceValues[device->bufferIndex] = fenceValue + 1;
        return static_cast<int32_t>(device->bufferIndex);
    }

    bool skiko_winui_present(void *devicePtr, bool isVsyncEnabled) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        const UINT64 fenceValue = device->fenceValues[device->bufferIndex];
        HRESULT presentResult = device->swapChain->Present(static_cast<int>(isVsyncEnabled), 0);
        if (FAILED(presentResult)) {
#ifdef SKIKO_WINUI_MINGW
            setLastErrorMessage(__FUNCTION__, "IDXGISwapChain::Present", presentResult);
#endif
            return false;
        }
        HRESULT signalResult = device->queue->Signal(device->fence.Get(), fenceValue);
#ifdef SKIKO_WINUI_MINGW
        if (FAILED(signalResult)) {
            setLastErrorMessage(__FUNCTION__, "ID3D12CommandQueue::Signal", signalResult);
        }
#endif
        return SUCCEEDED(signalResult);
    }

    bool skiko_winui_resizeBuffers(void *devicePtr, int32_t width, int32_t height) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        for (int i = 0; i < WinUIBuffersCount; i++) {
            if (device->fence->GetCompletedValue() < device->fenceValues[i]) {
                device->fence->SetEventOnCompletion(device->fenceValues[i], device->fenceEvent);
                WaitForSingleObjectEx(device->fenceEvent, INFINITE, FALSE);
            }
        }
        for (int i = 0; i < WinUIBuffersCount; i++) {
            device->buffers[i].Reset();
        }
        HRESULT resizeResult = device->swapChain->ResizeBuffers(
            WinUIBuffersCount,
            width,
            height,
            DXGI_FORMAT_R8G8B8A8_UNORM,
            0
        );
#ifdef SKIKO_WINUI_MINGW
        if (FAILED(resizeResult)) {
            setLastErrorMessage(__FUNCTION__, "IDXGISwapChain::ResizeBuffers", resizeResult);
        }
#endif
        return SUCCEEDED(resizeResult);
    }

    bool skiko_winui_setSwapChainTransform(
        void *devicePtr,
        float contentScaleX,
        float contentScaleY
    ) {
        clearLastError();
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        bool success = setSwapChainTransform(device, contentScaleX, contentScaleY);
#ifdef SKIKO_WINUI_MINGW
        if (!success) {
            setLastErrorMessage(__FUNCTION__, "IDXGISwapChain2::SetMatrixTransform");
        }
#endif
        return success;
    }

    void skiko_winui_disposeDevice(void *devicePtr) {
        WinUIDirectXDevice *device = fromNativePointer<WinUIDirectXDevice *>(devicePtr);
        delete device;
    }

    int32_t skiko_winui_getAdapterName(void *adapterPtr, char *buffer, int32_t bufferSize) {
        if (adapterPtr == nullptr) {
            if (buffer != nullptr && bufferSize > 0) {
                buffer[0] = '\0';
            }
            return 0;
        }

        IDXGIAdapter1 *adapter = fromNativePointer<IDXGIAdapter1 *>(adapterPtr);
        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        int requiredSizeWithNull = WideCharToMultiByte(
            CP_UTF8,
            0,
            desc.Description,
            -1,
            nullptr,
            0,
            nullptr,
            nullptr
        );
        if (requiredSizeWithNull <= 1) {
            if (buffer != nullptr && bufferSize > 0) {
                buffer[0] = '\0';
            }
            return 0;
        }
        if (buffer != nullptr && bufferSize > 0) {
            WideCharToMultiByte(
                CP_UTF8,
                0,
                desc.Description,
                -1,
                buffer,
                bufferSize,
                nullptr,
                nullptr
            );
            buffer[bufferSize - 1] = '\0';
        }
        return static_cast<int32_t>(requiredSizeWithNull - 1);
    }

    int64_t skiko_winui_getAdapterMemorySize(void *adapterPtr) {
        if (adapterPtr == nullptr) {
            return 0;
        }

        IDXGIAdapter1 *adapter = fromNativePointer<IDXGIAdapter1 *>(adapterPtr);
        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        return static_cast<int64_t>(desc.DedicatedVideoMemory);
    }

    int32_t skiko_winui_getLastErrorMessage(char *buffer, int32_t bufferSize) {
#ifdef SKIKO_WINUI_MINGW
        int32_t requiredSize = static_cast<int32_t>(strlen(lastErrorMessage));
        if (buffer != nullptr && bufferSize > 0) {
            snprintf(buffer, static_cast<size_t>(bufferSize), "%s", lastErrorMessage);
            buffer[bufferSize - 1] = '\0';
        }
        return requiredSize;
#else
        if (buffer != nullptr && bufferSize > 0) {
            buffer[0] = '\0';
        }
        return 0;
#endif
    }

    void skiko_winui_throwRenderExceptionForSmoke(const char *) {
    }
}

#endif
