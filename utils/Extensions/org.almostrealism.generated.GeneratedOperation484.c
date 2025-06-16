#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation484_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6426_v4807Offset = (int) offsetArr[0];
jint _6421_v4796Offset = (int) offsetArr[1];
jint _6426_v4807Size = (int) sizeArr[0];
jint _6421_v4796Size = (int) sizeArr[1];
jint _6426_v4807Dim0 = (int) dim0Arr[0];
jint _6421_v4796Dim0 = (int) dim0Arr[1];
double *_6426_v4807 = ((double *) argArr[0]);
double *_6421_v4796 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6426_v4807[global_id + _6426_v4807Offset] = ((- ((_6421_v4796[_6421_v4796Offset + 37] + _6421_v4796[_6421_v4796Offset + 38] + _6421_v4796[_6421_v4796Offset + 42] + _6421_v4796[_6421_v4796Offset + 39] + _6421_v4796[_6421_v4796Offset + 45] + _6421_v4796[_6421_v4796Offset + 41] + _6421_v4796[_6421_v4796Offset + 40] + _6421_v4796[_6421_v4796Offset + 47] + _6421_v4796[_6421_v4796Offset + 32] + _6421_v4796[_6421_v4796Offset + 33] + _6421_v4796[_6421_v4796Offset + 44] + _6421_v4796[_6421_v4796Offset + 34] + _6421_v4796[_6421_v4796Offset + 35] + _6421_v4796[_6421_v4796Offset + 36] + _6421_v4796[_6421_v4796Offset + 46] + _6421_v4796[_6421_v4796Offset + 43]) / 16.0)) + _6421_v4796[global_id + _6421_v4796Offset + 32]) * ((- ((_6421_v4796[_6421_v4796Offset + 37] + _6421_v4796[_6421_v4796Offset + 38] + _6421_v4796[_6421_v4796Offset + 42] + _6421_v4796[_6421_v4796Offset + 39] + _6421_v4796[_6421_v4796Offset + 45] + _6421_v4796[_6421_v4796Offset + 41] + _6421_v4796[_6421_v4796Offset + 40] + _6421_v4796[_6421_v4796Offset + 47] + _6421_v4796[_6421_v4796Offset + 32] + _6421_v4796[_6421_v4796Offset + 33] + _6421_v4796[_6421_v4796Offset + 44] + _6421_v4796[_6421_v4796Offset + 34] + _6421_v4796[_6421_v4796Offset + 35] + _6421_v4796[_6421_v4796Offset + 36] + _6421_v4796[_6421_v4796Offset + 46] + _6421_v4796[_6421_v4796Offset + 43]) / 16.0)) + _6421_v4796[global_id + _6421_v4796Offset + 32]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
