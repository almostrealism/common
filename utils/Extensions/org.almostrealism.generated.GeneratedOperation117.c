#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation117_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1520_v1329Offset = (int) offsetArr[0];
jint _1519_v1328Offset = (int) offsetArr[1];
jint _1520_v1329Size = (int) sizeArr[0];
jint _1519_v1328Size = (int) sizeArr[1];
jint _1520_v1329Dim0 = (int) dim0Arr[0];
jint _1519_v1328Dim0 = (int) dim0Arr[1];
double *_1520_v1329 = ((double *) argArr[0]);
double *_1519_v1328 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1520_v1329[(global_id * _1520_v1329Dim0) + _1520_v1329Offset] = _1519_v1328[((global_id * 3) % 3) + _1519_v1328Offset + 3];
_1520_v1329[(global_id * _1520_v1329Dim0) + _1520_v1329Offset + 1] = _1519_v1328[_1519_v1328Offset + 4];
_1520_v1329[(global_id * _1520_v1329Dim0) + _1520_v1329Offset + 2] = _1519_v1328[_1519_v1328Offset + 5];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
