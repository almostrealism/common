#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation641_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10033_v6582Offset = (int) offsetArr[0];
jint _10013_v6551Offset = (int) offsetArr[1];
jint _10024_v6563Offset = (int) offsetArr[2];
jint _10032_v6580Offset = (int) offsetArr[3];
jint _10033_v6582Size = (int) sizeArr[0];
jint _10013_v6551Size = (int) sizeArr[1];
jint _10024_v6563Size = (int) sizeArr[2];
jint _10032_v6580Size = (int) sizeArr[3];
jint _10033_v6582Dim0 = (int) dim0Arr[0];
jint _10013_v6551Dim0 = (int) dim0Arr[1];
jint _10024_v6563Dim0 = (int) dim0Arr[2];
jint _10032_v6580Dim0 = (int) dim0Arr[3];
double *_10033_v6582 = ((double *) argArr[0]);
double *_10013_v6551 = ((double *) argArr[1]);
double *_10024_v6563 = ((double *) argArr[2]);
double *_10032_v6580 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10033_v6582[global_id + _10033_v6582Offset] = (((- (global_id % 16)) + (global_id / 16)) == 0) ? ((((- ((_10013_v6551[((global_id / 64) * 4) + _10013_v6551Offset + 1] + _10013_v6551[((global_id / 64) * 4) + _10013_v6551Offset + 2] + _10013_v6551[((global_id / 64) * 4) + _10013_v6551Offset + 3] + _10013_v6551[((global_id / 64) * 4) + _10013_v6551Offset]) / 4.0)) + _10013_v6551[(global_id / 16) + _10013_v6551Offset]) / pow(((_10024_v6563[((global_id / 64) * 4) + _10024_v6563Offset + 1] + _10024_v6563[((global_id / 64) * 4) + _10024_v6563Offset + 2] + _10024_v6563[((global_id / 64) * 4) + _10024_v6563Offset + 3] + _10024_v6563[((global_id / 64) * 4) + _10024_v6563Offset]) / 4.0) + 1.0E-5, 0.5)) * _10032_v6580[(global_id / 16) + _10032_v6580Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
