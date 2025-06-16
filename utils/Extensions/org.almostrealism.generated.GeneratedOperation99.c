#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation99_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1179_v864Offset = (int) offsetArr[0];
jint _1174_v853Offset = (int) offsetArr[1];
jint _1176_v858Offset = (int) offsetArr[2];
jint _1179_v864Size = (int) sizeArr[0];
jint _1174_v853Size = (int) sizeArr[1];
jint _1176_v858Size = (int) sizeArr[2];
jint _1179_v864Dim0 = (int) dim0Arr[0];
jint _1174_v853Dim0 = (int) dim0Arr[1];
jint _1176_v858Dim0 = (int) dim0Arr[2];
double *_1179_v864 = ((double *) argArr[0]);
double *_1174_v853 = ((double *) argArr[1]);
double *_1176_v858 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1179_v864[(global_id * _1179_v864Dim0) + _1179_v864Offset] = 0.0;
for (int _1179_i = 0; _1179_i < 3;) {
jint k_1179_i = (global_id * 3) + _1179_i;
_1179_v864[(global_id * _1179_v864Dim0) + _1179_v864Offset] = (_1174_v853[((((k_1179_i) / 30) * 6) + (((k_1179_i) % 30) / 15) + (_1179_i * 2)) + _1174_v853Offset] * _1176_v858[((((k_1179_i) / 30) * 15) + (((k_1179_i) % 15) / 3) + (((k_1179_i) % 3) * 5)) + _1176_v858Offset]) + _1179_v864[(global_id * _1179_v864Dim0) + _1179_v864Offset];
_1179_i = _1179_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
