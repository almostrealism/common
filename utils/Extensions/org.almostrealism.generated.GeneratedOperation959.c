#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation959_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14486_v9639Offset = (int) offsetArr[0];
jint _14467_v9632Offset = (int) offsetArr[1];
jint _14469_v9636Offset = (int) offsetArr[2];
jint _14486_v9639Size = (int) sizeArr[0];
jint _14467_v9632Size = (int) sizeArr[1];
jint _14469_v9636Size = (int) sizeArr[2];
jint _14486_v9639Dim0 = (int) dim0Arr[0];
jint _14467_v9632Dim0 = (int) dim0Arr[1];
jint _14469_v9636Dim0 = (int) dim0Arr[2];
double *_14486_v9639 = ((double *) argArr[0]);
double *_14467_v9632 = ((double *) argArr[1]);
double *_14469_v9636 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14486_v9639[global_id + _14486_v9639Offset] = exp((- _14467_v9632[(global_id / 25088) + _14467_v9632Offset]) + _14469_v9636[global_id + _14469_v9636Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
