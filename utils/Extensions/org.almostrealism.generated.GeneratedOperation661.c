#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation661_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10256_v6752Offset = (int) offsetArr[0];
jint _10251_v6741Offset = (int) offsetArr[1];
jint _10256_v6752Size = (int) sizeArr[0];
jint _10251_v6741Size = (int) sizeArr[1];
jint _10256_v6752Dim0 = (int) dim0Arr[0];
jint _10251_v6741Dim0 = (int) dim0Arr[1];
double *_10256_v6752 = ((double *) argArr[0]);
double *_10251_v6741 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10256_v6752[global_id + _10256_v6752Offset] = ((- ((_10251_v6741[_10251_v6741Offset + 8] + _10251_v6741[_10251_v6741Offset + 9] + _10251_v6741[_10251_v6741Offset + 10] + _10251_v6741[_10251_v6741Offset + 11]) / 4.0)) + _10251_v6741[global_id + _10251_v6741Offset + 8]) * ((- ((_10251_v6741[_10251_v6741Offset + 8] + _10251_v6741[_10251_v6741Offset + 9] + _10251_v6741[_10251_v6741Offset + 10] + _10251_v6741[_10251_v6741Offset + 11]) / 4.0)) + _10251_v6741[global_id + _10251_v6741Offset + 8]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
