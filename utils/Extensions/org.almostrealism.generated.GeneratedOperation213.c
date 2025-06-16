#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation213_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2625_v2404Offset = (int) offsetArr[0];
jint _2626_v2401Offset = (int) offsetArr[1];
jint _2629_v2410Offset = (int) offsetArr[2];
jint _2625_v2404Size = (int) sizeArr[0];
jint _2626_v2401Size = (int) sizeArr[1];
jint _2629_v2410Size = (int) sizeArr[2];
jint _2625_v2404Dim0 = (int) dim0Arr[0];
jint _2626_v2401Dim0 = (int) dim0Arr[1];
jint _2629_v2410Dim0 = (int) dim0Arr[2];
double *_2625_v2404 = ((double *) argArr[0]);
double *_2626_v2401 = ((double *) argArr[1]);
double *_2629_v2410 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2629_v2410[global_id + _2629_v2410Offset] = (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 27] * _2626_v2401[(((global_id * 32) + 27) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 17] * _2626_v2401[(((global_id * 32) + 17) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 5] * _2626_v2401[(((global_id * 32) + 5) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 6] * _2626_v2401[(((global_id * 32) + 6) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 11] * _2626_v2401[(((global_id * 32) + 11) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 7] * _2626_v2401[(((global_id * 32) + 7) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 31] * _2626_v2401[(((global_id * 32) + 31) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 22] * _2626_v2401[(((global_id * 32) + 22) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 14] * _2626_v2401[(((global_id * 32) + 14) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 10] * _2626_v2401[(((global_id * 32) + 10) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 28] * _2626_v2401[(((global_id * 32) + 28) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 25] * _2626_v2401[(((global_id * 32) + 25) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 9] * _2626_v2401[(((global_id * 32) + 9) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 20] * _2626_v2401[(((global_id * 32) + 20) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 16] * _2626_v2401[(((global_id * 32) + 16) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 18] * _2626_v2401[(((global_id * 32) + 18) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 8] * _2626_v2401[(((global_id * 32) + 8) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 1] * _2626_v2401[(((global_id * 32) + 1) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 23] * _2626_v2401[(((global_id * 32) + 23) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 29] * _2626_v2401[(((global_id * 32) + 29) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 13] * _2626_v2401[(((global_id * 32) + 13) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 26] * _2626_v2401[(((global_id * 32) + 26) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 2] * _2626_v2401[(((global_id * 32) + 2) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 3] * _2626_v2401[(((global_id * 32) + 3) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 4] * _2626_v2401[(((global_id * 32) + 4) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 21] * _2626_v2401[(((global_id * 32) + 21) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 15] * _2626_v2401[(((global_id * 32) + 15) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 12] * _2626_v2401[(((global_id * 32) + 12) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 30] * _2626_v2401[(((global_id * 32) + 30) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 19] * _2626_v2401[(((global_id * 32) + 19) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset + 24] * _2626_v2401[(((global_id * 32) + 24) % 2048) + _2626_v2401Offset]) + (_2625_v2404[((global_id / 64) * 32) + _2625_v2404Offset] * _2626_v2401[((global_id * 32) % 2048) + _2626_v2401Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
