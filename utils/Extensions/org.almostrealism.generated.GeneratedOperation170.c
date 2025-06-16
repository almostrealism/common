#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation170_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2204_v2099Offset = (int) offsetArr[0];
jint _2204_v2100Offset = (int) offsetArr[1];
jint _2204_v2099Size = (int) sizeArr[0];
jint _2204_v2100Size = (int) sizeArr[1];
jint _2204_v2099Dim0 = (int) dim0Arr[0];
jint _2204_v2100Dim0 = (int) dim0Arr[1];
double *_2204_v2099 = ((double *) argArr[0]);
double *_2204_v2100 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2204_v2099[(global_id * _2204_v2099Dim0) + _2204_v2099Offset] = 0.0;
for (int _2204_i = 0; _2204_i < 3;) {
jint k_2204_i = (global_id * 3) + _2204_i;
_2204_v2099[(global_id * _2204_v2099Dim0) + _2204_v2099Offset] = _2204_v2100[(k_2204_i) + _2204_v2100Offset] + _2204_v2099[(global_id * _2204_v2099Dim0) + _2204_v2099Offset];
_2204_i = _2204_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
