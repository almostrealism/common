#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation348_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4493_v3647Offset = (int) offsetArr[0];
jint _4489_v3639Offset = (int) offsetArr[1];
jint _4492_v3644Offset = (int) offsetArr[2];
jint _4493_v3647Size = (int) sizeArr[0];
jint _4489_v3639Size = (int) sizeArr[1];
jint _4492_v3644Size = (int) sizeArr[2];
jint _4493_v3647Dim0 = (int) dim0Arr[0];
jint _4489_v3639Dim0 = (int) dim0Arr[1];
jint _4492_v3644Dim0 = (int) dim0Arr[2];
double *_4493_v3647 = ((double *) argArr[0]);
double *_4489_v3639 = ((double *) argArr[1]);
double *_4492_v3644 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4493_v3647[global_id + _4493_v3647Offset] = ((- (_4489_v3639[(global_id / 5488) + _4489_v3639Offset] / 5488.0)) + _4492_v3644[global_id + _4492_v3644Offset]) * ((- (_4489_v3639[(global_id / 5488) + _4489_v3639Offset] / 5488.0)) + _4492_v3644[global_id + _4492_v3644Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
