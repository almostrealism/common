#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation121_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1731_v1677Offset = (int) offsetArr[0];
jint _1723_v1666Offset = (int) offsetArr[1];
jint _1731_v1677Size = (int) sizeArr[0];
jint _1723_v1666Size = (int) sizeArr[1];
jint _1731_v1677Dim0 = (int) dim0Arr[0];
jint _1723_v1666Dim0 = (int) dim0Arr[1];
double *_1731_v1677 = ((double *) argArr[0]);
double *_1723_v1666 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1731_v1677[_1731_v1677Offset] = _1723_v1666[_1723_v1666Offset] * 0.0;
_1731_v1677[_1731_v1677Offset + 1] = _1723_v1666[_1723_v1666Offset] * 0.0;
_1731_v1677[_1731_v1677Offset + 2] = - _1723_v1666[_1723_v1666Offset];
_1731_v1677[_1731_v1677Offset + 3] = 0.0;
_1731_v1677[_1731_v1677Offset + 4] = 0.0;
_1731_v1677[_1731_v1677Offset + 5] = 1.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
