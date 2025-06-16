#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation943_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14243_v9167Offset = (int) offsetArr[0];
jint _14241_v9162Offset = (int) offsetArr[1];
jint _14241_v9163Offset = (int) offsetArr[2];
jint _14242_v9165Offset = (int) offsetArr[3];
jint _14243_v9167Size = (int) sizeArr[0];
jint _14241_v9162Size = (int) sizeArr[1];
jint _14241_v9163Size = (int) sizeArr[2];
jint _14242_v9165Size = (int) sizeArr[3];
jint _14243_v9167Dim0 = (int) dim0Arr[0];
jint _14241_v9162Dim0 = (int) dim0Arr[1];
jint _14241_v9163Dim0 = (int) dim0Arr[2];
jint _14242_v9165Dim0 = (int) dim0Arr[3];
double *_14243_v9167 = ((double *) argArr[0]);
double *_14241_v9162 = ((double *) argArr[1]);
double *_14241_v9163 = ((double *) argArr[2]);
double *_14242_v9165 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14243_v9167[global_id + _14243_v9167Offset] = (_14241_v9162[global_id + _14241_v9162Offset] + _14241_v9163[global_id + _14241_v9163Offset]) * _14242_v9165[(global_id / 2) + _14242_v9165Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
