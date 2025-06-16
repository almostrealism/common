#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation958_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14471_v9595Offset = (int) offsetArr[0];
jint _14471_v9596Offset = (int) offsetArr[1];
jint _14471_v9595Size = (int) sizeArr[0];
jint _14471_v9596Size = (int) sizeArr[1];
jint _14471_v9595Dim0 = (int) dim0Arr[0];
jint _14471_v9596Dim0 = (int) dim0Arr[1];
double *_14471_v9595 = ((double *) argArr[0]);
double *_14471_v9596 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14471_v9595[(global_id * _14471_v9595Dim0) + _14471_v9595Offset] = 0.0;
for (int _14471_i = 0; _14471_i < 25088;) {
jint k_14471_i = (global_id * 25088) + _14471_i;
_14471_v9595[(global_id * _14471_v9595Dim0) + _14471_v9595Offset] = _14471_v9596[(k_14471_i) + _14471_v9596Offset] + _14471_v9595[(global_id * _14471_v9595Dim0) + _14471_v9595Offset];
_14471_i = _14471_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
