#define NS_PRIVATE_IMPLEMENTATION
#define CA_PRIVATE_IMPLEMENTATION
#define MTL_PRIVATE_IMPLEMENTATION

#include <string>
#include <stdio.h>
#include <errno.h>
#include <jni.h>

#include <sys/mman.h>
#include <sys/stat.h>

#include <fcntl.h>
#include <unistd.h>

// The amalgamated metal-cpp single header (committed alongside this file) provides
// the NS, MTL and CA namespaces, so the build is self-contained and does not depend
// on an external metal-cpp installation path.
#include "Metal.hpp"

// Convert single float to bfloat16
uint16_t float32_to_bfloat16(float src) {
    uint32_t src_int;
    memcpy(&src_int, &src, sizeof(float));
    return (uint16_t)(src_int >> 16);
}

// Convert single bfloat16 to float
float bfloat16_to_float32(uint16_t src) {
    uint32_t result = ((uint32_t)src) << 16;
    float result_float;
    memcpy(&result_float, &result, sizeof(float));
    return result_float;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createSystemDefaultDevice(JNIEnv* env, jclass cls) {
    MTL::Device* device = MTLCreateSystemDefaultDevice();
    return (jlong) device;
}

extern "C"
JNIEXPORT jint JNICALL Java_org_almostrealism_hardware_metal_MTL_maxThreadgroupWidth(JNIEnv* env, jclass cls, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    return (jint) dev->maxThreadsPerThreadgroup().width;
}

extern "C"
JNIEXPORT jint JNICALL Java_org_almostrealism_hardware_metal_MTL_maxThreadgroupHeight(JNIEnv* env, jclass cls, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    return (jint) dev->maxThreadsPerThreadgroup().height;
}

extern "C"
JNIEXPORT jint JNICALL Java_org_almostrealism_hardware_metal_MTL_maxThreadgroupDepth(JNIEnv* env, jclass cls, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    return (jint) dev->maxThreadsPerThreadgroup().depth;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createCommandQueue(JNIEnv* env, jclass cls, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::CommandQueue* queue = dev->newCommandQueue();
    return (jlong) queue;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_commandBuffer(JNIEnv* env, jclass cls, jlong queue) {
    MTL::CommandQueue* que = (MTL::CommandQueue*) queue;
    MTL::CommandBuffer* buffer = que->commandBuffer();
    return (jlong) buffer;
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_commitCommandBuffer(JNIEnv* env, jclass cls, jlong cmdBuffer) {
    MTL::CommandBuffer* buf = (MTL::CommandBuffer*) cmdBuffer;
    buf->commit();
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_waitUntilCompleted(JNIEnv* env, jclass cls, jlong cmdBuffer) {
    MTL::CommandBuffer* buf = (MTL::CommandBuffer*) cmdBuffer;
    buf->waitUntilCompleted();
}

// Creates an MTLSharedEvent on the device, used to order dispatches across command buffers
// on the GPU (the analog of an OpenCL cl_event). The caller owns the returned event and must
// release it with releaseSharedEvent.
extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createSharedEvent(JNIEnv* env, jclass cls, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::SharedEvent* event = dev->newSharedEvent();
    return (jlong) event;
}

// Encodes, into the command buffer, a signal of the event to the given value once the buffer's
// prior work completes. Must be called when no encoder is active on the buffer.
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_encodeSignalEvent(JNIEnv* env, jclass cls, jlong cmdBuffer, jlong event, jlong value) {
    MTL::CommandBuffer* buf = (MTL::CommandBuffer*) cmdBuffer;
    MTL::SharedEvent* ev = (MTL::SharedEvent*) event;
    buf->encodeSignalEvent(ev, (uint64_t) value);
}

// Encodes, into the command buffer, a wait until the event reaches the given value before the
// buffer's subsequent work runs. Must be called when no encoder is active on the buffer.
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_encodeWaitForEvent(JNIEnv* env, jclass cls, jlong cmdBuffer, jlong event, jlong value) {
    MTL::CommandBuffer* buf = (MTL::CommandBuffer*) cmdBuffer;
    MTL::SharedEvent* ev = (MTL::SharedEvent*) event;
    buf->encodeWait(ev, (uint64_t) value);
}

// Releases an event created by createSharedEvent.
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_releaseSharedEvent(JNIEnv* env, jclass cls, jlong event) {
    MTL::SharedEvent* ev = (MTL::SharedEvent*) event;
    ev->release();
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_computeCommandEncoder(JNIEnv* env, jclass cls, jlong cmdBuffer) {
    MTL::CommandBuffer* buf = (MTL::CommandBuffer*) cmdBuffer;
    MTL::ComputeCommandEncoder* enc = buf->computeCommandEncoder();
    return (jlong) enc;
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setComputePipelineState(JNIEnv* env, jclass cls, jlong cmdEnc, jlong pipeline) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    MTL::ComputePipelineState* pipe = (MTL::ComputePipelineState*) pipeline;
    enc->setComputePipelineState(pipe);
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_dispatchThreadgroups(JNIEnv* env, jclass cls, jlong cmdEnc,
                                                                                     jint groupWidth, jint groupHeight, jint groupDepth,
                                                                                    jint gridWidth, jint gridHeight, jint gridDepth) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    enc->dispatchThreadgroups(MTL::Size(gridWidth, gridHeight, gridDepth), MTL::Size(groupWidth, groupHeight, groupDepth));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_dispatchThreads(JNIEnv* env, jclass cls, jlong cmdEnc,
                                                                                     jint groupWidth, jint groupHeight, jint groupDepth,
                                                                                    jint gridWidth, jint gridHeight, jint gridDepth) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    enc->dispatchThreads(MTL::Size(gridWidth, gridHeight, gridDepth), MTL::Size(groupWidth, groupHeight, groupDepth));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_endEncoding(JNIEnv* env, jclass cls, jlong cmdEnc) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    enc->endEncoding();
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createFunction(JNIEnv* env, jclass cls, jlong device, jstring func, jstring source) {
    const char* funcName = env->GetStringUTFChars(func, nullptr);
    const char* funcSource = env->GetStringUTFChars(source, nullptr);

    NS::String* funcNameStr = NS::String::alloc()->init(funcName, NS::StringEncoding::UTF8StringEncoding);
    NS::String* funcSourceStr = NS::String::alloc()->init(funcSource, NS::StringEncoding::UTF8StringEncoding);

    MTL::Device* dev = (MTL::Device*) device;
    MTL::CompileOptions* compileOptions = MTL::CompileOptions::alloc();
    compileOptions->setFastMathEnabled(true);

    NS::Error* error;
    MTL::Library* library = dev->newLibrary(funcSourceStr, compileOptions, &error);

    if (error != nullptr) {
        printf("Error: %s\n", error->localizedDescription()->utf8String());
    }

    MTL::Function* function = library->newFunction(funcNameStr);

    env->ReleaseStringUTFChars(func, funcName);
    env->ReleaseStringUTFChars(source, funcSource);

    return (jlong) function;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createComputePipelineState(JNIEnv* env, jclass cls, jlong device, jlong function) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::Function* func = (MTL::Function*) function;

    NS::Error* error;
    MTL::ComputePipelineState* pipeline = dev->newComputePipelineState(func, &error);

    if (error != nullptr) {
        printf("Error: %s\n", error->localizedDescription()->utf8String());
    }

    return (jlong) pipeline;
}

extern "C"
JNIEXPORT jint JNICALL Java_org_almostrealism_hardware_metal_MTL_maxTotalThreadsPerThreadgroup(JNIEnv* env, jclass cls, jlong pipeline) {
    MTL::ComputePipelineState* state = (MTL::ComputePipelineState*) pipeline;
    return (jint) state->maxTotalThreadsPerThreadgroup();
}

extern "C"
JNIEXPORT jint JNICALL Java_org_almostrealism_hardware_metal_MTL_threadExecutionWidth(JNIEnv* env, jclass cls, jlong pipeline) {
    MTL::ComputePipelineState* state = (MTL::ComputePipelineState*) pipeline;
    return (jint) state->threadExecutionWidth();
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createIntBuffer32(JNIEnv* env, jclass, jlong device, jintArray data, jint len) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::Buffer* buffer = dev->newBuffer((NS::UInteger) (len * 4), MTL::StorageModeShared);

    if (data != nullptr) {
        jint* dataPtr = env->GetIntArrayElements(data, nullptr);
        memcpy(buffer->contents(), dataPtr, (size_t) (len * 4));
        env->ReleaseIntArrayElements(data, dataPtr, JNI_ABORT);
    }

    return (jlong) buffer;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createBuffer16(JNIEnv* env, jclass, jlong device, jfloatArray data, jint len) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::Buffer* buffer = dev->newBuffer((NS::UInteger) (len * 2), MTL::StorageModeShared);

    if (data != nullptr) {
        jfloat* floatArr = env->GetFloatArrayElements(data, nullptr);

        uint16_t* bfloat16Arr = new uint16_t[len];
        for (int i = 0; i < len; ++i) {
            bfloat16Arr[i] = float32_to_bfloat16(floatArr[i]);
        }

        memcpy(buffer->contents(), bfloat16Arr, (size_t) (len * 2));
        delete[] bfloat16Arr;
        env->ReleaseFloatArrayElements(data, floatArr, JNI_ABORT);
    }

    return (jlong) buffer;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createBuffer32(JNIEnv* env, jclass, jlong device, jfloatArray data, jint len) {
    MTL::Device* dev = (MTL::Device*) device;
    MTL::Buffer* buffer = dev->newBuffer((NS::UInteger) (len * 4), MTL::StorageModeShared);

    if (data != nullptr) {
        jfloat* dataPtr = env->GetFloatArrayElements(data, nullptr);
        memcpy(buffer->contents(), dataPtr, (size_t) (len * 4));
        env->ReleaseFloatArrayElements(data, dataPtr, JNI_ABORT);
    }

    return (jlong) buffer;
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createSharedBuffer32(
    JNIEnv* env, jclass, jlong devicePtr, jstring jFilePath, jfloatArray data, jint len) {

    MTL::Device* device = reinterpret_cast<MTL::Device*>(devicePtr);
    size_t bufferSize = len * sizeof(float);

    // Check if len and bufferSize are valid
    if (len <= 0 || bufferSize == 0) {
        fprintf(stderr, "Invalid length: len = %d, bufferSize = %zu\n", len, bufferSize);
        return 0;
    }

    // Convert jstring to C-style string
    const char* filePath = env->GetStringUTFChars(jFilePath, nullptr);
    if (!filePath) {
        fprintf(stderr, "Failed to convert jstring to C string.\n");
        return 0;
    }

    // Use the provided file path
    int fd = open(filePath, O_CREAT | O_RDWR, 0666);
    if (fd == -1) {
        int errsv = errno;
        fprintf(stderr, "open failed: %s\n", strerror(errsv));
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return 0;
    }

    // Set the size of the file
    if (ftruncate(fd, bufferSize) == -1) {
        int errsv = errno;
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errsv));
        close(fd);
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return 0;
    }

    // Map the file into memory
    void* sharedMemory = mmap(NULL, bufferSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (sharedMemory == MAP_FAILED) {
        int errsv = errno;
        fprintf(stderr, "mmap failed: %s\n", strerror(errsv));
        close(fd);
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return 0;
    }

    close(fd); // Close the file descriptor

    // Write data to the shared memory
    if (data != nullptr) {
        jfloat* dataPtr = env->GetFloatArrayElements(data, nullptr);
        memcpy(sharedMemory, dataPtr, bufferSize);
        env->ReleaseFloatArrayElements(data, dataPtr, JNI_ABORT);
    }

    // Create a Metal buffer using the shared memory
    MTL::Buffer* buffer = device->newBuffer(sharedMemory, bufferSize, MTL::ResourceStorageModeShared);
    if (!buffer) {
        fprintf(stderr, "Failed to create Metal buffer.\n");
        munmap(sharedMemory, bufferSize);
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return 0;
    }

    // Release the file path string
    env->ReleaseStringUTFChars(jFilePath, filePath);

    return reinterpret_cast<jlong>(buffer);
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_getContentPointer(JNIEnv* env, jclass, jlong buffer) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();

    if (contents != nullptr) {
        return (jlong) contents;
    }

    return -1;
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setBufferContents16(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();

    uint16_t* bfloat16Arr = new uint16_t[length];
    float* floatArr = (float*) env->GetDirectBufferAddress(data);

    for (int i = 0; i < length; ++i) {
        bfloat16Arr[i] = float32_to_bfloat16(floatArr[i]);
    }

    memcpy(contents + (2 * offset), bfloat16Arr, (size_t) (2 * length));
    buf->didModifyRange(NS::Range(2 * offset, 2 * length));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_getBufferContents16(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();

    uint16_t* bfloat16Arr = new uint16_t[length];
    memcpy(bfloat16Arr, contents + (2 * offset), (size_t) (2 * length));
    float* floatArr = (float*) env->GetDirectBufferAddress(data);

    for (int i = 0; i < length; ++i) {
        floatArr[i] = bfloat16_to_float32(bfloat16Arr[i]);
    }
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data,
                                                                                        jint offset, jint length, jboolean sync) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();
    memcpy(contents + (4 * offset), env->GetDirectBufferAddress(data), (size_t) (4 * length));
    buf->didModifyRange(NS::Range(4 * offset, 4 * length));

    if (sync) {
        void* sharedMemory = reinterpret_cast<void*>(contents);

        // Synchronize changes back to the file
        if (msync(sharedMemory, length * sizeof(float), MS_SYNC) == -1) {
            int errsv = errno;
            fprintf(stderr, "msync failed: %s\n", strerror(errsv));
        } else {
            fprintf(stderr, "msync succeeded\n");
        }
    }
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setIntBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();
    memcpy(contents + (4 * offset), env->GetDirectBufferAddress(data), (size_t) (4 * length));
    buf->didModifyRange(NS::Range(4 * offset, 4 * length));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_getBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();
    memcpy(env->GetDirectBufferAddress(data), contents + (4 * offset), (size_t) (4 * length));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_getIntBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();
    memcpy(env->GetDirectBufferAddress(data), contents + (4 * offset), (size_t) (4 * length));
}

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_bufferLength(JNIEnv* env, jclass, jlong buffer) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    return (jlong) buf->length();
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setBuffer(JNIEnv* env, jclass, jlong cmdEnc, jint index, jlong buffer) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    enc->setBuffer(buf, 0, (NS::UInteger) index);
}

// Binds a small array of ints directly into the kernel's argument table at the given index,
// without a backing MTL::Buffer. Metal copies the bytes into the command at encode time, so
// each encoded command captures its own values — unlike a shared, reused buffer, this is safe
// when many commands are batched into one command buffer and committed together.
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setBytes(JNIEnv* env, jclass, jlong cmdEnc, jint index, jintArray data) {
    MTL::ComputeCommandEncoder* enc = (MTL::ComputeCommandEncoder*) cmdEnc;
    jsize len = env->GetArrayLength(data);
    jint* elems = env->GetIntArrayElements(data, nullptr);
    enc->setBytes(elems, (NS::UInteger) (len * sizeof(jint)), (NS::UInteger) index);
    env->ReleaseIntArrayElements(data, elems, JNI_ABORT);
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_releaseBuffer(JNIEnv* env, jclass, jlong buffer) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    buf->release();
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_releaseComputePipelineState(JNIEnv* env, jclass, jlong pipeline) {
    MTL::ComputePipelineState* pipe = (MTL::ComputePipelineState*) pipeline;
    pipe->release();
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_releaseCommandQueue(JNIEnv* env, jclass, jlong queue) {
    MTL::CommandQueue* que = (MTL::CommandQueue*) queue;
    que->release();
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_releaseDevice(JNIEnv* env, jclass, jlong device) {
    MTL::Device* dev = (MTL::Device*) device;
    dev->release();
}

// Creates a new Objective-C autorelease pool on the calling thread and returns an
// opaque handle to it. metal-cpp factory methods such as MTL::CommandQueue::commandBuffer()
// and MTL::CommandBuffer::computeCommandEncoder() return autoreleased objects; when
// they are created on a long-lived JNI worker thread with no pool in place they are
// never reclaimed, accumulating in the Metal driver until it stalls. Wrapping each
// kernel dispatch between autoreleasePoolPush()/autoreleasePoolPop() drains those
// per-dispatch command buffers and encoders. The push/pop must occur on the same
// thread that performs the dispatch.
extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_autoreleasePoolPush(JNIEnv* env, jclass cls) {
    NS::AutoreleasePool* pool = NS::AutoreleasePool::alloc()->init();
    return (jlong) pool;
}

// Drains and releases the autorelease pool created by autoreleasePoolPush(), freeing
// every object autoreleased on this thread since the matching push.
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_autoreleasePoolPop(JNIEnv* env, jclass cls, jlong poolPtr) {
    NS::AutoreleasePool* pool = (NS::AutoreleasePool*) poolPtr;
    if (pool != nullptr) pool->release();
}