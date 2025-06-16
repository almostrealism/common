#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation717_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11228_v7360Offset = (int) offsetArr[0];
jint _11194_v7320Offset = (int) offsetArr[1];
jint _11197_v7325Offset = (int) offsetArr[2];
jint _11214_v7333Offset = (int) offsetArr[3];
jint _11223_v7349Offset = (int) offsetArr[4];
jint _11228_v7360Size = (int) sizeArr[0];
jint _11194_v7320Size = (int) sizeArr[1];
jint _11197_v7325Size = (int) sizeArr[2];
jint _11214_v7333Size = (int) sizeArr[3];
jint _11223_v7349Size = (int) sizeArr[4];
jint _11228_v7360Dim0 = (int) dim0Arr[0];
jint _11194_v7320Dim0 = (int) dim0Arr[1];
jint _11197_v7325Dim0 = (int) dim0Arr[2];
jint _11214_v7333Dim0 = (int) dim0Arr[3];
jint _11223_v7349Dim0 = (int) dim0Arr[4];
double *_11228_v7360 = ((double *) argArr[0]);
double *_11194_v7320 = ((double *) argArr[1]);
double *_11197_v7325 = ((double *) argArr[2]);
double *_11214_v7333 = ((double *) argArr[3]);
double *_11223_v7349 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11228_v7360[global_id + _11228_v7360Offset] = ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_11214_v7333[(((global_id / 3600) * 120) + (global_id % 120)) + _11214_v7333Offset] * -0.03333333333333333)) * ((- (_11194_v7320[(global_id / 3600) + _11194_v7320Offset] / 30.0)) + _11197_v7325[(global_id / 120) + _11197_v7325Offset])) + ((((((- (global_id % 120)) + (global_id / 120)) == 0) ? 1 : 0) + (_11223_v7349[(((global_id / 3600) * 120) + (global_id % 120)) + _11223_v7349Offset] * -0.03333333333333333)) * ((- (_11194_v7320[(global_id / 3600) + _11194_v7320Offset] / 30.0)) + _11197_v7325[(global_id / 120) + _11197_v7325Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
