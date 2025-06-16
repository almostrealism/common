#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation109_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1210_v930Offset = (int) offsetArr[0];
jint _1202_v922Offset = (int) offsetArr[1];
jint _1203_v923Offset = (int) offsetArr[2];
jint _1210_v930Size = (int) sizeArr[0];
jint _1202_v922Size = (int) sizeArr[1];
jint _1203_v923Size = (int) sizeArr[2];
jint _1210_v930Dim0 = (int) dim0Arr[0];
jint _1202_v922Dim0 = (int) dim0Arr[1];
jint _1203_v923Dim0 = (int) dim0Arr[2];
double *_1210_v930 = ((double *) argArr[0]);
double *_1202_v922 = ((double *) argArr[1]);
double *_1203_v923 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1210_v930[(global_id * _1210_v930Dim0) + _1210_v930Offset] = pow((((((global_id * 2) % 2) == 1) ? _1203_v923[(((global_id * 1024) % 1024) + (global_id * _1203_v923Dim0)) + _1203_v923Offset] : _1202_v922[(((global_id * 1024) % 1024) + (global_id * _1202_v922Dim0)) + _1202_v922Offset]) * ((((global_id * 2) % 2) == 1) ? _1203_v923[(((global_id * 1024) % 1024) + (global_id * _1203_v923Dim0)) + _1203_v923Offset] : _1202_v922[(((global_id * 1024) % 1024) + (global_id * _1202_v922Dim0)) + _1202_v922Offset])) + (_1203_v923[(((global_id * 1024) % 1024) + (global_id * _1203_v923Dim0)) + _1203_v923Offset] * _1203_v923[(((global_id * 1024) % 1024) + (global_id * _1203_v923Dim0)) + _1203_v923Offset]), 0.5);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
