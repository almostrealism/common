/*
 * JNI bridge for the CUDA backend (org.almostrealism.hardware.cuda.CU).
 *
 * Uses the CUDA Driver API (cuda.h) for device, context, memory, stream, event,
 * module and launch management, and NVRTC (nvrtc.h) for runtime compilation of
 * generated kernel source. The CUDA runtime API (cudart) is intentionally not used.
 *
 * Conventions:
 *  - Every entry point that needs a CUDA context receives it explicitly and makes it
 *    current on the calling thread, because the JVM calls in from many threads and a CUDA
 *    context is current per thread. The context is set on every call rather than cached:
 *    cuCtxSetCurrent is cheap, and a per-thread cache cannot be invalidated on the other
 *    threads when a primary context is released, which would let them issue work against a
 *    stale context.
 *  - Every failing CUresult / nvrtcResult is converted to a Java HardwareException
 *    carrying the error name and description (and the NVRTC log for compile failures).
 *    No entry point reports failure by silently returning 0.
 *
 * Built by compile-cuda.sh into ../resources/libARCUDA-linux-<arch>.so.
 */

#include <jni.h>
#include <cuda.h>
#include <nvrtc.h>

#include <string>
#include <vector>

static void throwHardwareException(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("org/almostrealism/hardware/HardwareException");
    if (cls == nullptr) {
        env->ExceptionClear();
        cls = env->FindClass("java/lang/RuntimeException");
    }
    env->ThrowNew(cls, message.c_str());
}

static bool check(JNIEnv* env, CUresult result, const char* operation) {
    if (result == CUDA_SUCCESS) return true;

    const char* name = nullptr;
    const char* description = nullptr;
    cuGetErrorName(result, &name);
    cuGetErrorString(result, &description);

    std::string message = std::string(operation) + " failed: " +
            (name == nullptr ? "UNKNOWN" : name) + " (" +
            (description == nullptr ? "no description" : description) + ")";
    throwHardwareException(env, message);
    return false;
}

static bool checkNvrtc(JNIEnv* env, nvrtcResult result, const char* operation, const std::string& log) {
    if (result == NVRTC_SUCCESS) return true;

    std::string message = std::string(operation) + " failed: " + nvrtcGetErrorString(result);
    if (!log.empty()) {
        message += "\n" + log;
    }

    throwHardwareException(env, message);
    return false;
}

static bool makeCurrent(JNIEnv* env, jlong context) {
    return check(env, cuCtxSetCurrent((CUcontext) context), "cuCtxSetCurrent");
}

#define CU_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_org_almostrealism_hardware_cuda_CU_##name

/* ---------------------------------------------------------------- device & context */

CU_FN(void, init)(JNIEnv* env, jclass cls) {
    check(env, cuInit(0), "cuInit");
}

CU_FN(jint, driverVersion)(JNIEnv* env, jclass cls) {
    int version = 0;
    check(env, cuDriverGetVersion(&version), "cuDriverGetVersion");
    return version;
}

CU_FN(jint, nvrtcVersion)(JNIEnv* env, jclass cls) {
    int major = 0, minor = 0;
    if (!checkNvrtc(env, nvrtcVersion(&major, &minor), "nvrtcVersion", "")) return 0;
    return major * 1000 + minor * 10;
}

CU_FN(jint, deviceCount)(JNIEnv* env, jclass cls) {
    int count = 0;
    check(env, cuDeviceGetCount(&count), "cuDeviceGetCount");
    return count;
}

CU_FN(jint, device)(JNIEnv* env, jclass cls, jint ordinal) {
    CUdevice device = 0;
    check(env, cuDeviceGet(&device, ordinal), "cuDeviceGet");
    return (jint) device;
}

CU_FN(jstring, deviceName)(JNIEnv* env, jclass cls, jint device) {
    char name[256];
    if (!check(env, cuDeviceGetName(name, sizeof(name), (CUdevice) device), "cuDeviceGetName")) return nullptr;
    return env->NewStringUTF(name);
}

CU_FN(jint, deviceAttribute)(JNIEnv* env, jclass cls, jint device, jint attribute) {
    int value = 0;
    check(env, cuDeviceGetAttribute(&value, (CUdevice_attribute) attribute, (CUdevice) device),
          "cuDeviceGetAttribute");
    return value;
}

CU_FN(jlong, totalMemory)(JNIEnv* env, jclass cls, jint device) {
    size_t bytes = 0;
    check(env, cuDeviceTotalMem(&bytes, (CUdevice) device), "cuDeviceTotalMem");
    return (jlong) bytes;
}

