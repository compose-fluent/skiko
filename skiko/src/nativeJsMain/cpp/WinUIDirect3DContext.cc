// A Direct3D context for the WinUI layers of skiko-winui (winuiMingw), with a memory allocator that
// places every Skia resource in a heap of its own size.
//
// Without an allocator Skia uses D3D12MA with its default block size of 256 MB: the first block of a
// heap type takes 32 MB, the next ones 64, 128 and 256 MB, however little of them is used. A WinUI
// window holds a few textures (glyph atlases, images) and small buffers, so most of that video memory
// stays unused, and every layer (window, popup, dialog) has a context of its own.

#ifdef SK_DIRECT3D

#include "ganesh/GrDirectContext.h"
#include "ganesh/d3d/GrD3DBackendContext.h"
#include "ganesh/d3d/GrD3DDirectContext.h"
#include "ganesh/d3d/GrD3DTypes.h"
#include "common.h"

#include <atomic>
#include <chrono>

namespace {

// The bytes of the heaps of all the contexts, for the memory statistics.
std::atomic<int64_t> gHeapBytes{0};

class WinUIHeapAlloc final : public GrD3DAlloc {
public:
    WinUIHeapAlloc(gr_cp<ID3D12Heap> heap, uint64_t size) : fHeap(std::move(heap)), fSize(size) {
        gHeapBytes += static_cast<int64_t>(fSize);
    }
    ~WinUIHeapAlloc() override { gHeapBytes -= static_cast<int64_t>(fSize); }
    ID3D12Heap* heap() const { return fHeap.get(); }

private:
    gr_cp<ID3D12Heap> fHeap;
    uint64_t fSize;
};

D3D12_HEAP_FLAGS heapFlagsFor(const D3D12_RESOURCE_DESC& desc) {
    if (desc.Dimension == D3D12_RESOURCE_DIMENSION_BUFFER) {
        return D3D12_HEAP_FLAG_ALLOW_ONLY_BUFFERS;
    }
    if (desc.Flags & (D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET | D3D12_RESOURCE_FLAG_ALLOW_DEPTH_STENCIL)) {
        return D3D12_HEAP_FLAG_ALLOW_ONLY_RT_DS_TEXTURES;
    }
    return D3D12_HEAP_FLAG_ALLOW_ONLY_NON_RT_DS_TEXTURES;
}

// One heap per resource: no memory is reserved beyond what a resource needs (heaps are 64 KB
// granular). Skia keeps its resources in its resource cache and suballocates its per-frame data
// from ring buffers, so it creates few resources and the cost of a heap per creation stays small.
class WinUIPerResourceHeapAllocator final : public GrD3DMemoryAllocator {
public:
    explicit WinUIPerResourceHeapAllocator(gr_cp<ID3D12Device> device) : fDevice(std::move(device)) {}

    gr_cp<ID3D12Resource> createResource(D3D12_HEAP_TYPE heapType,
                                         const D3D12_RESOURCE_DESC* resourceDesc,
                                         D3D12_RESOURCE_STATES initialResourceState,
                                         sk_sp<GrD3DAlloc>* allocation,
                                         const D3D12_CLEAR_VALUE* clearValue) override {
        D3D12_RESOURCE_ALLOCATION_INFO info = fDevice->GetResourceAllocationInfo(0, 1, resourceDesc);
        if (info.SizeInBytes == UINT64_MAX || info.SizeInBytes == 0) {
            return nullptr;
        }
        D3D12_HEAP_DESC heapDesc = {};
        heapDesc.SizeInBytes = info.SizeInBytes;
        heapDesc.Properties.Type = heapType;
        heapDesc.Properties.CPUPageProperty = D3D12_CPU_PAGE_PROPERTY_UNKNOWN;
        heapDesc.Properties.MemoryPoolPreference = D3D12_MEMORY_POOL_UNKNOWN;
        heapDesc.Alignment = info.Alignment;
        heapDesc.Flags = heapFlagsFor(*resourceDesc);
        gr_cp<ID3D12Heap> heap;
        if (FAILED(fDevice->CreateHeap(&heapDesc, IID_PPV_ARGS(&heap)))) {
            return nullptr;
        }
        gr_cp<ID3D12Resource> resource;
        if (FAILED(fDevice->CreatePlacedResource(heap.get(), 0, resourceDesc, initialResourceState,
                                                 clearValue, IID_PPV_ARGS(&resource)))) {
            return nullptr;
        }
        *allocation = sk_make_sp<WinUIHeapAlloc>(std::move(heap), info.SizeInBytes);
        return resource;
    }

