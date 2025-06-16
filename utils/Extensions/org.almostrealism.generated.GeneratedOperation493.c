#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation493_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6545_v4888Offset = (int) offsetArr[0];
jint _6540_v4885Offset = (int) offsetArr[1];
jint _6545_v4888Size = (int) sizeArr[0];
jint _6540_v4885Size = (int) sizeArr[1];
jint _6545_v4888Dim0 = (int) dim0Arr[0];
jint _6540_v4885Dim0 = (int) dim0Arr[1];
double *_6545_v4888 = ((double *) argArr[0]);
double *_6540_v4885 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6545_v4888[global_id + _6545_v4888Offset] = (_6540_v4885[global_id + _6540_v4885Offset + 48] + -0.04982179671828755) / 0.03627004582331105;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
