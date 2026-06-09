#ifdef SK_DIRECT3D

#include <Windows.h>
#include <microsoft.ui.xaml.media.dxinterop.h>

#include <d3d12.h>
#include <dxgi1_4.h>
#include <dxgi1_6.h>
#include <jni.h>
#include <cstdint>
#include <cstdio>
#include <string>

#include <wrl/client.h>

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

    void throwWinUIExceptionByErrorCode(JNIEnv *env, const char *function, DWORD errorCode) {
        char message[256];
        snprintf(message, sizeof(message) - 1, "Windows error %lu", static_cast<unsigned long>(errorCode));
        throwWinUIException(env, function, message);
    }

    std::string toUtf8(const wchar_t *value) {
        if (value == nullptr) {
            return "";
        }
        int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
        if (size <= 1) {
            return "";
        }
        std::string result(static_cast<size_t>(size - 1), '\0');
        WideCharToMultiByte(CP_UTF8, 0, value, -1, result.data(), size, nullptr, nullptr);
        return result;
    }

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

    bool failed(JNIEnv *env, const char *function, HRESULT result, const char *message) {
        if (SUCCEEDED(result)) {
            return false;
        }
        char fullMessage[512];
        snprintf(
            fullMessage,
            sizeof(fullMessage) - 1,
            "%s failed with HRESULT 0x%08lX",
            message,
            static_cast<unsigned long>(result)
        );
        throwWinUIException(env, function, fullMessage);
        return true;
    }

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
        JNIEnv *env,
        const char *function,
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
        if (failed(env, function, device->swapChain.As(&swapChain2), "QueryInterface(IDXGISwapChain2)")) {
            return false;
        }
        DXGI_MATRIX_3X2_F matrix = {};
        matrix._11 = 1.0f / contentScaleX;
        matrix._22 = 1.0f / contentScaleY;
        return !failed(
            env,
            function,
            swapChain2->SetMatrixTransform(&matrix),
            "IDXGISwapChain2::SetMatrixTransform"
        );
    }
}

