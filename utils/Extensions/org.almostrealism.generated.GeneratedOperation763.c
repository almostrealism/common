#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation763_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11700_v7511Offset = (int) offsetArr[0];
jint _11698_v7506Offset = (int) offsetArr[1];
jint _11698_v7507Offset = (int) offsetArr[2];
jint _11699_v7509Offset = (int) offsetArr[3];
jint _11700_v7511Size = (int) sizeArr[0];
jint _11698_v7506Size = (int) sizeArr[1];
jint _11698_v7507Size = (int) sizeArr[2];
jint _11699_v7509Size = (int) sizeArr[3];
jint _11700_v7511Dim0 = (int) dim0Arr[0];
jint _11698_v7506Dim0 = (int) dim0Arr[1];
jint _11698_v7507Dim0 = (int) dim0Arr[2];
jint _11699_v7509Dim0 = (int) dim0Arr[3];
double *_11700_v7511 = ((double *) argArr[0]);
double *_11698_v7506 = ((double *) argArr[1]);
double *_11698_v7507 = ((double *) argArr[2]);
double *_11699_v7509 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11700_v7511[global_id + _11700_v7511Offset] = (_11698_v7506[global_id + _11698_v7506Offset] + _11698_v7507[global_id + _11698_v7507Offset]) * _11699_v7509[(global_id / 100) + _11699_v7509Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
