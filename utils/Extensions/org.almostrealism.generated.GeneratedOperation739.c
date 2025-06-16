#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation739_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11391_v7463Offset = (int) offsetArr[0];
jint _11391_v7464Offset = (int) offsetArr[1];
jint _11391_v7466Offset = (int) offsetArr[2];
jint _11391_v7463Size = (int) sizeArr[0];
jint _11391_v7464Size = (int) sizeArr[1];
jint _11391_v7466Size = (int) sizeArr[2];
jint _11391_v7463Dim0 = (int) dim0Arr[0];
jint _11391_v7464Dim0 = (int) dim0Arr[1];
jint _11391_v7466Dim0 = (int) dim0Arr[2];
double *_11391_v7463 = ((double *) argArr[0]);
double *_11391_v7464 = ((double *) argArr[1]);
double *_11391_v7466 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11391_v7463[global_id + _11391_v7463Offset] = _11391_v7464[global_id + _11391_v7464Offset + 60] * _11391_v7466[global_id + _11391_v7466Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
