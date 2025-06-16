#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation217_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2642_v2446Offset = (int) offsetArr[0];
jint _2643_v2449Offset = (int) offsetArr[1];
jint _2645_v2454Offset = (int) offsetArr[2];
jint _2642_v2446Size = (int) sizeArr[0];
jint _2643_v2449Size = (int) sizeArr[1];
jint _2645_v2454Size = (int) sizeArr[2];
jint _2642_v2446Dim0 = (int) dim0Arr[0];
jint _2643_v2449Dim0 = (int) dim0Arr[1];
jint _2645_v2454Dim0 = (int) dim0Arr[2];
double *_2642_v2446 = ((double *) argArr[0]);
double *_2643_v2449 = ((double *) argArr[1]);
double *_2645_v2454 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2645_v2454[global_id + _2645_v2454Offset] = (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 5] * _2642_v2446[_2642_v2446Offset + 5]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 6] * _2642_v2446[_2642_v2446Offset + 6]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 10] * _2642_v2446[_2642_v2446Offset + 10]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 7] * _2642_v2446[_2642_v2446Offset + 7]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 13] * _2642_v2446[_2642_v2446Offset + 13]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 9] * _2642_v2446[_2642_v2446Offset + 9]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 8] * _2642_v2446[_2642_v2446Offset + 8]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 15] * _2642_v2446[_2642_v2446Offset + 15]) + (_2642_v2446[((global_id * 16) % 16) + _2642_v2446Offset] * _2643_v2449[(global_id * 16) + _2643_v2449Offset]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 1] * _2642_v2446[_2642_v2446Offset + 1]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 12] * _2642_v2446[_2642_v2446Offset + 12]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 2] * _2642_v2446[_2642_v2446Offset + 2]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 3] * _2642_v2446[_2642_v2446Offset + 3]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 4] * _2642_v2446[_2642_v2446Offset + 4]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 14] * _2642_v2446[_2642_v2446Offset + 14]) + (_2643_v2449[(global_id * 16) + _2643_v2449Offset + 11] * _2642_v2446[_2642_v2446Offset + 11]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
