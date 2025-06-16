#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation315_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3893_v3119Offset = (int) offsetArr[0];
jint _3898_v3122Offset = (int) offsetArr[1];
jint _3901_v3127Offset = (int) offsetArr[2];
jint _3893_v3119Size = (int) sizeArr[0];
jint _3898_v3122Size = (int) sizeArr[1];
jint _3901_v3127Size = (int) sizeArr[2];
jint _3893_v3119Dim0 = (int) dim0Arr[0];
jint _3898_v3122Dim0 = (int) dim0Arr[1];
jint _3901_v3127Dim0 = (int) dim0Arr[2];
double *_3893_v3119 = ((double *) argArr[0]);
double *_3898_v3122 = ((double *) argArr[1]);
double *_3901_v3127 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3901_v3127[global_id + _3901_v3127Offset] = (- ((_3898_v3122[(global_id * 2) + _3898_v3122Offset + 1] + _3898_v3122[(global_id * 2) + _3898_v3122Offset]) * _3893_v3119[_3893_v3119Offset])) + _3901_v3127[global_id + _3901_v3127Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
