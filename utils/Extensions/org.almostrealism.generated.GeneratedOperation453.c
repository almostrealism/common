#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation453_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6162_v4447Offset = (int) offsetArr[0];
jint _6111_v4432Offset = (int) offsetArr[1];
jint _6161_v4446Offset = (int) offsetArr[2];
jint _6162_v4447Size = (int) sizeArr[0];
jint _6111_v4432Size = (int) sizeArr[1];
jint _6161_v4446Size = (int) sizeArr[2];
jint _6162_v4447Dim0 = (int) dim0Arr[0];
jint _6111_v4432Dim0 = (int) dim0Arr[1];
jint _6161_v4446Dim0 = (int) dim0Arr[2];
double *_6162_v4447 = ((double *) argArr[0]);
double *_6111_v4432 = ((double *) argArr[1]);
double *_6161_v4446 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6162_v4447[global_id + _6162_v4447Offset] = ((- ((_6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 8] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 1] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 13] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 2] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 3] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 4] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 15] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 12] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 5] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 6] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 11] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 7] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 14] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 10] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset + 9] + _6111_v4432[((global_id / 1536) * 16) + _6111_v4432Offset]) / 16.0)) + _6111_v4432[(global_id / 96) + _6111_v4432Offset]) * _6161_v4446[(((global_id / 1536) * 96) + (global_id % 96)) + _6161_v4446Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