extern "C" {
    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_chooseAdapter(
        JNIEnv *env,
        jobject,
        jint adapterPriority
    ) {
        Microsoft::WRL::ComPtr<IDXGIFactory4> deviceFactory;
        if (!SUCCEEDED(CreateDXGIFactory1(IID_PPV_ARGS(&deviceFactory)))) {
            return 0;
        }

        Microsoft::WRL::ComPtr<IDXGIFactory6> factory6;
        if (!SUCCEEDED(deviceFactory->QueryInterface(IID_PPV_ARGS(&factory6)))) {
            return 0;
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
                return toJavaPointer(adapter);
            }
            adapter->Release();
        }

        return 0;
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_createDirectXDeviceForSwapChainPanel(
        JNIEnv *env,
        jobject,
        jlong adapterPtr,
        jlong panelPtr
    ) {
        if (adapterPtr == 0 || panelPtr == 0) {
            return 0;
        }

        Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
        adapter.Attach(fromJavaPointer<IDXGIAdapter1 *>(adapterPtr));
        ISwapChainPanelNative *panelNativeRaw = fromJavaPointer<ISwapChainPanelNative *>(panelPtr);
        panelNativeRaw->AddRef();
        Microsoft::WRL::ComPtr<ISwapChainPanelNative> panelNative;
        panelNative.Attach(panelNativeRaw);

        D3D_FEATURE_LEVEL maxSupportedFeatureLevel = D3D_FEATURE_LEVEL_12_0;
        D3D_FEATURE_LEVEL featureLevels[] = {
            D3D_FEATURE_LEVEL_12_1,
            D3D_FEATURE_LEVEL_12_0
        };

        for (int i = 0; i < _countof(featureLevels); i++) {
            if (SUCCEEDED(D3D12CreateDevice(adapter.Get(), featureLevels[i], _uuidof(ID3D12Device), nullptr))) {
                maxSupportedFeatureLevel = featureLevels[i];
                break;
            }
        }

        Microsoft::WRL::ComPtr<ID3D12Device> d3dDevice;
        if (failed(
                env,
                __FUNCTION__,
                D3D12CreateDevice(adapter.Get(), maxSupportedFeatureLevel, IID_PPV_ARGS(&d3dDevice)),
                "D3D12CreateDevice"
            )) {
            return 0;
        }

        D3D12_COMMAND_QUEUE_DESC queueDesc = {};
        queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
        queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;

        Microsoft::WRL::ComPtr<ID3D12CommandQueue> queue;
        if (failed(
                env,
                __FUNCTION__,
                d3dDevice->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&queue)),
                "ID3D12Device::CreateCommandQueue"
            )) {
            return 0;
        }

        WinUIDirectXDevice *result = new WinUIDirectXDevice();
        result->adapter = adapter;
        result->device = d3dDevice;
        result->queue = queue;
        result->panelNative = panelNative;

        return toJavaPointer(result);
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterPtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        return toJavaPointer(device->adapter.Get());
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getDevicePtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        return toJavaPointer(device->device.Get());
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getQueuePtr(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        return toJavaPointer(device->queue.Get());
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_initSwapChain(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint width,
        jint height
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        Microsoft::WRL::ComPtr<IDXGISwapChain1> swapChain1;
        if (!createSwapChainForComposition(device, static_cast<UINT>(width), static_cast<UINT>(height), &swapChain1)) {
            throwWinUIException(env, __FUNCTION__, "CreateSwapChainForComposition failed");
            return;
        }
        if (failed(env, __FUNCTION__, swapChain1->QueryInterface(IID_PPV_ARGS(&device->swapChain)), "QueryInterface(IDXGISwapChain3)")) {
            return;
        }
        failed(env, __FUNCTION__, device->panelNative->SetSwapChain(device->swapChain.Get()), "ISwapChainPanelNative::SetSwapChain");
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_initFence(
        JNIEnv *env,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        for (int i = 0; i < WinUIBuffersCount; i++) {
            device->fenceValues[i] = 10000;
        }
        failed(
            env,
            __FUNCTION__,
            device->device->CreateFence(device->fenceValues[0], D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&device->fence)),
            "ID3D12Device::CreateFence"
        );
        device->fenceEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
        if (device->fenceEvent == NULL) {
            throwWinUIExceptionByErrorCode(env, __FUNCTION__, GetLastError());
        }
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getBufferResourcePtr(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint index
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        if (index < 0 || index >= WinUIBuffersCount) {
            throwWinUIException(env, __FUNCTION__, "Back buffer index is out of range");
            return 0;
        }
        device->buffers[index].Reset();
        if (failed(
                env,
                __FUNCTION__,
                device->swapChain->GetBuffer(index, IID_PPV_ARGS(&device->buffers[index])),
                "IDXGISwapChain::GetBuffer"
            )) {
            return 0;
        }
        return toJavaPointer(device->buffers[index].Get());
    }

    JNIEXPORT jint JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getBufferIndex(
        JNIEnv *env,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        const UINT64 fenceValue = device->fenceValues[device->bufferIndex];
        device->bufferIndex = device->swapChain->GetCurrentBackBufferIndex();
        if (device->fence->GetCompletedValue() < fenceValue) {
            device->fence->SetEventOnCompletion(fenceValue, device->fenceEvent);
            WaitForSingleObjectEx(device->fenceEvent, INFINITE, FALSE);
        }
        device->fenceValues[device->bufferIndex] = fenceValue + 1;
        return device->bufferIndex;
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_present(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jboolean isVsyncEnabled
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        const UINT64 fenceValue = device->fenceValues[device->bufferIndex];
        failed(env, __FUNCTION__, device->swapChain->Present(static_cast<int>(isVsyncEnabled), 0), "IDXGISwapChain::Present");
        failed(env, __FUNCTION__, device->queue->Signal(device->fence.Get(), fenceValue), "ID3D12CommandQueue::Signal");
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_resizeBuffers(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jint width,
        jint height
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        for (int i = 0; i < WinUIBuffersCount; i++) {
            if (device->fence->GetCompletedValue() < device->fenceValues[i]) {
                device->fence->SetEventOnCompletion(device->fenceValues[i], device->fenceEvent);
                WaitForSingleObjectEx(device->fenceEvent, INFINITE, FALSE);
            }
        }
        for (int i = 0; i < WinUIBuffersCount; i++) {
            device->buffers[i].Reset();
        }
        failed(
            env,
            __FUNCTION__,
            device->swapChain->ResizeBuffers(WinUIBuffersCount, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0),
            "IDXGISwapChain::ResizeBuffers"
        );
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_setSwapChainTransform(
        JNIEnv *env,
        jobject,
        jlong devicePtr,
        jfloat contentScaleX,
        jfloat contentScaleY
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        setSwapChainTransform(env, __FUNCTION__, device, contentScaleX, contentScaleY);
    }

    JNIEXPORT void JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_disposeDevice(
        JNIEnv *,
        jobject,
        jlong devicePtr
    ) {
        WinUIDirectXDevice *device = fromJavaPointer<WinUIDirectXDevice *>(devicePtr);
        delete device;
    }

    JNIEXPORT jstring JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterName(
        JNIEnv *env,
        jobject,
        jlong adapterPtr
    ) {
        IDXGIAdapter1 *adapter = fromJavaPointer<IDXGIAdapter1 *>(adapterPtr);

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        std::string name = toUtf8(desc.Description);
        return env->NewStringUTF(name.c_str());
    }

    JNIEXPORT jlong JNICALL Java_org_jetbrains_skiko_winui_WinUISkiaLayerNative_getAdapterMemorySize(
        JNIEnv *,
        jobject,
        jlong adapterPtr
    ) {
        IDXGIAdapter1 *adapter = fromJavaPointer<IDXGIAdapter1 *>(adapterPtr);

        DXGI_ADAPTER_DESC1 desc;
        adapter->GetDesc1(&desc);
        return static_cast<jlong>(desc.DedicatedVideoMemory);
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
}

#endif
