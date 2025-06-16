#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation478_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6346_v4752Offset = (int) offsetArr[0];
jint _6341_v4741Offset = (int) offsetArr[1];
jint _6346_v4752Size = (int) sizeArr[0];
jint _6341_v4741Size = (int) sizeArr[1];
jint _6346_v4752Dim0 = (int) dim0Arr[0];
jint _6341_v4741Dim0 = (int) dim0Arr[1];
double *_6346_v4752 = ((double *) argArr[0]);
double *_6341_v4741 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6346_v4752[global_id + _6346_v4752Offset] = ((- ((_6341_v4741[_6341_v4741Offset + 21] + _6341_v4741[_6341_v4741Offset + 22] + _6341_v4741[_6341_v4741Offset + 26] + _6341_v4741[_6341_v4741Offset + 23] + _6341_v4741[_6341_v4741Offset + 29] + _6341_v4741[_6341_v4741Offset + 25] + _6341_v4741[_6341_v4741Offset + 24] + _6341_v4741[_6341_v4741Offset + 31] + _6341_v4741[_6341_v4741Offset + 16] + _6341_v4741[_6341_v4741Offset + 17] + _6341_v4741[_6341_v4741Offset + 28] + _6341_v4741[_6341_v4741Offset + 18] + _6341_v4741[_6341_v4741Offset + 19] + _6341_v4741[_6341_v4741Offset + 20] + _6341_v4741[_6341_v4741Offset + 30] + _6341_v4741[_6341_v4741Offset + 27]) / 16.0)) + _6341_v4741[global_id + _6341_v4741Offset + 16]) * ((- ((_6341_v4741[_6341_v4741Offset + 21] + _6341_v4741[_6341_v4741Offset + 22] + _6341_v4741[_6341_v4741Offset + 26] + _6341_v4741[_6341_v4741Offset + 23] + _6341_v4741[_6341_v4741Offset + 29] + _6341_v4741[_6341_v4741Offset + 25] + _6341_v4741[_6341_v4741Offset + 24] + _6341_v4741[_6341_v4741Offset + 31] + _6341_v4741[_6341_v4741Offset + 16] + _6341_v4741[_6341_v4741Offset + 17] + _6341_v4741[_6341_v4741Offset + 28] + _6341_v4741[_6341_v4741Offset + 18] + _6341_v4741[_6341_v4741Offset + 19] + _6341_v4741[_6341_v4741Offset + 20] + _6341_v4741[_6341_v4741Offset + 30] + _6341_v4741[_6341_v4741Offset + 27]) / 16.0)) + _6341_v4741[global_id + _6341_v4741Offset + 16]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
