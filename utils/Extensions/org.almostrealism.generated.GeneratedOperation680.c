#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation680_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10826_v7052Offset = (int) offsetArr[0];
jint _10791_v7005Offset = (int) offsetArr[1];
jint _10811_v7021Offset = (int) offsetArr[2];
jint _10820_v7039Offset = (int) offsetArr[3];
jint _10826_v7052Size = (int) sizeArr[0];
jint _10791_v7005Size = (int) sizeArr[1];
jint _10811_v7021Size = (int) sizeArr[2];
jint _10820_v7039Size = (int) sizeArr[3];
jint _10826_v7052Dim0 = (int) dim0Arr[0];
jint _10791_v7005Dim0 = (int) dim0Arr[1];
jint _10811_v7021Dim0 = (int) dim0Arr[2];
jint _10820_v7039Dim0 = (int) dim0Arr[3];
double *_10826_v7052 = ((double *) argArr[0]);
double *_10791_v7005 = ((double *) argArr[1]);
double *_10811_v7021 = ((double *) argArr[2]);
double *_10820_v7039 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10826_v7052[global_id + _10826_v7052Offset] = ((((_10811_v7021[((global_id % 2) * 2) + _10811_v7021Offset + 1] + _10811_v7021[((global_id % 2) * 2) + _10811_v7021Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_10791_v7005[_10791_v7005Offset] + _10791_v7005[_10791_v7005Offset + 1]) / 2.0)) + _10791_v7005[(global_id / 2) + _10791_v7005Offset])) + ((((_10820_v7039[((global_id % 2) * 2) + _10820_v7039Offset + 1] + _10820_v7039[((global_id % 2) * 2) + _10820_v7039Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * ((- ((_10791_v7005[_10791_v7005Offset] + _10791_v7005[_10791_v7005Offset + 1]) / 2.0)) + _10791_v7005[(global_id / 2) + _10791_v7005Offset]));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
