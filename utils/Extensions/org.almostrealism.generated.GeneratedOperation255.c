#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation255_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2984_v2755Offset = (int) offsetArr[0];
jint _2976_v2747Offset = (int) offsetArr[1];
jint _2977_v2748Offset = (int) offsetArr[2];
jint _2984_v2755Size = (int) sizeArr[0];
jint _2976_v2747Size = (int) sizeArr[1];
jint _2977_v2748Size = (int) sizeArr[2];
jint _2984_v2755Dim0 = (int) dim0Arr[0];
jint _2976_v2747Dim0 = (int) dim0Arr[1];
jint _2977_v2748Dim0 = (int) dim0Arr[2];
double *_2984_v2755 = ((double *) argArr[0]);
double *_2976_v2747 = ((double *) argArr[1]);
double *_2977_v2748 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2984_v2755[(global_id * _2984_v2755Dim0) + _2984_v2755Offset] = pow((- _2977_v2748[(((global_id / _2977_v2748Size) * _2977_v2748Dim0) + (global_id % _2977_v2748Size)) + _2977_v2748Offset]) + _2976_v2747[(((global_id / _2976_v2747Size) * _2976_v2747Dim0) + (global_id % _2976_v2747Size)) + _2976_v2747Offset], 2.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
