#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation709_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11198_v7308Offset = (int) offsetArr[0];
jint _11194_v7300Offset = (int) offsetArr[1];
jint _11197_v7305Offset = (int) offsetArr[2];
jint _11198_v7308Size = (int) sizeArr[0];
jint _11194_v7300Size = (int) sizeArr[1];
jint _11197_v7305Size = (int) sizeArr[2];
jint _11198_v7308Dim0 = (int) dim0Arr[0];
jint _11194_v7300Dim0 = (int) dim0Arr[1];
jint _11197_v7305Dim0 = (int) dim0Arr[2];
double *_11198_v7308 = ((double *) argArr[0]);
double *_11194_v7300 = ((double *) argArr[1]);
double *_11197_v7305 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11198_v7308[global_id + _11198_v7308Offset] = ((- (_11194_v7300[(global_id / 30) + _11194_v7300Offset] / 30.0)) + _11197_v7305[global_id + _11197_v7305Offset]) * ((- (_11194_v7300[(global_id / 30) + _11194_v7300Offset] / 30.0)) + _11197_v7305[global_id + _11197_v7305Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
