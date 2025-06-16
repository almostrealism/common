#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation265_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3139_v2848Offset = (int) offsetArr[0];
jint _3139_v2849Offset = (int) offsetArr[1];
jint _3139_v2848Size = (int) sizeArr[0];
jint _3139_v2849Size = (int) sizeArr[1];
jint _3139_v2848Dim0 = (int) dim0Arr[0];
jint _3139_v2849Dim0 = (int) dim0Arr[1];
double *_3139_v2848 = ((double *) argArr[0]);
double *_3139_v2849 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3139_v2848[(global_id * _3139_v2848Dim0) + _3139_v2848Offset] = 0.0;
for (int _3139_i = 0; _3139_i < 30;) {
jint k_3139_i = (global_id * 30) + _3139_i;
_3139_v2848[(global_id * _3139_v2848Dim0) + _3139_v2848Offset] = _3139_v2849[(k_3139_i) + _3139_v2849Offset] + _3139_v2848[(global_id * _3139_v2848Dim0) + _3139_v2848Offset];
_3139_i = _3139_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
