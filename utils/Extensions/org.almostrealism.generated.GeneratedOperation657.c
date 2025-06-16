#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation657_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10198_v6716Offset = (int) offsetArr[0];
jint _10179_v6701Offset = (int) offsetArr[1];
jint _10198_v6716Size = (int) sizeArr[0];
jint _10179_v6701Size = (int) sizeArr[1];
jint _10198_v6716Dim0 = (int) dim0Arr[0];
jint _10179_v6701Dim0 = (int) dim0Arr[1];
double *_10198_v6716 = ((double *) argArr[0]);
double *_10179_v6701 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10198_v6716[_10198_v6716Offset] = ((((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 4]) * ((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 4])) + (((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 5]) * ((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 5])) + (((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 6]) * ((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 6])) + (((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 7]) * ((- ((_10179_v6701[_10179_v6701Offset + 4] + _10179_v6701[_10179_v6701Offset + 5] + _10179_v6701[_10179_v6701Offset + 6] + _10179_v6701[_10179_v6701Offset + 7]) / 4.0)) + _10179_v6701[_10179_v6701Offset + 7]))) / 4.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
