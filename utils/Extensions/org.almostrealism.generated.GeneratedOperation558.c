#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation558_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7834_v5658Offset = (int) offsetArr[0];
jint _7832_v5656Offset = (int) offsetArr[1];
jint _7833_v5657Offset = (int) offsetArr[2];
jint _7834_v5658Size = (int) sizeArr[0];
jint _7832_v5656Size = (int) sizeArr[1];
jint _7833_v5657Size = (int) sizeArr[2];
jint _7834_v5658Dim0 = (int) dim0Arr[0];
jint _7832_v5656Dim0 = (int) dim0Arr[1];
jint _7833_v5657Dim0 = (int) dim0Arr[2];
double *_7834_v5658 = ((double *) argArr[0]);
double *_7832_v5656 = ((double *) argArr[1]);
double *_7833_v5657 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7834_v5658[(global_id * _7834_v5658Dim0) + _7834_v5658Offset] = _7832_v5656[(((global_id * 3) % 3) + (global_id * _7832_v5656Dim0)) + _7832_v5656Offset] * _7833_v5657[(((global_id * 3) % 3) + (global_id * _7833_v5657Dim0)) + _7833_v5657Offset];
_7834_v5658[(global_id * _7834_v5658Dim0) + _7834_v5658Offset + 1] = _7832_v5656[(global_id * _7832_v5656Dim0) + _7832_v5656Offset + 1] * _7833_v5657[(global_id * _7833_v5657Dim0) + _7833_v5657Offset + 1];
_7834_v5658[(global_id * _7834_v5658Dim0) + _7834_v5658Offset + 2] = _7832_v5656[(global_id * _7832_v5656Dim0) + _7832_v5656Offset + 2] * _7833_v5657[(global_id * _7833_v5657Dim0) + _7833_v5657Offset + 2];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
