#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation383_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5270_v4067Offset = (int) offsetArr[0];
jint _5270_v4068Offset = (int) offsetArr[1];
jint _5270_v4067Size = (int) sizeArr[0];
jint _5270_v4068Size = (int) sizeArr[1];
jint _5270_v4067Dim0 = (int) dim0Arr[0];
jint _5270_v4068Dim0 = (int) dim0Arr[1];
double *_5270_v4067 = ((double *) argArr[0]);
double *_5270_v4068 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5270_v4067[(global_id * _5270_v4067Dim0) + _5270_v4067Offset] = 0.0;
for (int _5270_i = 0; _5270_i < 30;) {
jint k_5270_i = (global_id * 30) + _5270_i;
_5270_v4067[(global_id * _5270_v4067Dim0) + _5270_v4067Offset] = _5270_v4068[(k_5270_i) + _5270_v4068Offset] + _5270_v4067[(global_id * _5270_v4067Dim0) + _5270_v4067Offset];
_5270_i = _5270_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
