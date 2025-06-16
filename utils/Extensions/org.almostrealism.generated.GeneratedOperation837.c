#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation837_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12599_v8105Offset = (int) offsetArr[0];
jint _12599_v8106Offset = (int) offsetArr[1];
jint _12599_v8105Size = (int) sizeArr[0];
jint _12599_v8106Size = (int) sizeArr[1];
jint _12599_v8105Dim0 = (int) dim0Arr[0];
jint _12599_v8106Dim0 = (int) dim0Arr[1];
double *_12599_v8105 = ((double *) argArr[0]);
double *_12599_v8106 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12599_v8105[(global_id * _12599_v8105Dim0) + _12599_v8105Offset] = 0.0;
for (int _12599_i = 0; _12599_i < 120;) {
jint k_12599_i = (global_id * 120) + _12599_i;
_12599_v8105[(global_id * _12599_v8105Dim0) + _12599_v8105Offset] = _12599_v8106[(k_12599_i) + _12599_v8106Offset] + _12599_v8105[(global_id * _12599_v8105Dim0) + _12599_v8105Offset];
_12599_i = _12599_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
