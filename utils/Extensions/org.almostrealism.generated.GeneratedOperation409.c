#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation409_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5335_v3885Offset = (int) offsetArr[0];
jint _5341_v3888Offset = (int) offsetArr[1];
jint _5343_v3891Offset = (int) offsetArr[2];
jint _5335_v3885Size = (int) sizeArr[0];
jint _5341_v3888Size = (int) sizeArr[1];
jint _5343_v3891Size = (int) sizeArr[2];
jint _5335_v3885Dim0 = (int) dim0Arr[0];
jint _5341_v3888Dim0 = (int) dim0Arr[1];
jint _5343_v3891Dim0 = (int) dim0Arr[2];
double *_5335_v3885 = ((double *) argArr[0]);
double *_5341_v3888 = ((double *) argArr[1]);
double *_5343_v3891 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5343_v3891[global_id + _5343_v3891Offset] = (- (_5335_v3885[_5335_v3885Offset] * _5341_v3888[global_id + _5341_v3888Offset])) + _5343_v3891[global_id + _5343_v3891Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
