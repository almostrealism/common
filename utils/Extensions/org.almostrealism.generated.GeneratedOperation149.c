#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation149_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2029_v1990Offset = (int) offsetArr[0];
jint _2023_v1982Offset = (int) offsetArr[1];
jint _2025_v1984Offset = (int) offsetArr[2];
jint _2029_v1990Size = (int) sizeArr[0];
jint _2023_v1982Size = (int) sizeArr[1];
jint _2025_v1984Size = (int) sizeArr[2];
jint _2029_v1990Dim0 = (int) dim0Arr[0];
jint _2023_v1982Dim0 = (int) dim0Arr[1];
jint _2025_v1984Dim0 = (int) dim0Arr[2];
double *_2029_v1990 = ((double *) argArr[0]);
double *_2023_v1982 = ((double *) argArr[1]);
double *_2025_v1984 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2029_v1990[(global_id * _2029_v1990Dim0) + _2029_v1990Offset] = (_2023_v1982[_2023_v1982Offset + 6] * _2025_v1984[_2025_v1984Offset + 6]) + (_2023_v1982[_2023_v1982Offset + 8] * _2025_v1984[_2025_v1984Offset + 8]) + (_2023_v1982[_2023_v1982Offset + 40] * _2025_v1984[_2025_v1984Offset + 40]) + (_2023_v1982[_2023_v1982Offset + 28] * _2025_v1984[_2025_v1984Offset + 28]) + (_2023_v1982[_2023_v1982Offset + 22] * _2025_v1984[_2025_v1984Offset + 22]) + (_2023_v1982[_2023_v1982Offset + 58] * _2025_v1984[_2025_v1984Offset + 58]) + (_2023_v1982[_2023_v1982Offset + 36] * _2025_v1984[_2025_v1984Offset + 36]) + (_2023_v1982[_2023_v1982Offset + 46] * _2025_v1984[_2025_v1984Offset + 46]) + (_2023_v1982[_2023_v1982Offset + 52] * _2025_v1984[_2025_v1984Offset + 52]) + (_2023_v1982[_2023_v1982Offset + 32] * _2025_v1984[_2025_v1984Offset + 32]) + (_2023_v1982[_2023_v1982Offset + 10] * _2025_v1984[_2025_v1984Offset + 10]) + (_2023_v1982[_2023_v1982Offset + 12] * _2025_v1984[_2025_v1984Offset + 12]) + (_2023_v1982[_2023_v1982Offset + 20] * _2025_v1984[_2025_v1984Offset + 20]) + (_2023_v1982[_2023_v1982Offset + 14] * _2025_v1984[_2025_v1984Offset + 14]) + (_2023_v1982[_2023_v1982Offset + 60] * _2025_v1984[_2025_v1984Offset + 60]) + (_2023_v1982[_2023_v1982Offset + 42] * _2025_v1984[_2025_v1984Offset + 42]) + (_2023_v1982[_2023_v1982Offset + 26] * _2025_v1984[_2025_v1984Offset + 26]) + (_2023_v1982[_2023_v1982Offset + 18] * _2025_v1984[_2025_v1984Offset + 18]) + (_2023_v1982[_2023_v1982Offset + 54] * _2025_v1984[_2025_v1984Offset + 54]) + (_2023_v1982[_2023_v1982Offset + 48] * _2025_v1984[_2025_v1984Offset + 48]) + (_2023_v1982[_2023_v1982Offset + 16] * _2025_v1984[_2025_v1984Offset + 16]) + (_2023_v1982[_2023_v1982Offset + 38] * _2025_v1984[_2025_v1984Offset + 38]) + (_2023_v1982[_2023_v1982Offset + 30] * _2025_v1984[_2025_v1984Offset + 30]) + (_2023_v1982[_2023_v1982Offset + 62] * _2025_v1984[_2025_v1984Offset + 62]) + (_2023_v1982[_2023_v1982Offset + 34] * _2025_v1984[_2025_v1984Offset + 34]) + (_2023_v1982[_2023_v1982Offset] * _2025_v1984[_2025_v1984Offset]) + (_2023_v1982[_2023_v1982Offset + 2] * _2025_v1984[_2025_v1984Offset + 2]) + (_2023_v1982[_2023_v1982Offset + 44] * _2025_v1984[_2025_v1984Offset + 44]) + (_2023_v1982[_2023_v1982Offset + 56] * _2025_v1984[_2025_v1984Offset + 56]) + (_2023_v1982[_2023_v1982Offset + 24] * _2025_v1984[_2025_v1984Offset + 24]) + (_2023_v1982[_2023_v1982Offset + 50] * _2025_v1984[_2025_v1984Offset + 50]) + (_2023_v1982[_2023_v1982Offset + 4] * _2025_v1984[_2025_v1984Offset + 4]);
_2029_v1990[(global_id * _2029_v1990Dim0) + _2029_v1990Offset + 1] = 1.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
