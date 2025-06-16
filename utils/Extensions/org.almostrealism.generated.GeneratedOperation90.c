#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation90_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1156_v813Offset = (int) offsetArr[0];
jint _1152_v809Offset = (int) offsetArr[1];
jint _1153_v810Offset = (int) offsetArr[2];
jint _1155_v812Offset = (int) offsetArr[3];
jint _1156_v813Size = (int) sizeArr[0];
jint _1152_v809Size = (int) sizeArr[1];
jint _1153_v810Size = (int) sizeArr[2];
jint _1155_v812Size = (int) sizeArr[3];
jint _1156_v813Dim0 = (int) dim0Arr[0];
jint _1152_v809Dim0 = (int) dim0Arr[1];
jint _1153_v810Dim0 = (int) dim0Arr[2];
jint _1155_v812Dim0 = (int) dim0Arr[3];
double *_1156_v813 = ((double *) argArr[0]);
double *_1152_v809 = ((double *) argArr[1]);
double *_1153_v810 = ((double *) argArr[2]);
double *_1155_v812 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1156_v813[(global_id * _1156_v813Dim0) + _1156_v813Offset] = (_1152_v809[(global_id * _1152_v809Dim0) + _1152_v809Offset] + _1153_v810[(global_id * _1153_v810Dim0) + _1153_v810Offset]) * _1155_v812[(global_id * _1155_v812Dim0) + _1155_v812Offset];
_1156_v813[(global_id * _1156_v813Dim0) + _1156_v813Offset + 1] = _1155_v812[(global_id * _1155_v812Dim0) + _1155_v812Offset + 1];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
