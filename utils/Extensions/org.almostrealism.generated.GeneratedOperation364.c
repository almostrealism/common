#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation364_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4739_v3688Offset = (int) offsetArr[0];
jint _4688_v3673Offset = (int) offsetArr[1];
jint _4738_v3687Offset = (int) offsetArr[2];
jint _4739_v3688Size = (int) sizeArr[0];
jint _4688_v3673Size = (int) sizeArr[1];
jint _4738_v3687Size = (int) sizeArr[2];
jint _4739_v3688Dim0 = (int) dim0Arr[0];
jint _4688_v3673Dim0 = (int) dim0Arr[1];
jint _4738_v3687Dim0 = (int) dim0Arr[2];
double *_4739_v3688 = ((double *) argArr[0]);
double *_4688_v3673 = ((double *) argArr[1]);
double *_4738_v3687 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4739_v3688[global_id + _4739_v3688Offset] = ((- ((_4688_v3673[_4688_v3673Offset] + _4688_v3673[_4688_v3673Offset + 1]) / 2.0)) + _4688_v3673[(global_id / 2) + _4688_v3673Offset]) * _4738_v3687[(global_id % 2) + _4738_v3687Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
