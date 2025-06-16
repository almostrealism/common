#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation855_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12867_v8434Offset = (int) offsetArr[0];
jint _12849_v8409Offset = (int) offsetArr[1];
jint _12852_v8414Offset = (int) offsetArr[2];
jint _12860_v8419Offset = (int) offsetArr[3];
jint _12866_v8432Offset = (int) offsetArr[4];
jint _12867_v8436Offset = (int) offsetArr[5];
jint _12867_v8434Size = (int) sizeArr[0];
jint _12849_v8409Size = (int) sizeArr[1];
jint _12852_v8414Size = (int) sizeArr[2];
jint _12860_v8419Size = (int) sizeArr[3];
jint _12866_v8432Size = (int) sizeArr[4];
jint _12867_v8436Size = (int) sizeArr[5];
jint _12867_v8434Dim0 = (int) dim0Arr[0];
jint _12849_v8409Dim0 = (int) dim0Arr[1];
jint _12852_v8414Dim0 = (int) dim0Arr[2];
jint _12860_v8419Dim0 = (int) dim0Arr[3];
jint _12866_v8432Dim0 = (int) dim0Arr[4];
jint _12867_v8436Dim0 = (int) dim0Arr[5];
double *_12867_v8434 = ((double *) argArr[0]);
double *_12849_v8409 = ((double *) argArr[1]);
double *_12852_v8414 = ((double *) argArr[2]);
double *_12860_v8419 = ((double *) argArr[3]);
double *_12866_v8432 = ((double *) argArr[4]);
double *_12867_v8436 = ((double *) argArr[5]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12867_v8434[global_id + _12867_v8434Offset] = ((((- (_12849_v8409[(global_id / 30) + _12849_v8409Offset] / 30.0)) + _12852_v8414[global_id + _12852_v8414Offset]) / pow((_12860_v8419[(global_id / 30) + _12860_v8419Offset] / 30.0) + 1.0E-5, 0.5)) * _12866_v8432[global_id + _12866_v8432Offset]) + _12867_v8436[global_id + _12867_v8436Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
