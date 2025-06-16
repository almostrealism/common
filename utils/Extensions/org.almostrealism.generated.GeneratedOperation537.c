#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation537_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7238_v5323Offset = (int) offsetArr[0];
jint _7236_v5321Offset = (int) offsetArr[1];
jint _7237_v5322Offset = (int) offsetArr[2];
jint _7238_v5323Size = (int) sizeArr[0];
jint _7236_v5321Size = (int) sizeArr[1];
jint _7237_v5322Size = (int) sizeArr[2];
jint _7238_v5323Dim0 = (int) dim0Arr[0];
jint _7236_v5321Dim0 = (int) dim0Arr[1];
jint _7237_v5322Dim0 = (int) dim0Arr[2];
double *_7238_v5323 = ((double *) argArr[0]);
double *_7236_v5321 = ((double *) argArr[1]);
double *_7237_v5322 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7238_v5323[(global_id * _7238_v5323Dim0) + _7238_v5323Offset] = _7236_v5321[(((global_id * 2) % 2) + (global_id * _7236_v5321Dim0)) + _7236_v5321Offset] * _7237_v5322[(((global_id * 2) % 2) + (global_id * _7237_v5322Dim0)) + _7237_v5322Offset];
_7238_v5323[(global_id * _7238_v5323Dim0) + _7238_v5323Offset + 1] = _7236_v5321[(global_id * _7236_v5321Dim0) + _7236_v5321Offset + 1] * _7237_v5322[(global_id * _7237_v5322Dim0) + _7237_v5322Offset + 1];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
