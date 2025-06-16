#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation960_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14478_v9567Offset = (int) offsetArr[0];
jint _14478_v9568Offset = (int) offsetArr[1];
jint _14478_v9567Size = (int) sizeArr[0];
jint _14478_v9568Size = (int) sizeArr[1];
jint _14478_v9567Dim0 = (int) dim0Arr[0];
jint _14478_v9568Dim0 = (int) dim0Arr[1];
double *_14478_v9567 = ((double *) argArr[0]);
double *_14478_v9568 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14478_v9567[(global_id * _14478_v9567Dim0) + _14478_v9567Offset] = 0;
for (int _14478_i = 0; _14478_i < 25088;) {
jint k_14478_i = (global_id * 25088) + _14478_i;
_14478_v9567[(global_id * _14478_v9567Dim0) + _14478_v9567Offset] = (_14478_v9568[(k_14478_i) + _14478_v9568Offset] > _14478_v9568[(((int) (_14478_v9567[(global_id * _14478_v9567Dim0) + _14478_v9567Offset] + (global_id * 25088.0))) % 100352) + _14478_v9568Offset]) ? _14478_i : _14478_v9567[(global_id * _14478_v9567Dim0) + _14478_v9567Offset];
_14478_i = _14478_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
