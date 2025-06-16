#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation744_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11641_v7613Offset = (int) offsetArr[0];
jint _11641_v7614Offset = (int) offsetArr[1];
jint _11641_v7613Size = (int) sizeArr[0];
jint _11641_v7614Size = (int) sizeArr[1];
jint _11641_v7613Dim0 = (int) dim0Arr[0];
jint _11641_v7614Dim0 = (int) dim0Arr[1];
double *_11641_v7613 = ((double *) argArr[0]);
double *_11641_v7614 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11641_v7613[(global_id * _11641_v7613Dim0) + _11641_v7613Offset] = 0.0;
for (int _11641_i = 0; _11641_i < 25;) {
jint k_11641_i = (global_id * 25) + _11641_i;
_11641_v7613[(global_id * _11641_v7613Dim0) + _11641_v7613Offset] = _11641_v7614[(k_11641_i) + _11641_v7614Offset] + _11641_v7613[(global_id * _11641_v7613Dim0) + _11641_v7613Offset];
_11641_i = _11641_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
