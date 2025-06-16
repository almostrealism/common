#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation818_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12543_v8200Offset = (int) offsetArr[0];
jint _12543_v8201Offset = (int) offsetArr[1];
jint _12543_v8200Size = (int) sizeArr[0];
jint _12543_v8201Size = (int) sizeArr[1];
jint _12543_v8200Dim0 = (int) dim0Arr[0];
jint _12543_v8201Dim0 = (int) dim0Arr[1];
double *_12543_v8200 = ((double *) argArr[0]);
double *_12543_v8201 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12543_v8200[(global_id * _12543_v8200Dim0) + _12543_v8200Offset] = 0.0;
for (int _12543_i = 0; _12543_i < 30;) {
jint k_12543_i = (global_id * 30) + _12543_i;
_12543_v8200[(global_id * _12543_v8200Dim0) + _12543_v8200Offset] = _12543_v8201[(k_12543_i) + _12543_v8201Offset] + _12543_v8200[(global_id * _12543_v8200Dim0) + _12543_v8200Offset];
_12543_i = _12543_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
