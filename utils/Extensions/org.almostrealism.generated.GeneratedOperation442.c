#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation442_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5677_v4377Offset = (int) offsetArr[0];
jint _5677_v4378Offset = (int) offsetArr[1];
jint _5677_v4380Offset = (int) offsetArr[2];
jint _5677_v4377Size = (int) sizeArr[0];
jint _5677_v4378Size = (int) sizeArr[1];
jint _5677_v4380Size = (int) sizeArr[2];
jint _5677_v4377Dim0 = (int) dim0Arr[0];
jint _5677_v4378Dim0 = (int) dim0Arr[1];
jint _5677_v4380Dim0 = (int) dim0Arr[2];
double *_5677_v4377 = ((double *) argArr[0]);
double *_5677_v4378 = ((double *) argArr[1]);
double *_5677_v4380 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5677_v4377[global_id + _5677_v4377Offset] = _5677_v4378[global_id + _5677_v4378Offset + 90] * _5677_v4380[global_id + _5677_v4380Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
