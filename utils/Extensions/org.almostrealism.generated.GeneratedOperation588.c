#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation588_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9131_v5864Offset = (int) offsetArr[0];
jint _9064_v5853Offset = (int) offsetArr[1];
jint _9128_v5856Offset = (int) offsetArr[2];
jint _9128_v5857Offset = (int) offsetArr[3];
jint _9130_v5862Offset = (int) offsetArr[4];
jint _9131_v5864Size = (int) sizeArr[0];
jint _9064_v5853Size = (int) sizeArr[1];
jint _9128_v5856Size = (int) sizeArr[2];
jint _9128_v5857Size = (int) sizeArr[3];
jint _9130_v5862Size = (int) sizeArr[4];
jint _9131_v5864Dim0 = (int) dim0Arr[0];
jint _9064_v5853Dim0 = (int) dim0Arr[1];
jint _9128_v5856Dim0 = (int) dim0Arr[2];
jint _9128_v5857Dim0 = (int) dim0Arr[3];
jint _9130_v5862Dim0 = (int) dim0Arr[4];
double *_9131_v5864 = ((double *) argArr[0]);
double *_9064_v5853 = ((double *) argArr[1]);
double *_9128_v5856 = ((double *) argArr[2]);
double *_9128_v5857 = ((double *) argArr[3]);
double *_9130_v5862 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9131_v5864[global_id + _9131_v5864Offset] = (_9064_v5853[(global_id / 8) + _9064_v5853Offset] * (_9128_v5856[global_id + _9128_v5856Offset] + _9128_v5857[global_id + _9128_v5857Offset])) * _9130_v5862[(global_id / 8) + _9130_v5862Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
