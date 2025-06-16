#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation726_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11249_v7256Offset = (int) offsetArr[0];
jint _11200_v7231Offset = (int) offsetArr[1];
jint _11245_v7248Offset = (int) offsetArr[2];
jint _11249_v7256Size = (int) sizeArr[0];
jint _11200_v7231Size = (int) sizeArr[1];
jint _11245_v7248Size = (int) sizeArr[2];
jint _11249_v7256Dim0 = (int) dim0Arr[0];
jint _11200_v7231Dim0 = (int) dim0Arr[1];
jint _11245_v7248Dim0 = (int) dim0Arr[2];
double *_11249_v7256 = ((double *) argArr[0]);
double *_11200_v7231 = ((double *) argArr[1]);
double *_11245_v7248 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11249_v7256[global_id + _11249_v7256Offset] = pow(pow((_11200_v7231[(global_id / 3600) + _11200_v7231Offset] / 30.0) + 1.0E-5, 0.5), -1.0) * (((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_11245_v7248[(((global_id / 3600) * 120) + (global_id % 120)) + _11245_v7248Offset] * -0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
