#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation573_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _8088_v5794Offset = (int) offsetArr[0];
jint _8070_v5769Offset = (int) offsetArr[1];
jint _8073_v5774Offset = (int) offsetArr[2];
jint _8081_v5779Offset = (int) offsetArr[3];
jint _8087_v5792Offset = (int) offsetArr[4];
jint _8088_v5796Offset = (int) offsetArr[5];
jint _8088_v5794Size = (int) sizeArr[0];
jint _8070_v5769Size = (int) sizeArr[1];
jint _8073_v5774Size = (int) sizeArr[2];
jint _8081_v5779Size = (int) sizeArr[3];
jint _8087_v5792Size = (int) sizeArr[4];
jint _8088_v5796Size = (int) sizeArr[5];
jint _8088_v5794Dim0 = (int) dim0Arr[0];
jint _8070_v5769Dim0 = (int) dim0Arr[1];
jint _8073_v5774Dim0 = (int) dim0Arr[2];
jint _8081_v5779Dim0 = (int) dim0Arr[3];
jint _8087_v5792Dim0 = (int) dim0Arr[4];
jint _8088_v5796Dim0 = (int) dim0Arr[5];
double *_8088_v5794 = ((double *) argArr[0]);
double *_8070_v5769 = ((double *) argArr[1]);
double *_8073_v5774 = ((double *) argArr[2]);
double *_8081_v5779 = ((double *) argArr[3]);
double *_8087_v5792 = ((double *) argArr[4]);
double *_8088_v5796 = ((double *) argArr[5]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_8088_v5794[global_id + _8088_v5794Offset] = ((((- (_8070_v5769[(global_id / 30) + _8070_v5769Offset] / 30.0)) + _8073_v5774[global_id + _8073_v5774Offset]) / pow((_8081_v5779[(global_id / 30) + _8081_v5779Offset] / 30.0) + 1.0E-5, 0.5)) * _8087_v5792[global_id + _8087_v5792Offset]) + _8088_v5796[global_id + _8088_v5796Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
