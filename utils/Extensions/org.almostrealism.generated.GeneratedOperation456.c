#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation456_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6173_v4481Offset = (int) offsetArr[0];
jint _6123_v4451Offset = (int) offsetArr[1];
jint _6168_v4471Offset = (int) offsetArr[2];
jint _6173_v4481Size = (int) sizeArr[0];
jint _6123_v4451Size = (int) sizeArr[1];
jint _6168_v4471Size = (int) sizeArr[2];
jint _6173_v4481Dim0 = (int) dim0Arr[0];
jint _6123_v4451Dim0 = (int) dim0Arr[1];
jint _6168_v4471Dim0 = (int) dim0Arr[2];
double *_6173_v4481 = ((double *) argArr[0]);
double *_6123_v4451 = ((double *) argArr[1]);
double *_6168_v4471 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6173_v4481[global_id + _6173_v4481Offset] = pow(pow(((_6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 8] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 1] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 13] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 2] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 3] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 4] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 15] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 12] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 5] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 6] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 11] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 7] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 14] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 10] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset + 9] + _6123_v4451[((global_id / 1536) * 16) + _6123_v4451Offset]) / 16.0) + 1.0E-5, 0.5), -1.0) * (((_6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 8] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 1] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 13] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 2] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 3] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 4] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 15] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 12] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 5] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 6] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 11] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 7] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 14] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 10] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset + 9] + _6168_v4471[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6168_v4471Offset]) * -0.0625) + ((((- (global_id % 96)) + (global_id / 96)) == 0) ? 1 : 0));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
