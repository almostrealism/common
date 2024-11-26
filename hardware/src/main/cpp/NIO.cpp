#define NS_PRIVATE_IMPLEMENTATION
#define CA_PRIVATE_IMPLEMENTATION
#define MTL_PRIVATE_IMPLEMENTATION

#include <string>
#include <cstdio>
#include <cstring>
#include <cerrno>

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>

#include <sys/mman.h>
#include <sys/stat.h>


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

// Map a file into memory and return a DirectByteBuffer
extern "C"
JNIEXPORT jobject JNICALL Java_org_almostrealism_nio_NIO_mapSharedMemory(JNIEnv* env, jclass, jstring jFilePath, jint length) {
    const char* filePath = env->GetStringUTFChars(jFilePath, nullptr);
    if (!filePath) {
        fprintf(stderr, "Failed to convert jstring to C string.\n");
        return nullptr;
    }

    int fd = open(filePath, O_CREAT | O_RDWR, 0666);
    if (fd == -1) {
        fprintf(stderr, "open failed: %s\n", strerror(errno));
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return nullptr;
    }

    // Resize the file to the desired length
    if (ftruncate(fd, length) == -1) {
        fprintf(stderr, "ftruncate failed: %s\n", strerror(errno));
        close(fd);
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return nullptr;
    }

    // Map the file into memory
    void* sharedMemory = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (sharedMemory == MAP_FAILED) {
        fprintf(stderr, "mmap failed: %s\n", strerror(errno));
        close(fd);
        env->ReleaseStringUTFChars(jFilePath, filePath);
        return nullptr;
    }

    close(fd);
    env->ReleaseStringUTFChars(jFilePath, filePath);

    // Create a DirectByteBuffer from the mapped memory
    return env->NewDirectByteBuffer(sharedMemory, length);
}

// Synchronize shared memory to the file
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_nio_NIO_syncSharedMemory(JNIEnv* env, jclass, jobject buffer, jint length) {
    void* sharedMemory = env->GetDirectBufferAddress(buffer);
    if (!sharedMemory) {
        fprintf(stderr, "Failed to get direct buffer address.\n");
        return;
    }

    if (msync(sharedMemory, length, MS_SYNC) == -1) {
        fprintf(stderr, "msync failed: %s\n", strerror(errno));
    } else {
        fprintf(stderr, "msync succeeded.\n");
    }
}

// Unmap the shared memory
extern "C"
JNIEXPORT void JNICALL Java_org_almostrealism_nio_NIO_unmapSharedMemory(JNIEnv* env, jclass, jobject buffer, jint length) {
    void* sharedMemory = env->GetDirectBufferAddress(buffer);
    if (!sharedMemory) {
        fprintf(stderr, "Failed to get direct buffer address.\n");
        return;
    }

    if (munmap(sharedMemory, length) == -1) {
        fprintf(stderr, "munmap failed: %s\n", strerror(errno));
    } else {
        fprintf(stderr, "munmap succeeded.\n");
    }
}