#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation528_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7207_v5263Offset = (int) offsetArr[0];
jint _7187_v5232Offset = (int) offsetArr[1];
jint _7198_v5244Offset = (int) offsetArr[2];
jint _7206_v5261Offset = (int) offsetArr[3];
jint _7207_v5263Size = (int) sizeArr[0];
jint _7187_v5232Size = (int) sizeArr[1];
jint _7198_v5244Size = (int) sizeArr[2];
jint _7206_v5261Size = (int) sizeArr[3];
jint _7207_v5263Dim0 = (int) dim0Arr[0];
jint _7187_v5232Dim0 = (int) dim0Arr[1];
jint _7198_v5244Dim0 = (int) dim0Arr[2];
jint _7206_v5261Dim0 = (int) dim0Arr[3];
double *_7207_v5263 = ((double *) argArr[0]);
double *_7187_v5232 = ((double *) argArr[1]);
double *_7198_v5244 = ((double *) argArr[2]);
double *_7206_v5261 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7207_v5263[global_id + _7207_v5263Offset] = (((- (global_id % 2)) + (global_id / 2)) == 0) ? ((((- ((_7187_v5232[_7187_v5232Offset] + _7187_v5232[_7187_v5232Offset + 1]) / 2.0)) + _7187_v5232[(global_id / 2) + _7187_v5232Offset]) / pow(((_7198_v5244[_7198_v5244Offset] + _7198_v5244[_7198_v5244Offset + 1]) / 2.0) + 1.0E-5, 0.5)) * _7206_v5261[(global_id / 2) + _7206_v5261Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
