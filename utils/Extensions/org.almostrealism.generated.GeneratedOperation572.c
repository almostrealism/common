#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation572_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _8080_v5801Offset = (int) offsetArr[0];
jint _8080_v5802Offset = (int) offsetArr[1];
jint _8080_v5801Size = (int) sizeArr[0];
jint _8080_v5802Size = (int) sizeArr[1];
jint _8080_v5801Dim0 = (int) dim0Arr[0];
jint _8080_v5802Dim0 = (int) dim0Arr[1];
double *_8080_v5801 = ((double *) argArr[0]);
double *_8080_v5802 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_8080_v5801[(global_id * _8080_v5801Dim0) + _8080_v5801Offset] = 0.0;
for (int _8080_i = 0; _8080_i < 30;) {
jint k_8080_i = (global_id * 30) + _8080_i;
_8080_v5801[(global_id * _8080_v5801Dim0) + _8080_v5801Offset] = _8080_v5802[(k_8080_i) + _8080_v5802Offset] + _8080_v5801[(global_id * _8080_v5801Dim0) + _8080_v5801Offset];
_8080_i = _8080_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
