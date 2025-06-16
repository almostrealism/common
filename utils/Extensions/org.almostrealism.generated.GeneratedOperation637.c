#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation637_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10007_v6377Offset = (int) offsetArr[0];
jint _10004_v6369Offset = (int) offsetArr[1];
jint _10004_v6370Offset = (int) offsetArr[2];
jint _10006_v6375Offset = (int) offsetArr[3];
jint _9940_v6366Offset = (int) offsetArr[4];
jint _10007_v6377Size = (int) sizeArr[0];
jint _10004_v6369Size = (int) sizeArr[1];
jint _10004_v6370Size = (int) sizeArr[2];
jint _10006_v6375Size = (int) sizeArr[3];
jint _9940_v6366Size = (int) sizeArr[4];
jint _10007_v6377Dim0 = (int) dim0Arr[0];
jint _10004_v6369Dim0 = (int) dim0Arr[1];
jint _10004_v6370Dim0 = (int) dim0Arr[2];
jint _10006_v6375Dim0 = (int) dim0Arr[3];
jint _9940_v6366Dim0 = (int) dim0Arr[4];
double *_10007_v6377 = ((double *) argArr[0]);
double *_10004_v6369 = ((double *) argArr[1]);
double *_10004_v6370 = ((double *) argArr[2]);
double *_10006_v6375 = ((double *) argArr[3]);
double *_9940_v6366 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10007_v6377[global_id + _10007_v6377Offset] = (_9940_v6366[(global_id / 16) + _9940_v6366Offset] * (_10004_v6369[global_id + _10004_v6369Offset] + _10004_v6370[global_id + _10004_v6370Offset])) * _10006_v6375[(global_id / 16) + _10006_v6375Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
