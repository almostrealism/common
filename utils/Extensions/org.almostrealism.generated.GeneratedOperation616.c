#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation616_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9427_v6267Offset = (int) offsetArr[0];
jint _9427_v6268Offset = (int) offsetArr[1];
jint _9427_v6270Offset = (int) offsetArr[2];
jint _9427_v6267Size = (int) sizeArr[0];
jint _9427_v6268Size = (int) sizeArr[1];
jint _9427_v6270Size = (int) sizeArr[2];
jint _9427_v6267Dim0 = (int) dim0Arr[0];
jint _9427_v6268Dim0 = (int) dim0Arr[1];
jint _9427_v6270Dim0 = (int) dim0Arr[2];
double *_9427_v6267 = ((double *) argArr[0]);
double *_9427_v6268 = ((double *) argArr[1]);
double *_9427_v6270 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9427_v6267[global_id + _9427_v6267Offset] = _9427_v6268[global_id + _9427_v6268Offset + 4] * _9427_v6270[global_id + _9427_v6270Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
