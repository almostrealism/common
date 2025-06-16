#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation948_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14292_v9363Offset = (int) offsetArr[0];
jint _14292_v9364Offset = (int) offsetArr[1];
jint _14292_v9366Offset = (int) offsetArr[2];
jint _14292_v9363Size = (int) sizeArr[0];
jint _14292_v9364Size = (int) sizeArr[1];
jint _14292_v9366Size = (int) sizeArr[2];
jint _14292_v9363Dim0 = (int) dim0Arr[0];
jint _14292_v9364Dim0 = (int) dim0Arr[1];
jint _14292_v9366Dim0 = (int) dim0Arr[2];
double *_14292_v9363 = ((double *) argArr[0]);
double *_14292_v9364 = ((double *) argArr[1]);
double *_14292_v9366 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14292_v9363[global_id + _14292_v9363Offset] = _14292_v9364[global_id + _14292_v9364Offset] * _14292_v9366[global_id + _14292_v9366Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
