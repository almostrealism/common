#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation468_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6216_v4653Offset = (int) offsetArr[0];
jint _6216_v4654Offset = (int) offsetArr[1];
jint _6216_v4653Size = (int) sizeArr[0];
jint _6216_v4654Size = (int) sizeArr[1];
jint _6216_v4653Dim0 = (int) dim0Arr[0];
jint _6216_v4654Dim0 = (int) dim0Arr[1];
double *_6216_v4653 = ((double *) argArr[0]);
double *_6216_v4654 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6216_v4653[(global_id * _6216_v4653Dim0) + _6216_v4653Offset] = 0.0;
for (int _6216_i = 0; _6216_i < 96;) {
jint k_6216_i = (global_id * 96) + _6216_i;
_6216_v4653[(global_id * _6216_v4653Dim0) + _6216_v4653Offset] = _6216_v4654[(k_6216_i) + _6216_v4654Offset] + _6216_v4653[(global_id * _6216_v4653Dim0) + _6216_v4653Offset];
_6216_i = _6216_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
