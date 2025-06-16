#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation470_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6266_v4681Offset = (int) offsetArr[0];
jint _6261_v4670Offset = (int) offsetArr[1];
jint _6266_v4681Size = (int) sizeArr[0];
jint _6261_v4670Size = (int) sizeArr[1];
jint _6266_v4681Dim0 = (int) dim0Arr[0];
jint _6261_v4670Dim0 = (int) dim0Arr[1];
double *_6266_v4681 = ((double *) argArr[0]);
double *_6261_v4670 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6266_v4681[global_id + _6266_v4681Offset] = ((- ((_6261_v4670[_6261_v4670Offset + 5] + _6261_v4670[_6261_v4670Offset + 6] + _6261_v4670[_6261_v4670Offset + 10] + _6261_v4670[_6261_v4670Offset + 7] + _6261_v4670[_6261_v4670Offset + 13] + _6261_v4670[_6261_v4670Offset + 9] + _6261_v4670[_6261_v4670Offset + 8] + _6261_v4670[_6261_v4670Offset + 15] + _6261_v4670[_6261_v4670Offset] + _6261_v4670[_6261_v4670Offset + 1] + _6261_v4670[_6261_v4670Offset + 12] + _6261_v4670[_6261_v4670Offset + 2] + _6261_v4670[_6261_v4670Offset + 3] + _6261_v4670[_6261_v4670Offset + 4] + _6261_v4670[_6261_v4670Offset + 14] + _6261_v4670[_6261_v4670Offset + 11]) / 16.0)) + _6261_v4670[global_id + _6261_v4670Offset]) * ((- ((_6261_v4670[_6261_v4670Offset + 5] + _6261_v4670[_6261_v4670Offset + 6] + _6261_v4670[_6261_v4670Offset + 10] + _6261_v4670[_6261_v4670Offset + 7] + _6261_v4670[_6261_v4670Offset + 13] + _6261_v4670[_6261_v4670Offset + 9] + _6261_v4670[_6261_v4670Offset + 8] + _6261_v4670[_6261_v4670Offset + 15] + _6261_v4670[_6261_v4670Offset] + _6261_v4670[_6261_v4670Offset + 1] + _6261_v4670[_6261_v4670Offset + 12] + _6261_v4670[_6261_v4670Offset + 2] + _6261_v4670[_6261_v4670Offset + 3] + _6261_v4670[_6261_v4670Offset + 4] + _6261_v4670[_6261_v4670Offset + 14] + _6261_v4670[_6261_v4670Offset + 11]) / 16.0)) + _6261_v4670[global_id + _6261_v4670Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
