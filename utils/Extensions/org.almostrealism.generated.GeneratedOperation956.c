#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation956_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14466_v9628Offset = (int) offsetArr[0];
jint _14466_v9629Offset = (int) offsetArr[1];
jint _14466_v9628Size = (int) sizeArr[0];
jint _14466_v9629Size = (int) sizeArr[1];
jint _14466_v9628Dim0 = (int) dim0Arr[0];
jint _14466_v9629Dim0 = (int) dim0Arr[1];
double *_14466_v9628 = ((double *) argArr[0]);
double *_14466_v9629 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14466_v9628[(global_id * _14466_v9628Dim0) + _14466_v9628Offset] = -1.7976931348623157E308;
for (int _14466_i = 0; _14466_i < 25088;) {
jint k_14466_i = (global_id * 25088) + _14466_i;
_14466_v9628[(global_id * _14466_v9628Dim0) + _14466_v9628Offset] = fmax(_14466_v9628[(global_id * _14466_v9628Dim0) + _14466_v9628Offset], _14466_v9629[(k_14466_i) + _14466_v9629Offset]);
_14466_i = _14466_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