CU_FN(jlong, primaryContextRetain)(JNIEnv* env, jclass cls, jint device) {
    CUcontext ctx = nullptr;
    if (!check(env, cuDevicePrimaryCtxRetain(&ctx, (CUdevice) device), "cuDevicePrimaryCtxRetain")) return 0;
    return (jlong) ctx;
}

CU_FN(void, primaryContextRelease)(JNIEnv* env, jclass cls, jint device) {
    check(env, cuDevicePrimaryCtxRelease((CUdevice) device), "cuDevicePrimaryCtxRelease");
}

CU_FN(void, synchronize)(JNIEnv* env, jclass cls, jlong context) {
    if (!makeCurrent(env, context)) return;
    check(env, cuCtxSynchronize(), "cuCtxSynchronize");
}

/* ---------------------------------------------------------------- compilation */

CU_FN(jbyteArray, compile)(JNIEnv* env, jclass cls, jstring source, jstring name,
                           jobjectArray options, jboolean cubin) {
    const char* src = env->GetStringUTFChars(source, nullptr);
    const char* nm = env->GetStringUTFChars(name, nullptr);

    nvrtcProgram program;
    nvrtcResult created = nvrtcCreateProgram(&program, src, nm, 0, nullptr, nullptr);
    env->ReleaseStringUTFChars(source, src);
    env->ReleaseStringUTFChars(name, nm);
    if (!checkNvrtc(env, created, "nvrtcCreateProgram", "")) return nullptr;

    jsize optionCount = options == nullptr ? 0 : env->GetArrayLength(options);
    std::vector<std::string> optionValues;
    for (jsize i = 0; i < optionCount; i++) {
        jstring option = (jstring) env->GetObjectArrayElement(options, i);
        const char* value = env->GetStringUTFChars(option, nullptr);
        optionValues.emplace_back(value);
        env->ReleaseStringUTFChars(option, value);
        env->DeleteLocalRef(option);
    }

    std::vector<const char*> optionPointers;
    for (const std::string& value : optionValues) optionPointers.push_back(value.c_str());

    nvrtcResult compiled = nvrtcCompileProgram(program, (int) optionPointers.size(),
            optionPointers.empty() ? nullptr : optionPointers.data());

    std::string log;
    size_t logSize = 0;
    if (nvrtcGetProgramLogSize(program, &logSize) == NVRTC_SUCCESS && logSize > 1) {
        log.resize(logSize);
        nvrtcGetProgramLog(program, &log[0]);
    }

    if (!checkNvrtc(env, compiled, "nvrtcCompileProgram", log)) {
        nvrtcDestroyProgram(&program);
        return nullptr;
    }

    size_t size = 0;
    nvrtcResult sized = cubin ? nvrtcGetCUBINSize(program, &size) : nvrtcGetPTXSize(program, &size);
    if (!checkNvrtc(env, sized, cubin ? "nvrtcGetCUBINSize" : "nvrtcGetPTXSize", log)) {
        nvrtcDestroyProgram(&program);
        return nullptr;
    }

    std::vector<char> image(size);
    nvrtcResult fetched = cubin ? nvrtcGetCUBIN(program, image.data()) : nvrtcGetPTX(program, image.data());
    nvrtcDestroyProgram(&program);
    if (!checkNvrtc(env, fetched, cubin ? "nvrtcGetCUBIN" : "nvrtcGetPTX", log)) return nullptr;

    jbyteArray result = env->NewByteArray((jsize) size);
    env->SetByteArrayRegion(result, 0, (jsize) size, (const jbyte*) image.data());
    return result;
}

CU_FN(jlong, moduleLoadData)(JNIEnv* env, jclass cls, jlong context, jbyteArray image) {
    if (!makeCurrent(env, context)) return 0;

    jsize length = env->GetArrayLength(image);
    std::vector<char> data(length + 1, 0);
    env->GetByteArrayRegion(image, 0, length, (jbyte*) data.data());

    CUmodule module = nullptr;
    if (!check(env, cuModuleLoadData(&module, data.data()), "cuModuleLoadData")) return 0;
    return (jlong) module;
}

CU_FN(void, moduleUnload)(JNIEnv* env, jclass cls, jlong context, jlong module) {
    if (!makeCurrent(env, context)) return;
    check(env, cuModuleUnload((CUmodule) module), "cuModuleUnload");
}

