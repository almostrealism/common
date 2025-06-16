#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation566_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7991_v5725Offset = (int) offsetArr[0];
jint _7991_v5726Offset = (int) offsetArr[1];
jint _7991_v5725Size = (int) sizeArr[0];
jint _7991_v5726Size = (int) sizeArr[1];
jint _7991_v5725Dim0 = (int) dim0Arr[0];
jint _7991_v5726Dim0 = (int) dim0Arr[1];
double *_7991_v5725 = ((double *) argArr[0]);
double *_7991_v5726 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7991_v5725[(global_id * _7991_v5725Dim0) + _7991_v5725Offset] = 0.0;
for (int _7991_i = 0; _7991_i < 30;) {
jint k_7991_i = (global_id * 30) + _7991_i;
_7991_v5725[(global_id * _7991_v5725Dim0) + _7991_v5725Offset] = _7991_v5726[(k_7991_i) + _7991_v5726Offset] + _7991_v5725[(global_id * _7991_v5725Dim0) + _7991_v5725Offset];
_7991_i = _7991_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
