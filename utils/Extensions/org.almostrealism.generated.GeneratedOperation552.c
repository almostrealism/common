#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation552_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7806_v5615Offset = (int) offsetArr[0];
jint _7785_v5568Offset = (int) offsetArr[1];
jint _7804_v5610Offset = (int) offsetArr[2];
jint _7806_v5615Size = (int) sizeArr[0];
jint _7785_v5568Size = (int) sizeArr[1];
jint _7804_v5610Size = (int) sizeArr[2];
jint _7806_v5615Dim0 = (int) dim0Arr[0];
jint _7785_v5568Dim0 = (int) dim0Arr[1];
jint _7804_v5610Dim0 = (int) dim0Arr[2];
double *_7806_v5615 = ((double *) argArr[0]);
double *_7785_v5568 = ((double *) argArr[1]);
double *_7804_v5610 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7806_v5615[global_id + _7806_v5615Offset] = (((- (((global_id % 3) * 3) + (global_id / 3))) + ((global_id % 3) * 4)) == 0) ? ((((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[(global_id % 3) + _7785_v5568Offset]) / pow((((((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset]) * ((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset])) + (((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset + 1]) * ((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset + 1])) + (((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset + 2]) * ((- ((_7785_v5568[_7785_v5568Offset] + _7785_v5568[_7785_v5568Offset + 1] + _7785_v5568[_7785_v5568Offset + 2]) / 3.0)) + _7785_v5568[_7785_v5568Offset + 2]))) / 3.0) + 1.0E-5, 0.5)) * _7804_v5610[(global_id % 3) + _7804_v5610Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
