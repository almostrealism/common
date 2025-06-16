#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation788_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12118_v7969Offset = (int) offsetArr[0];
jint _12118_v7970Offset = (int) offsetArr[1];
jint _12118_v7969Size = (int) sizeArr[0];
jint _12118_v7970Size = (int) sizeArr[1];
jint _12118_v7969Dim0 = (int) dim0Arr[0];
jint _12118_v7970Dim0 = (int) dim0Arr[1];
double *_12118_v7969 = ((double *) argArr[0]);
double *_12118_v7970 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12118_v7969[(global_id * _12118_v7969Dim0) + _12118_v7969Offset] = 0.0;
for (int _12118_i = 0; _12118_i < 20;) {
jint k_12118_i = (global_id * 20) + _12118_i;
_12118_v7969[(global_id * _12118_v7969Dim0) + _12118_v7969Offset] = _12118_v7970[(k_12118_i) + _12118_v7970Offset] + _12118_v7969[(global_id * _12118_v7969Dim0) + _12118_v7969Offset];
_12118_i = _12118_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
