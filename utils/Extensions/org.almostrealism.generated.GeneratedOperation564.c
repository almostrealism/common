#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation564_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7985_v5740Offset = (int) offsetArr[0];
jint _7985_v5741Offset = (int) offsetArr[1];
jint _7985_v5740Size = (int) sizeArr[0];
jint _7985_v5741Size = (int) sizeArr[1];
jint _7985_v5740Dim0 = (int) dim0Arr[0];
jint _7985_v5741Dim0 = (int) dim0Arr[1];
double *_7985_v5740 = ((double *) argArr[0]);
double *_7985_v5741 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7985_v5740[(global_id * _7985_v5740Dim0) + _7985_v5740Offset] = 0.0;
for (int _7985_i = 0; _7985_i < 30;) {
jint k_7985_i = (global_id * 30) + _7985_i;
_7985_v5740[(global_id * _7985_v5740Dim0) + _7985_v5740Offset] = _7985_v5741[(k_7985_i) + _7985_v5741Offset] + _7985_v5740[(global_id * _7985_v5740Dim0) + _7985_v5740Offset];
_7985_i = _7985_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
