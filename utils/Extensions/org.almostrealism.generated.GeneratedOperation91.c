#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation91_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1161_v818Offset = (int) offsetArr[0];
jint _1157_v814Offset = (int) offsetArr[1];
jint _1158_v815Offset = (int) offsetArr[2];
jint _1160_v817Offset = (int) offsetArr[3];
jint _1161_v818Size = (int) sizeArr[0];
jint _1157_v814Size = (int) sizeArr[1];
jint _1158_v815Size = (int) sizeArr[2];
jint _1160_v817Size = (int) sizeArr[3];
jint _1161_v818Dim0 = (int) dim0Arr[0];
jint _1157_v814Dim0 = (int) dim0Arr[1];
jint _1158_v815Dim0 = (int) dim0Arr[2];
jint _1160_v817Dim0 = (int) dim0Arr[3];
double *_1161_v818 = ((double *) argArr[0]);
double *_1157_v814 = ((double *) argArr[1]);
double *_1158_v815 = ((double *) argArr[2]);
double *_1160_v817 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1161_v818[(global_id * _1161_v818Dim0) + _1161_v818Offset] = (_1157_v814[(global_id * _1157_v814Dim0) + _1157_v814Offset] + _1158_v815[(global_id * _1158_v815Dim0) + _1158_v815Offset]) * _1160_v817[(global_id * _1160_v817Dim0) + _1160_v817Offset];
_1161_v818[(global_id * _1161_v818Dim0) + _1161_v818Offset + 1] = _1160_v817[(global_id * _1160_v817Dim0) + _1160_v817Offset + 1];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
