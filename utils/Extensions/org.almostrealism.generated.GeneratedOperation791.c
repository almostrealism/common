#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation791_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12128_v7918Offset = (int) offsetArr[0];
jint _12128_v7919Offset = (int) offsetArr[1];
jint _12128_v7918Size = (int) sizeArr[0];
jint _12128_v7919Size = (int) sizeArr[1];
jint _12128_v7918Dim0 = (int) dim0Arr[0];
jint _12128_v7919Dim0 = (int) dim0Arr[1];
double *_12128_v7918 = ((double *) argArr[0]);
double *_12128_v7919 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12128_v7918[global_id + _12128_v7918Offset] = _12128_v7919[((((global_id % 1600) / 20) * 80) + ((global_id / 1600) * 20) + (global_id % 20)) + _12128_v7919Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