CU_FN(jlong, moduleGetFunction)(JNIEnv* env, jclass cls, jlong context, jlong module, jstring name) {
    if (!makeCurrent(env, context)) return 0;

    const char* nm = env->GetStringUTFChars(name, nullptr);
    CUfunction function = nullptr;
    CUresult result = cuModuleGetFunction(&function, (CUmodule) module, nm);
    env->ReleaseStringUTFChars(name, nm);

    if (!check(env, result, "cuModuleGetFunction")) return 0;
    return (jlong) function;
}

CU_FN(jint, functionAttribute)(JNIEnv* env, jclass cls, jlong context, jlong function, jint attribute) {
    if (!makeCurrent(env, context)) return 0;

    int value = 0;
    check(env, cuFuncGetAttribute(&value, (CUfunction_attribute) attribute, (CUfunction) function),
          "cuFuncGetAttribute");
    return value;
}

/* ---------------------------------------------------------------- memory */

CU_FN(jlong, memAlloc)(JNIEnv* env, jclass cls, jlong context, jlong bytes) {
    if (!makeCurrent(env, context)) return 0;

    CUdeviceptr ptr = 0;
    if (!check(env, cuMemAlloc(&ptr, (size_t) bytes), "cuMemAlloc")) return 0;
    if (!check(env, cuMemsetD8(ptr, 0, (size_t) bytes), "cuMemsetD8")) {
        cuMemFree(ptr);
        return 0;
    }

    return (jlong) ptr;
}

CU_FN(jlong, memAllocManaged)(JNIEnv* env, jclass cls, jlong context, jlong bytes) {
    if (!makeCurrent(env, context)) return 0;

    CUdeviceptr ptr = 0;
    if (!check(env, cuMemAllocManaged(&ptr, (size_t) bytes, CU_MEM_ATTACH_GLOBAL), "cuMemAllocManaged")) return 0;
    if (!check(env, cuMemsetD8(ptr, 0, (size_t) bytes), "cuMemsetD8")) {
        cuMemFree(ptr);
        return 0;
    }

    return (jlong) ptr;
}

CU_FN(void, memFree)(JNIEnv* env, jclass cls, jlong context, jlong ptr) {
    if (!makeCurrent(env, context)) return;
    check(env, cuMemFree((CUdeviceptr) ptr), "cuMemFree");
}

CU_FN(void, memcpyHtoD)(JNIEnv* env, jclass cls, jlong context, jlong destination, jlong destinationOffset,
                        jobject source, jlong sourceOffset, jlong bytes) {
    if (!makeCurrent(env, context)) return;

    char* src = (char*) env->GetDirectBufferAddress(source);
    if (src == nullptr) {
        throwHardwareException(env, "memcpyHtoD requires a direct buffer");
        return;
    }

    check(env, cuMemcpyHtoD((CUdeviceptr) (destination + destinationOffset), src + sourceOffset, (size_t) bytes),
          "cuMemcpyHtoD");
}

CU_FN(void, memcpyDtoH)(JNIEnv* env, jclass cls, jlong context, jobject destination, jlong destinationOffset,
                        jlong source, jlong sourceOffset, jlong bytes) {
    if (!makeCurrent(env, context)) return;

    char* dst = (char*) env->GetDirectBufferAddress(destination);
    if (dst == nullptr) {
        throwHardwareException(env, "memcpyDtoH requires a direct buffer");
        return;
    }

    check(env, cuMemcpyDtoH(dst + destinationOffset, (CUdeviceptr) (source + sourceOffset), (size_t) bytes),
          "cuMemcpyDtoH");
}

CU_FN(void, memcpyDtoD)(JNIEnv* env, jclass cls, jlong context, jlong destination, jlong source, jlong bytes) {
    if (!makeCurrent(env, context)) return;
    check(env, cuMemcpyDtoD((CUdeviceptr) destination, (CUdeviceptr) source, (size_t) bytes), "cuMemcpyDtoD");
}

CU_FN(void, memcpyDtoDAsync)(JNIEnv* env, jclass cls, jlong context, jlong destination, jlong source,
                             jlong bytes, jlong stream) {
    if (!makeCurrent(env, context)) return;
    check(env, cuMemcpyDtoDAsync((CUdeviceptr) destination, (CUdeviceptr) source, (size_t) bytes,
                                 (CUstream) stream), "cuMemcpyDtoDAsync");
}

/* ---------------------------------------------------------------- streams & events */

CU_FN(jlong, streamCreate)(JNIEnv* env, jclass cls, jlong context) {
    if (!makeCurrent(env, context)) return 0;

    CUstream stream = nullptr;
    if (!check(env, cuStreamCreate(&stream, CU_STREAM_NON_BLOCKING), "cuStreamCreate")) return 0;
    return (jlong) stream;
}

