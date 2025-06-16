#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation772_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _11794_v7735Offset = (int) offsetArr[0];
jint _11794_v7736Offset = (int) offsetArr[1];
jint _11794_v7738Offset = (int) offsetArr[2];
jint _11794_v7735Size = (int) sizeArr[0];
jint _11794_v7736Size = (int) sizeArr[1];
jint _11794_v7738Size = (int) sizeArr[2];
jint _11794_v7735Dim0 = (int) dim0Arr[0];
jint _11794_v7736Dim0 = (int) dim0Arr[1];
jint _11794_v7738Dim0 = (int) dim0Arr[2];
double *_11794_v7735 = ((double *) argArr[0]);
double *_11794_v7736 = ((double *) argArr[1]);
double *_11794_v7738 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_11794_v7735[global_id + _11794_v7735Offset] = _11794_v7736[global_id + _11794_v7736Offset + 25] * _11794_v7738[global_id + _11794_v7738Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
