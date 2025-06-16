#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation794_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12134_v7830Offset = (int) offsetArr[0];
jint _12084_v7818Offset = (int) offsetArr[1];
jint _12087_v7823Offset = (int) offsetArr[2];
jint _12133_v7829Offset = (int) offsetArr[3];
jint _12134_v7830Size = (int) sizeArr[0];
jint _12084_v7818Size = (int) sizeArr[1];
jint _12087_v7823Size = (int) sizeArr[2];
jint _12133_v7829Size = (int) sizeArr[3];
jint _12134_v7830Dim0 = (int) dim0Arr[0];
jint _12084_v7818Dim0 = (int) dim0Arr[1];
jint _12087_v7823Dim0 = (int) dim0Arr[2];
jint _12133_v7829Dim0 = (int) dim0Arr[3];
double *_12134_v7830 = ((double *) argArr[0]);
double *_12084_v7818 = ((double *) argArr[1]);
double *_12087_v7823 = ((double *) argArr[2]);
double *_12133_v7829 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12134_v7830[global_id + _12134_v7830Offset] = ((- (_12084_v7818[(global_id / 1600) + _12084_v7818Offset] / 20.0)) + _12087_v7823[(global_id / 80) + _12087_v7823Offset]) * _12133_v7829[(((global_id / 1600) * 80) + (global_id % 80)) + _12133_v7829Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
