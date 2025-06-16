#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation483_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6394_v4785Offset = (int) offsetArr[0];
jint _6394_v4786Offset = (int) offsetArr[1];
jint _6394_v4788Offset = (int) offsetArr[2];
jint _6394_v4785Size = (int) sizeArr[0];
jint _6394_v4786Size = (int) sizeArr[1];
jint _6394_v4788Size = (int) sizeArr[2];
jint _6394_v4785Dim0 = (int) dim0Arr[0];
jint _6394_v4786Dim0 = (int) dim0Arr[1];
jint _6394_v4788Dim0 = (int) dim0Arr[2];
double *_6394_v4785 = ((double *) argArr[0]);
double *_6394_v4786 = ((double *) argArr[1]);
double *_6394_v4788 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6394_v4785[global_id + _6394_v4785Offset] = _6394_v4786[global_id + _6394_v4786Offset] * _6394_v4788[global_id + _6394_v4788Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
