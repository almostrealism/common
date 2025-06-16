#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation762_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11697_v7558Offset = (int) offsetArr[0];
jint _11648_v7533Offset = (int) offsetArr[1];
jint _11693_v7550Offset = (int) offsetArr[2];
jint _11697_v7558Size = (int) sizeArr[0];
jint _11648_v7533Size = (int) sizeArr[1];
jint _11693_v7550Size = (int) sizeArr[2];
jint _11697_v7558Dim0 = (int) dim0Arr[0];
jint _11648_v7533Dim0 = (int) dim0Arr[1];
jint _11693_v7550Dim0 = (int) dim0Arr[2];
double *_11697_v7558 = ((double *) argArr[0]);
double *_11648_v7533 = ((double *) argArr[1]);
double *_11693_v7550 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11697_v7558[global_id + _11697_v7558Offset] = pow(pow((_11648_v7533[(global_id / 2500) + _11648_v7533Offset] / 25.0) + 1.0E-5, 0.5), -1.0) * (((((- (global_id % 100)) + (global_id / 100)) == 0) ? 1 : 0) + (_11693_v7550[(((global_id / 2500) * 100) + (global_id % 100)) + _11693_v7550Offset] * -0.04));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
