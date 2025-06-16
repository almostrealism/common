#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation536_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7230_v5318Offset = (int) offsetArr[0];
jint _7225_v5310Offset = (int) offsetArr[1];
jint _7226_v5311Offset = (int) offsetArr[2];
jint _7229_v5317Offset = (int) offsetArr[3];
jint _7230_v5318Size = (int) sizeArr[0];
jint _7225_v5310Size = (int) sizeArr[1];
jint _7226_v5311Size = (int) sizeArr[2];
jint _7229_v5317Size = (int) sizeArr[3];
jint _7230_v5318Dim0 = (int) dim0Arr[0];
jint _7225_v5310Dim0 = (int) dim0Arr[1];
jint _7226_v5311Dim0 = (int) dim0Arr[2];
jint _7229_v5317Dim0 = (int) dim0Arr[3];
double *_7230_v5318 = ((double *) argArr[0]);
double *_7225_v5310 = ((double *) argArr[1]);
double *_7226_v5311 = ((double *) argArr[2]);
double *_7229_v5317 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7230_v5318[(global_id * _7230_v5318Dim0) + _7230_v5318Offset] = ((- _7226_v5311[(((global_id / _7226_v5311Size) * _7226_v5311Dim0) + (global_id % _7226_v5311Size)) + _7226_v5311Offset]) + _7225_v5310[(((global_id / 2) * _7225_v5310Dim0) + (global_id % 2)) + _7225_v5310Offset]) / _7229_v5317[(((global_id / _7229_v5317Size) * _7229_v5317Dim0) + (global_id % _7229_v5317Size)) + _7229_v5317Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
