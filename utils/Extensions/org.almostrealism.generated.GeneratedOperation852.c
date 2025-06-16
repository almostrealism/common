#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation852_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12853_v8456Offset = (int) offsetArr[0];
jint _12853_v8457Offset = (int) offsetArr[1];
jint _12853_v8456Size = (int) sizeArr[0];
jint _12853_v8457Size = (int) sizeArr[1];
jint _12853_v8456Dim0 = (int) dim0Arr[0];
jint _12853_v8457Dim0 = (int) dim0Arr[1];
double *_12853_v8456 = ((double *) argArr[0]);
double *_12853_v8457 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12853_v8456[(global_id * _12853_v8456Dim0) + _12853_v8456Offset] = 0.0;
for (int _12853_i = 0; _12853_i < 30;) {
jint k_12853_i = (global_id * 30) + _12853_i;
_12853_v8456[(global_id * _12853_v8456Dim0) + _12853_v8456Offset] = _12853_v8457[(k_12853_i) + _12853_v8457Offset] + _12853_v8456[(global_id * _12853_v8456Dim0) + _12853_v8456Offset];
_12853_i = _12853_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
