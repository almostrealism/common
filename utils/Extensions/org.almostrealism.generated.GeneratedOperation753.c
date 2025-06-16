#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation753_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11676_v7662Offset = (int) offsetArr[0];
jint _11642_v7622Offset = (int) offsetArr[1];
jint _11645_v7627Offset = (int) offsetArr[2];
jint _11662_v7635Offset = (int) offsetArr[3];
jint _11671_v7651Offset = (int) offsetArr[4];
jint _11676_v7662Size = (int) sizeArr[0];
jint _11642_v7622Size = (int) sizeArr[1];
jint _11645_v7627Size = (int) sizeArr[2];
jint _11662_v7635Size = (int) sizeArr[3];
jint _11671_v7651Size = (int) sizeArr[4];
jint _11676_v7662Dim0 = (int) dim0Arr[0];
jint _11642_v7622Dim0 = (int) dim0Arr[1];
jint _11645_v7627Dim0 = (int) dim0Arr[2];
jint _11662_v7635Dim0 = (int) dim0Arr[3];
jint _11671_v7651Dim0 = (int) dim0Arr[4];
double *_11676_v7662 = ((double *) argArr[0]);
double *_11642_v7622 = ((double *) argArr[1]);
double *_11645_v7627 = ((double *) argArr[2]);
double *_11662_v7635 = ((double *) argArr[3]);
double *_11671_v7651 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11676_v7662[global_id + _11676_v7662Offset] = ((((((- (global_id % 100)) + (global_id / 100)) == 0) ? 1 : 0) + (_11662_v7635[(((global_id / 2500) * 100) + (global_id % 100)) + _11662_v7635Offset] * -0.04)) * ((- (_11642_v7622[(global_id / 2500) + _11642_v7622Offset] / 25.0)) + _11645_v7627[(global_id / 100) + _11645_v7627Offset])) + ((((((- (global_id % 100)) + (global_id / 100)) == 0) ? 1 : 0) + (_11671_v7651[(((global_id / 2500) * 100) + (global_id % 100)) + _11671_v7651Offset] * -0.04)) * ((- (_11642_v7622[(global_id / 2500) + _11642_v7622Offset] / 25.0)) + _11645_v7627[(global_id / 100) + _11645_v7627Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
