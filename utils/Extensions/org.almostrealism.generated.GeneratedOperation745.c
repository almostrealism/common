#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation745_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11646_v7610Offset = (int) offsetArr[0];
jint _11642_v7602Offset = (int) offsetArr[1];
jint _11645_v7607Offset = (int) offsetArr[2];
jint _11646_v7610Size = (int) sizeArr[0];
jint _11642_v7602Size = (int) sizeArr[1];
jint _11645_v7607Size = (int) sizeArr[2];
jint _11646_v7610Dim0 = (int) dim0Arr[0];
jint _11642_v7602Dim0 = (int) dim0Arr[1];
jint _11645_v7607Dim0 = (int) dim0Arr[2];
double *_11646_v7610 = ((double *) argArr[0]);
double *_11642_v7602 = ((double *) argArr[1]);
double *_11645_v7607 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11646_v7610[global_id + _11646_v7610Offset] = ((- (_11642_v7602[(global_id / 25) + _11642_v7602Offset] / 25.0)) + _11645_v7607[global_id + _11645_v7607Offset]) * ((- (_11642_v7602[(global_id / 25) + _11642_v7602Offset] / 25.0)) + _11645_v7607[global_id + _11645_v7607Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
