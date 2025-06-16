#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation496_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6586_v4917Offset = (int) offsetArr[0];
jint _6581_v4906Offset = (int) offsetArr[1];
jint _6586_v4917Size = (int) sizeArr[0];
jint _6581_v4906Size = (int) sizeArr[1];
jint _6586_v4917Dim0 = (int) dim0Arr[0];
jint _6581_v4906Dim0 = (int) dim0Arr[1];
double *_6586_v4917 = ((double *) argArr[0]);
double *_6581_v4906 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6586_v4917[global_id + _6586_v4917Offset] = ((- ((_6581_v4906[_6581_v4906Offset + 69] + _6581_v4906[_6581_v4906Offset + 70] + _6581_v4906[_6581_v4906Offset + 74] + _6581_v4906[_6581_v4906Offset + 71] + _6581_v4906[_6581_v4906Offset + 77] + _6581_v4906[_6581_v4906Offset + 73] + _6581_v4906[_6581_v4906Offset + 72] + _6581_v4906[_6581_v4906Offset + 79] + _6581_v4906[_6581_v4906Offset + 64] + _6581_v4906[_6581_v4906Offset + 65] + _6581_v4906[_6581_v4906Offset + 76] + _6581_v4906[_6581_v4906Offset + 66] + _6581_v4906[_6581_v4906Offset + 67] + _6581_v4906[_6581_v4906Offset + 68] + _6581_v4906[_6581_v4906Offset + 78] + _6581_v4906[_6581_v4906Offset + 75]) / 16.0)) + _6581_v4906[global_id + _6581_v4906Offset + 64]) * ((- ((_6581_v4906[_6581_v4906Offset + 69] + _6581_v4906[_6581_v4906Offset + 70] + _6581_v4906[_6581_v4906Offset + 74] + _6581_v4906[_6581_v4906Offset + 71] + _6581_v4906[_6581_v4906Offset + 77] + _6581_v4906[_6581_v4906Offset + 73] + _6581_v4906[_6581_v4906Offset + 72] + _6581_v4906[_6581_v4906Offset + 79] + _6581_v4906[_6581_v4906Offset + 64] + _6581_v4906[_6581_v4906Offset + 65] + _6581_v4906[_6581_v4906Offset + 76] + _6581_v4906[_6581_v4906Offset + 66] + _6581_v4906[_6581_v4906Offset + 67] + _6581_v4906[_6581_v4906Offset + 68] + _6581_v4906[_6581_v4906Offset + 78] + _6581_v4906[_6581_v4906Offset + 75]) / 16.0)) + _6581_v4906[global_id + _6581_v4906Offset + 64]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
