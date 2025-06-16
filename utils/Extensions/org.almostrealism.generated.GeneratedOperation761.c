#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation761_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11692_v7675Offset = (int) offsetArr[0];
jint _11692_v7676Offset = (int) offsetArr[1];
jint _11692_v7675Size = (int) sizeArr[0];
jint _11692_v7676Size = (int) sizeArr[1];
jint _11692_v7675Dim0 = (int) dim0Arr[0];
jint _11692_v7676Dim0 = (int) dim0Arr[1];
double *_11692_v7675 = ((double *) argArr[0]);
double *_11692_v7676 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11692_v7675[(global_id * _11692_v7675Dim0) + _11692_v7675Offset] = 0.0;
for (int _11692_i = 0; _11692_i < 25;) {
jint k_11692_i = (global_id * 25) + _11692_i;
_11692_v7675[(global_id * _11692_v7675Dim0) + _11692_v7675Offset] = _11692_v7676[(k_11692_i) + _11692_v7676Offset] + _11692_v7675[(global_id * _11692_v7675Dim0) + _11692_v7675Offset];
_11692_i = _11692_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
