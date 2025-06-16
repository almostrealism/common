#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation404_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5330_v4080Offset = (int) offsetArr[0];
jint _5330_v4081Offset = (int) offsetArr[1];
jint _5330_v4080Size = (int) sizeArr[0];
jint _5330_v4081Size = (int) sizeArr[1];
jint _5330_v4080Dim0 = (int) dim0Arr[0];
jint _5330_v4081Dim0 = (int) dim0Arr[1];
double *_5330_v4080 = ((double *) argArr[0]);
double *_5330_v4081 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5330_v4080[(global_id * _5330_v4080Dim0) + _5330_v4080Offset] = 0.0;
for (int _5330_i = 0; _5330_i < 120;) {
jint k_5330_i = (global_id * 120) + _5330_i;
_5330_v4080[(global_id * _5330_v4080Dim0) + _5330_v4080Offset] = _5330_v4081[(k_5330_i) + _5330_v4081Offset] + _5330_v4080[(global_id * _5330_v4080Dim0) + _5330_v4080Offset];
_5330_i = _5330_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
