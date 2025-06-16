#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation402_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5327_v4112Offset = (int) offsetArr[0];
jint _5308_v4086Offset = (int) offsetArr[1];
jint _5311_v4091Offset = (int) offsetArr[2];
jint _5319_v4096Offset = (int) offsetArr[3];
jint _5326_v4110Offset = (int) offsetArr[4];
jint _5327_v4112Size = (int) sizeArr[0];
jint _5308_v4086Size = (int) sizeArr[1];
jint _5311_v4091Size = (int) sizeArr[2];
jint _5319_v4096Size = (int) sizeArr[3];
jint _5326_v4110Size = (int) sizeArr[4];
jint _5327_v4112Dim0 = (int) dim0Arr[0];
jint _5308_v4086Dim0 = (int) dim0Arr[1];
jint _5311_v4091Dim0 = (int) dim0Arr[2];
jint _5319_v4096Dim0 = (int) dim0Arr[3];
jint _5326_v4110Dim0 = (int) dim0Arr[4];
double *_5327_v4112 = ((double *) argArr[0]);
double *_5308_v4086 = ((double *) argArr[1]);
double *_5311_v4091 = ((double *) argArr[2]);
double *_5319_v4096 = ((double *) argArr[3]);
double *_5326_v4110 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5327_v4112[global_id + _5327_v4112Offset] = (((- (global_id % 120)) + (global_id / 120)) == 0) ? ((((- (_5308_v4086[(global_id / 3600) + _5308_v4086Offset] / 30.0)) + _5311_v4091[(global_id / 120) + _5311_v4091Offset]) / pow((_5319_v4096[(global_id / 3600) + _5319_v4096Offset] / 30.0) + 1.0E-5, 0.5)) * _5326_v4110[(global_id / 120) + _5326_v4110Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
