#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation396_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5304_v3895Offset = (int) offsetArr[0];
jint _5304_v3896Offset = (int) offsetArr[1];
jint _5304_v3895Size = (int) sizeArr[0];
jint _5304_v3896Size = (int) sizeArr[1];
jint _5304_v3895Dim0 = (int) dim0Arr[0];
jint _5304_v3896Dim0 = (int) dim0Arr[1];
double *_5304_v3895 = ((double *) argArr[0]);
double *_5304_v3896 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5304_v3895[(global_id * _5304_v3895Dim0) + _5304_v3895Offset] = 0.0;
for (int _5304_i = 0; _5304_i < 120;) {
jint k_5304_i = (global_id * 120) + _5304_i;
_5304_v3895[(global_id * _5304_v3895Dim0) + _5304_v3895Offset] = _5304_v3896[(k_5304_i) + _5304_v3896Offset] + _5304_v3895[(global_id * _5304_v3895Dim0) + _5304_v3895Offset];
_5304_i = _5304_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
