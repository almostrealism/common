#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation530_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7186_v5019Offset = (int) offsetArr[0];
jint _7210_v5022Offset = (int) offsetArr[1];
jint _7213_v5027Offset = (int) offsetArr[2];
jint _7186_v5019Size = (int) sizeArr[0];
jint _7210_v5022Size = (int) sizeArr[1];
jint _7213_v5027Size = (int) sizeArr[2];
jint _7186_v5019Dim0 = (int) dim0Arr[0];
jint _7210_v5022Dim0 = (int) dim0Arr[1];
jint _7213_v5027Dim0 = (int) dim0Arr[2];
double *_7186_v5019 = ((double *) argArr[0]);
double *_7210_v5022 = ((double *) argArr[1]);
double *_7213_v5027 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7213_v5027[global_id + _7213_v5027Offset] = (- ((_7210_v5022[(global_id * 2) + _7210_v5022Offset + 1] + _7210_v5022[(global_id * 2) + _7210_v5022Offset]) * _7186_v5019[_7186_v5019Offset])) + _7213_v5027[global_id + _7213_v5027Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
