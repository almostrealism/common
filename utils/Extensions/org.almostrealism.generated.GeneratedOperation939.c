#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation939_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14229_v9186Offset = (int) offsetArr[0];
jint _14178_v9171Offset = (int) offsetArr[1];
jint _14228_v9185Offset = (int) offsetArr[2];
jint _14229_v9186Size = (int) sizeArr[0];
jint _14178_v9171Size = (int) sizeArr[1];
jint _14228_v9185Size = (int) sizeArr[2];
jint _14229_v9186Dim0 = (int) dim0Arr[0];
jint _14178_v9171Dim0 = (int) dim0Arr[1];
jint _14228_v9185Dim0 = (int) dim0Arr[2];
double *_14229_v9186 = ((double *) argArr[0]);
double *_14178_v9171 = ((double *) argArr[1]);
double *_14228_v9185 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14229_v9186[global_id + _14229_v9186Offset] = ((- ((_14178_v9171[_14178_v9171Offset] + _14178_v9171[_14178_v9171Offset + 1]) / 2.0)) + _14178_v9171[(global_id / 2) + _14178_v9171Offset]) * _14228_v9185[(global_id % 2) + _14228_v9185Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
