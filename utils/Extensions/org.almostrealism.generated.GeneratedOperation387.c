#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation387_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5281_v3998Offset = (int) offsetArr[0];
jint _5281_v3999Offset = (int) offsetArr[1];
jint _5281_v3998Size = (int) sizeArr[0];
jint _5281_v3999Size = (int) sizeArr[1];
jint _5281_v3998Dim0 = (int) dim0Arr[0];
jint _5281_v3999Dim0 = (int) dim0Arr[1];
double *_5281_v3998 = ((double *) argArr[0]);
double *_5281_v3999 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5281_v3998[(global_id * _5281_v3998Dim0) + _5281_v3998Offset] = 0.0;
for (int _5281_i = 0; _5281_i < 30;) {
jint k_5281_i = (global_id * 30) + _5281_i;
_5281_v3998[(global_id * _5281_v3998Dim0) + _5281_v3998Offset] = _5281_v3999[(k_5281_i) + _5281_v3999Offset] + _5281_v3998[(global_id * _5281_v3998Dim0) + _5281_v3998Offset];
_5281_i = _5281_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
