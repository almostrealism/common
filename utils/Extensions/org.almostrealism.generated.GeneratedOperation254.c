#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation254_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2993_v2745Offset = (int) offsetArr[0];
jint _2985_v2738Offset = (int) offsetArr[1];
jint _2986_v2739Offset = (int) offsetArr[2];
jint _2993_v2745Size = (int) sizeArr[0];
jint _2985_v2738Size = (int) sizeArr[1];
jint _2986_v2739Size = (int) sizeArr[2];
jint _2993_v2745Dim0 = (int) dim0Arr[0];
jint _2985_v2738Dim0 = (int) dim0Arr[1];
jint _2986_v2739Dim0 = (int) dim0Arr[2];
double *_2993_v2745 = ((double *) argArr[0]);
double *_2985_v2738 = ((double *) argArr[1]);
double *_2986_v2739 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2993_v2745[(global_id * _2993_v2745Dim0) + _2993_v2745Offset] = ((- _2986_v2739[(((global_id / _2986_v2739Size) * _2986_v2739Dim0) + (global_id % _2986_v2739Size)) + _2986_v2739Offset]) + _2985_v2738[(((global_id / _2985_v2738Size) * _2985_v2738Dim0) + (global_id % _2985_v2738Size)) + _2985_v2738Offset]) * 9.110787172011662E-5;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
