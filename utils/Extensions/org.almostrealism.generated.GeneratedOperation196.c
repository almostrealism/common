#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation196_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2527_v2323Offset = (int) offsetArr[0];
jint _2525_v2317Offset = (int) offsetArr[1];
jint _2526_v2320Offset = (int) offsetArr[2];
jint _2527_v2323Size = (int) sizeArr[0];
jint _2525_v2317Size = (int) sizeArr[1];
jint _2526_v2320Size = (int) sizeArr[2];
jint _2527_v2323Dim0 = (int) dim0Arr[0];
jint _2525_v2317Dim0 = (int) dim0Arr[1];
jint _2526_v2320Dim0 = (int) dim0Arr[2];
double *_2527_v2323 = ((double *) argArr[0]);
double *_2525_v2317 = ((double *) argArr[1]);
double *_2526_v2320 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2527_v2323[(global_id * _2527_v2323Dim0) + _2527_v2323Offset] = 0.0;
for (int _2527_i = 0; _2527_i < 10;) {
jint k_2527_i = (global_id * 10) + _2527_i;
_2527_v2323[(global_id * _2527_v2323Dim0) + _2527_v2323Offset] = (_2526_v2320[(k_2527_i) + _2526_v2320Offset] * _2525_v2317[_2527_i + _2525_v2317Offset]) + _2527_v2323[(global_id * _2527_v2323Dim0) + _2527_v2323Offset];
_2527_i = _2527_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
