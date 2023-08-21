#define NS_PRIVATE_IMPLEMENTATION
#define CA_PRIVATE_IMPLEMENTATION
#define MTL_PRIVATE_IMPLEMENTATION

#include <string>
#include <jni.h>
#include <Foundation/Foundation.hpp>
#include <Metal/Metal.hpp>
#include <QuartzCore/QuartzCore.hpp>

extern "C"
JNIEXPORT jlong JNICALL Java_org_almostrealism_hardware_metal_MTL_createSystemDefaultDevice(JNIEnv* env, jclass cls) {
    MTL::Device* device = MTLCreateSystemDefaultDevice();
    return (jlong) device;
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

    NS::Error* error;
    MTL::Library* library = dev->newLibrary(funcSourceStr, nullptr, &error);

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
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_setBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
    MTL::Buffer* buf = (MTL::Buffer*) buffer;
    uint8_t* contents = (uint8_t*) buf->contents();
    memcpy(contents + (4 * offset), env->GetDirectBufferAddress(data), (size_t) (4 * length));
    buf->didModifyRange(NS::Range(offset, length));
}

extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_hardware_metal_MTL_getBufferContents32(JNIEnv* env, jclass, jlong buffer, jobject data, jint offset, jint length) {
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