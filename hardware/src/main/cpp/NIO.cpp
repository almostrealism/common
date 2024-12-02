#define NS_PRIVATE_IMPLEMENTATION
#define CA_PRIVATE_IMPLEMENTATION
#define MTL_PRIVATE_IMPLEMENTATION

#include <string>
#include <jni.h>

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
JNIEXPORT jlong JNICALL Java_org_almostrealism_nio_NIO_pointerForBuffer(JNIEnv* env, jclass, jobject buffer) {
    void* bufferPtr = env->GetDirectBufferAddress(buffer);
    return reinterpret_cast<jlong>(bufferPtr);
}