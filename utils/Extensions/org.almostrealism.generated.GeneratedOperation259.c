#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation259_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2974_v2787Offset = (int) offsetArr[0];
jint _2960_v2765Offset = (int) offsetArr[1];
jint _2961_v2763Offset = (int) offsetArr[2];
jint _2963_v2761Offset = (int) offsetArr[3];
jint _2965_v2758Offset = (int) offsetArr[4];
jint _2968_v2776Offset = (int) offsetArr[5];
jint _2971_v2781Offset = (int) offsetArr[6];
jint _2974_v2787Size = (int) sizeArr[0];
jint _2960_v2765Size = (int) sizeArr[1];
jint _2961_v2763Size = (int) sizeArr[2];
jint _2963_v2761Size = (int) sizeArr[3];
jint _2965_v2758Size = (int) sizeArr[4];
jint _2968_v2776Size = (int) sizeArr[5];
jint _2971_v2781Size = (int) sizeArr[6];
jint _2974_v2787Dim0 = (int) dim0Arr[0];
jint _2960_v2765Dim0 = (int) dim0Arr[1];
jint _2961_v2763Dim0 = (int) dim0Arr[2];
jint _2963_v2761Dim0 = (int) dim0Arr[3];
jint _2965_v2758Dim0 = (int) dim0Arr[4];
jint _2968_v2776Dim0 = (int) dim0Arr[5];
jint _2971_v2781Dim0 = (int) dim0Arr[6];
double *_2974_v2787 = ((double *) argArr[0]);
double *_2960_v2765 = ((double *) argArr[1]);
double *_2961_v2763 = ((double *) argArr[2]);
double *_2963_v2761 = ((double *) argArr[3]);
double *_2965_v2758 = ((double *) argArr[4]);
double *_2968_v2776 = ((double *) argArr[5]);
double *_2971_v2781 = ((double *) argArr[6]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2974_v2787[(global_id * _2974_v2787Dim0) + _2974_v2787Offset] = 0.0;
for (int _2974_i = 0; _2974_i < 21952;) {
jint k_2974_i = (global_id * 21952) + _2974_i;
_2974_v2787[(global_id * _2974_v2787Dim0) + _2974_v2787Offset] = ((((((- (((k_2974_i) / 21952) + (_2974_i * 21952))) + (_2974_i * 21953)) == 0) ? (((_2961_v2763[_2974_i + _2961_v2763Offset] * _2960_v2765[_2960_v2765Offset]) * _2963_v2761[_2974_i + _2963_v2761Offset]) * _2965_v2758[_2974_i + _2965_v2758Offset]) : 0) + ((((- (((k_2974_i) / 21952) + (_2974_i * 21952))) + (_2974_i * 21953)) == 0) ? _2968_v2776[_2974_i + _2968_v2776Offset] : 0)) * _2971_v2781[_2974_i + _2971_v2781Offset]) + _2974_v2787[(global_id * _2974_v2787Dim0) + _2974_v2787Offset];
_2974_i = _2974_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
