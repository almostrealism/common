#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation316_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3922_v3335Offset = (int) offsetArr[0];
jint _3903_v3320Offset = (int) offsetArr[1];
jint _3922_v3335Size = (int) sizeArr[0];
jint _3903_v3320Size = (int) sizeArr[1];
jint _3922_v3335Dim0 = (int) dim0Arr[0];
jint _3903_v3320Dim0 = (int) dim0Arr[1];
double *_3922_v3335 = ((double *) argArr[0]);
double *_3903_v3320 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3922_v3335[_3922_v3335Offset] = ((((- ((_3903_v3320[_3903_v3320Offset] + _3903_v3320[_3903_v3320Offset + 1]) / 2.0)) + _3903_v3320[_3903_v3320Offset]) * ((- ((_3903_v3320[_3903_v3320Offset] + _3903_v3320[_3903_v3320Offset + 1]) / 2.0)) + _3903_v3320[_3903_v3320Offset])) + (((- ((_3903_v3320[_3903_v3320Offset] + _3903_v3320[_3903_v3320Offset + 1]) / 2.0)) + _3903_v3320[_3903_v3320Offset + 1]) * ((- ((_3903_v3320[_3903_v3320Offset] + _3903_v3320[_3903_v3320Offset + 1]) / 2.0)) + _3903_v3320[_3903_v3320Offset + 1]))) / 2.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
