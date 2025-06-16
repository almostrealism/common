#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation394_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5301_v3911Offset = (int) offsetArr[0];
jint _5234_v3900Offset = (int) offsetArr[1];
jint _5298_v3903Offset = (int) offsetArr[2];
jint _5298_v3904Offset = (int) offsetArr[3];
jint _5300_v3909Offset = (int) offsetArr[4];
jint _5301_v3911Size = (int) sizeArr[0];
jint _5234_v3900Size = (int) sizeArr[1];
jint _5298_v3903Size = (int) sizeArr[2];
jint _5298_v3904Size = (int) sizeArr[3];
jint _5300_v3909Size = (int) sizeArr[4];
jint _5301_v3911Dim0 = (int) dim0Arr[0];
jint _5234_v3900Dim0 = (int) dim0Arr[1];
jint _5298_v3903Dim0 = (int) dim0Arr[2];
jint _5298_v3904Dim0 = (int) dim0Arr[3];
jint _5300_v3909Dim0 = (int) dim0Arr[4];
double *_5301_v3911 = ((double *) argArr[0]);
double *_5234_v3900 = ((double *) argArr[1]);
double *_5298_v3903 = ((double *) argArr[2]);
double *_5298_v3904 = ((double *) argArr[3]);
double *_5300_v3909 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5301_v3911[global_id + _5301_v3911Offset] = (_5234_v3900[(global_id / 120) + _5234_v3900Offset] * (_5298_v3903[global_id + _5298_v3903Offset] + _5298_v3904[global_id + _5298_v3904Offset])) * _5300_v3909[(global_id / 120) + _5300_v3909Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
