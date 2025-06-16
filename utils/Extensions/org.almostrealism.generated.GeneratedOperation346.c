#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation346_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4483_v3632Offset = (int) offsetArr[0];
jint _4483_v3633Offset = (int) offsetArr[1];
jint _4483_v3632Size = (int) sizeArr[0];
jint _4483_v3633Size = (int) sizeArr[1];
jint _4483_v3632Dim0 = (int) dim0Arr[0];
jint _4483_v3633Dim0 = (int) dim0Arr[1];
double *_4483_v3632 = ((double *) argArr[0]);
double *_4483_v3633 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4483_v3632[(global_id * _4483_v3632Dim0) + _4483_v3632Offset] = 0.0;
for (int _4483_i = 0; _4483_i < 5488;) {
jint k_4483_i = (global_id * 5488) + _4483_i;
_4483_v3632[(global_id * _4483_v3632Dim0) + _4483_v3632Offset] = _4483_v3633[(k_4483_i) + _4483_v3633Offset] + _4483_v3632[(global_id * _4483_v3632Dim0) + _4483_v3632Offset];
_4483_i = _4483_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
