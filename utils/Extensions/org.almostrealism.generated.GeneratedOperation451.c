#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation451_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6122_v4532Offset = (int) offsetArr[0];
jint _6117_v4521Offset = (int) offsetArr[1];
jint _6122_v4532Size = (int) sizeArr[0];
jint _6117_v4521Size = (int) sizeArr[1];
jint _6122_v4532Dim0 = (int) dim0Arr[0];
jint _6117_v4521Dim0 = (int) dim0Arr[1];
double *_6122_v4532 = ((double *) argArr[0]);
double *_6117_v4521 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6122_v4532[global_id + _6122_v4532Offset] = ((- ((_6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 8] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 1] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 13] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 2] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 3] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 4] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 15] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 12] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 5] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 6] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 11] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 7] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 14] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 10] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 9] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset]) / 16.0)) + _6117_v4521[global_id + _6117_v4521Offset]) * ((- ((_6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 8] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 1] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 13] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 2] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 3] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 4] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 15] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 12] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 5] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 6] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 11] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 7] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 14] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 10] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset + 9] + _6117_v4521[((global_id / 16) * 16) + _6117_v4521Offset]) / 16.0)) + _6117_v4521[global_id + _6117_v4521Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
