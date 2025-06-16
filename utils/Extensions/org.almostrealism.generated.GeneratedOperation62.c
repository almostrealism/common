#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation62_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _930_v646Offset = (int) offsetArr[0];
jint _930_v647Offset = (int) offsetArr[1];
jint _930_v646Size = (int) sizeArr[0];
jint _930_v647Size = (int) sizeArr[1];
jint _930_v646Dim0 = (int) dim0Arr[0];
jint _930_v647Dim0 = (int) dim0Arr[1];
double *_930_v646 = ((double *) argArr[0]);
double *_930_v647 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_930_v646[global_id + _930_v646Offset] = _930_v647[(((global_id / 2048) * 1024) + (global_id % 1024)) + _930_v647Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
