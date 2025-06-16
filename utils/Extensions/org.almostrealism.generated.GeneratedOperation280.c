#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation280_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3473_v2914Offset = (int) offsetArr[0];
jint _3422_v2899Offset = (int) offsetArr[1];
jint _3472_v2913Offset = (int) offsetArr[2];
jint _3473_v2914Size = (int) sizeArr[0];
jint _3422_v2899Size = (int) sizeArr[1];
jint _3472_v2913Size = (int) sizeArr[2];
jint _3473_v2914Dim0 = (int) dim0Arr[0];
jint _3422_v2899Dim0 = (int) dim0Arr[1];
jint _3472_v2913Dim0 = (int) dim0Arr[2];
double *_3473_v2914 = ((double *) argArr[0]);
double *_3422_v2899 = ((double *) argArr[1]);
double *_3472_v2913 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3473_v2914[global_id + _3473_v2914Offset] = ((- ((_3422_v2899[_3422_v2899Offset] + _3422_v2899[_3422_v2899Offset + 1]) / 2.0)) + _3422_v2899[(global_id / 2) + _3422_v2899Offset]) * _3472_v2913[(global_id % 2) + _3472_v2913Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
