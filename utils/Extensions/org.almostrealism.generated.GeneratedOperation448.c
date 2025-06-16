#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation448_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6152_v4587Offset = (int) offsetArr[0];
jint _6117_v4540Offset = (int) offsetArr[1];
jint _6137_v4556Offset = (int) offsetArr[2];
jint _6146_v4574Offset = (int) offsetArr[3];
jint _6152_v4587Size = (int) sizeArr[0];
jint _6117_v4540Size = (int) sizeArr[1];
jint _6137_v4556Size = (int) sizeArr[2];
jint _6146_v4574Size = (int) sizeArr[3];
jint _6152_v4587Dim0 = (int) dim0Arr[0];
jint _6117_v4540Dim0 = (int) dim0Arr[1];
jint _6137_v4556Dim0 = (int) dim0Arr[2];
jint _6146_v4574Dim0 = (int) dim0Arr[3];
double *_6152_v4587 = ((double *) argArr[0]);
double *_6117_v4540 = ((double *) argArr[1]);
double *_6137_v4556 = ((double *) argArr[2]);
double *_6146_v4574 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6152_v4587[global_id + _6152_v4587Offset] = ((((_6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 8] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 1] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 13] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 2] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 3] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 4] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 15] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 12] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 5] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 6] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 11] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 7] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 14] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 10] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset + 9] + _6137_v4556[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6137_v4556Offset]) * -0.0625) + ((((- (global_id % 96)) + (global_id / 96)) == 0) ? 1 : 0)) * ((- ((_6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 8] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 1] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 13] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 2] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 3] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 4] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 15] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 12] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 5] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 6] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 11] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 7] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 14] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 10] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 9] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset]) / 16.0)) + _6117_v4540[(global_id / 96) + _6117_v4540Offset])) + ((((_6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 8] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 1] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 13] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 2] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 3] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 4] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 15] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 12] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 5] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 6] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 11] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 7] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 14] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 10] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset + 9] + _6146_v4574[((((global_id / 1536) * 96) + (global_id % 96)) * 16) + _6146_v4574Offset]) * -0.0625) + ((((- (global_id % 96)) + (global_id / 96)) == 0) ? 1 : 0)) * ((- ((_6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 8] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 1] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 13] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 2] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 3] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 4] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 15] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 12] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 5] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 6] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 11] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 7] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 14] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 10] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset + 9] + _6117_v4540[((global_id / 1536) * 16) + _6117_v4540Offset]) / 16.0)) + _6117_v4540[(global_id / 96) + _6117_v4540Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