CU_FN(void, streamDestroy)(JNIEnv* env, jclass cls, jlong context, jlong stream) {
    if (!makeCurrent(env, context)) return;
    check(env, cuStreamDestroy((CUstream) stream), "cuStreamDestroy");
}

CU_FN(void, streamSynchronize)(JNIEnv* env, jclass cls, jlong context, jlong stream) {
    if (!makeCurrent(env, context)) return;
    check(env, cuStreamSynchronize((CUstream) stream), "cuStreamSynchronize");
}

CU_FN(void, streamWaitEvent)(JNIEnv* env, jclass cls, jlong context, jlong stream, jlong event) {
    if (!makeCurrent(env, context)) return;
    check(env, cuStreamWaitEvent((CUstream) stream, (CUevent) event, 0), "cuStreamWaitEvent");
}

CU_FN(jlong, eventCreate)(JNIEnv* env, jclass cls, jlong context) {
    if (!makeCurrent(env, context)) return 0;

    CUevent event = nullptr;
    if (!check(env, cuEventCreate(&event, CU_EVENT_DISABLE_TIMING), "cuEventCreate")) return 0;
    return (jlong) event;
}

CU_FN(void, eventRecord)(JNIEnv* env, jclass cls, jlong context, jlong event, jlong stream) {
    if (!makeCurrent(env, context)) return;
    check(env, cuEventRecord((CUevent) event, (CUstream) stream), "cuEventRecord");
}

CU_FN(jboolean, eventQuery)(JNIEnv* env, jclass cls, jlong context, jlong event) {
    if (!makeCurrent(env, context)) return JNI_FALSE;

    CUresult result = cuEventQuery((CUevent) event);
    if (result == CUDA_ERROR_NOT_READY) return JNI_FALSE;
    return check(env, result, "cuEventQuery") ? JNI_TRUE : JNI_FALSE;
}

CU_FN(void, eventSynchronize)(JNIEnv* env, jclass cls, jlong context, jlong event) {
    if (!makeCurrent(env, context)) return;
    check(env, cuEventSynchronize((CUevent) event), "cuEventSynchronize");
}

CU_FN(void, eventDestroy)(JNIEnv* env, jclass cls, jlong context, jlong event) {
    if (!makeCurrent(env, context)) return;
    check(env, cuEventDestroy((CUevent) event), "cuEventDestroy");
}

/* ---------------------------------------------------------------- launch */

/*
 * Launches a generated kernel. The parameter block matches the signature rendered by
 * CudaLanguageOperations: every argument pointer, then every argument offset (int),
 * then every argument size (int), then global_count (long) and global_offset (long).
 */
CU_FN(void, launchKernel)(JNIEnv* env, jclass cls, jlong context, jlong function,
                          jint gridX, jint blockX, jlongArray pointers, jintArray offsets, jintArray sizes,
                          jlong globalCount, jlong globalOffset, jlong stream) {
    if (!makeCurrent(env, context)) return;

    jsize count = env->GetArrayLength(pointers);
    if (env->GetArrayLength(offsets) != count || env->GetArrayLength(sizes) != count) {
        throwHardwareException(env, "launchKernel requires one offset and one size per pointer");
        return;
    }

    std::vector<CUdeviceptr> ptrValues(count);
    std::vector<jint> offsetValues(count);
    std::vector<jint> sizeValues(count);

    std::vector<jlong> rawPointers(count);
    env->GetLongArrayRegion(pointers, 0, count, rawPointers.data());
    env->GetIntArrayRegion(offsets, 0, count, offsetValues.data());
    env->GetIntArrayRegion(sizes, 0, count, sizeValues.data());
    for (jsize i = 0; i < count; i++) ptrValues[i] = (CUdeviceptr) rawPointers[i];

    long long countValue = (long long) globalCount;
    long long offsetValue = (long long) globalOffset;

    std::vector<void*> params;
    params.reserve(3 * count + 2);
    for (jsize i = 0; i < count; i++) params.push_back(&ptrValues[i]);
    for (jsize i = 0; i < count; i++) params.push_back(&offsetValues[i]);
    for (jsize i = 0; i < count; i++) params.push_back(&sizeValues[i]);
    params.push_back(&countValue);
    params.push_back(&offsetValue);

    check(env, cuLaunchKernel((CUfunction) function,
                              (unsigned int) gridX, 1, 1,
                              (unsigned int) blockX, 1, 1,
                              0, (CUstream) stream, params.data(), nullptr),
          "cuLaunchKernel");
}
