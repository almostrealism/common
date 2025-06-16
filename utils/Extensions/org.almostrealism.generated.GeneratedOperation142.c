#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation142_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1932_v1887Offset = (int) offsetArr[0];
jint _1926_v1883Offset = (int) offsetArr[1];
jint _1929_v1885Offset = (int) offsetArr[2];
jint _1932_v1887Size = (int) sizeArr[0];
jint _1926_v1883Size = (int) sizeArr[1];
jint _1929_v1885Size = (int) sizeArr[2];
jint _1932_v1887Dim0 = (int) dim0Arr[0];
jint _1926_v1883Dim0 = (int) dim0Arr[1];
jint _1929_v1885Dim0 = (int) dim0Arr[2];
double *_1932_v1887 = ((double *) argArr[0]);
double *_1926_v1883 = ((double *) argArr[1]);
double *_1929_v1885 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1932_v1887[(global_id * _1932_v1887Dim0) + _1932_v1887Offset] = (_1926_v1883[(global_id * _1926_v1883Dim0) + _1926_v1883Offset] * _1926_v1883[(global_id * _1926_v1883Dim0) + _1926_v1883Offset]) + (_1929_v1885[(global_id * _1929_v1885Dim0) + _1929_v1885Offset] * _1929_v1885[(global_id * _1929_v1885Dim0) + _1929_v1885Offset]);
_1932_v1887[(global_id * _1932_v1887Dim0) + _1932_v1887Offset + 1] = 1.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
