#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation663_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10278_v6771Offset = (int) offsetArr[0];
jint _10259_v6756Offset = (int) offsetArr[1];
jint _10278_v6771Size = (int) sizeArr[0];
jint _10259_v6756Size = (int) sizeArr[1];
jint _10278_v6771Dim0 = (int) dim0Arr[0];
jint _10259_v6756Dim0 = (int) dim0Arr[1];
double *_10278_v6771 = ((double *) argArr[0]);
double *_10259_v6756 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10278_v6771[_10278_v6771Offset] = ((((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 8]) * ((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 8])) + (((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 9]) * ((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 9])) + (((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 10]) * ((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 10])) + (((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 11]) * ((- ((_10259_v6756[_10259_v6756Offset + 8] + _10259_v6756[_10259_v6756Offset + 9] + _10259_v6756[_10259_v6756Offset + 10] + _10259_v6756[_10259_v6756Offset + 11]) / 4.0)) + _10259_v6756[_10259_v6756Offset + 11]))) / 4.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
