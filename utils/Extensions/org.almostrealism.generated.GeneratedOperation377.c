#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation377_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5247_v3996Offset = (int) offsetArr[0];
jint _5247_v3997Offset = (int) offsetArr[1];
jint _5247_v3996Size = (int) sizeArr[0];
jint _5247_v3997Size = (int) sizeArr[1];
jint _5247_v3996Dim0 = (int) dim0Arr[0];
jint _5247_v3997Dim0 = (int) dim0Arr[1];
double *_5247_v3996 = ((double *) argArr[0]);
double *_5247_v3997 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5247_v3996[(global_id * _5247_v3996Dim0) + _5247_v3996Offset] = 0.0;
for (int _5247_i = 0; _5247_i < 30;) {
jint k_5247_i = (global_id * 30) + _5247_i;
_5247_v3996[(global_id * _5247_v3996Dim0) + _5247_v3996Offset] = _5247_v3997[(k_5247_i) + _5247_v3997Offset] + _5247_v3996[(global_id * _5247_v3996Dim0) + _5247_v3996Offset];
_5247_i = _5247_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
