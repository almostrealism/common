#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation779_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12083_v7863Offset = (int) offsetArr[0];
jint _12083_v7864Offset = (int) offsetArr[1];
jint _12083_v7863Size = (int) sizeArr[0];
jint _12083_v7864Size = (int) sizeArr[1];
jint _12083_v7863Dim0 = (int) dim0Arr[0];
jint _12083_v7864Dim0 = (int) dim0Arr[1];
double *_12083_v7863 = ((double *) argArr[0]);
double *_12083_v7864 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12083_v7863[(global_id * _12083_v7863Dim0) + _12083_v7863Offset] = 0.0;
for (int _12083_i = 0; _12083_i < 20;) {
jint k_12083_i = (global_id * 20) + _12083_i;
_12083_v7863[(global_id * _12083_v7863Dim0) + _12083_v7863Offset] = _12083_v7864[(k_12083_i) + _12083_v7864Offset] + _12083_v7863[(global_id * _12083_v7863Dim0) + _12083_v7863Offset];
_12083_i = _12083_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