    // Skia aliases a texture to view it in another format, at offset 0 of its allocation.
    gr_cp<ID3D12Resource> createAliasingResource(sk_sp<GrD3DAlloc>& allocation,
                                                 uint64_t localOffset,
                                                 const D3D12_RESOURCE_DESC* resourceDesc,
                                                 D3D12_RESOURCE_STATES initialResourceState,
                                                 const D3D12_CLEAR_VALUE* clearValue) override {
        auto* alloc = static_cast<WinUIHeapAlloc*>(allocation.get());
        if (alloc == nullptr) {
            return nullptr;
        }
        gr_cp<ID3D12Resource> resource;
        if (FAILED(fDevice->CreatePlacedResource(alloc->heap(), localOffset, resourceDesc,
                                                 initialResourceState, clearValue,
                                                 IID_PPV_ARGS(&resource)))) {
            return nullptr;
        }
        return resource;
    }

private:
    gr_cp<ID3D12Device> fDevice;
};

}  // namespace

SKIKO_EXPORT KNativePointer org_jetbrains_skiko_winui_WinUIDirect3D__1nMakeContext
  (KNativePointer adapterPtr, KNativePointer devicePtr, KNativePointer queuePtr) {
    IDXGIAdapter1* adapter = reinterpret_cast<IDXGIAdapter1*>(adapterPtr);
    ID3D12Device* device = reinterpret_cast<ID3D12Device*>(devicePtr);
    ID3D12CommandQueue* queue = reinterpret_cast<ID3D12CommandQueue*>(queuePtr);
    GrD3DBackendContext backendContext = {};
    backendContext.fAdapter.retain(adapter);
    backendContext.fDevice.retain(device);
    backendContext.fQueue.retain(queue);
    gr_cp<ID3D12Device> allocatorDevice;
    allocatorDevice.retain(device);
    backendContext.fMemoryAllocator = sk_make_sp<WinUIPerResourceHeapAllocator>(std::move(allocatorDevice));
    sk_sp<GrDirectContext> instance = GrDirectContexts::MakeD3D(backendContext);
    return static_cast<KNativePointer>(instance.release());
}

// Frees the resources of the cache that no frame used for the given time.
SKIKO_EXPORT void org_jetbrains_skiko_winui_WinUIDirect3D__1nPerformDeferredCleanup
  (KNativePointer contextPtr, KLong notUsedMillis) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(contextPtr);
    context->performDeferredCleanup(std::chrono::milliseconds(notUsedMillis));
}

// Fills `result` with the resource count, the bytes and the purgeable bytes of the resource cache,
// and the bytes of the heaps of all the contexts.
SKIKO_EXPORT void org_jetbrains_skiko_winui_WinUIDirect3D__1nGetMemoryUsage
  (KNativePointer contextPtr, KLong* result) {
    GrDirectContext* context = reinterpret_cast<GrDirectContext*>(contextPtr);
    int count = 0;
    size_t bytes = 0;
    context->getResourceCacheUsage(&count, &bytes);
    result[0] = count;
    result[1] = static_cast<KLong>(bytes);
    result[2] = static_cast<KLong>(context->getResourceCachePurgeableBytes());
    result[3] = gHeapBytes.load();
}

#endif  // SK_DIRECT3D
