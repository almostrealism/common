#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation347_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4488_v3650Offset = (int) offsetArr[0];
jint _4488_v3651Offset = (int) offsetArr[1];
jint _4488_v3650Size = (int) sizeArr[0];
jint _4488_v3651Size = (int) sizeArr[1];
jint _4488_v3650Dim0 = (int) dim0Arr[0];
jint _4488_v3651Dim0 = (int) dim0Arr[1];
double *_4488_v3650 = ((double *) argArr[0]);
double *_4488_v3651 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4488_v3650[(global_id * _4488_v3650Dim0) + _4488_v3650Offset] = 0.0;
for (int _4488_i = 0; _4488_i < 5488;) {
jint k_4488_i = (global_id * 5488) + _4488_i;
_4488_v3650[(global_id * _4488_v3650Dim0) + _4488_v3650Offset] = _4488_v3651[(k_4488_i) + _4488_v3651Offset] + _4488_v3650[(global_id * _4488_v3650Dim0) + _4488_v3650Offset];
_4488_i = _4488_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
