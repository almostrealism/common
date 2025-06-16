#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation600_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9180_v6124Offset = (int) offsetArr[0];
jint _9175_v6116Offset = (int) offsetArr[1];
jint _9176_v6117Offset = (int) offsetArr[2];
jint _9179_v6123Offset = (int) offsetArr[3];
jint _9180_v6124Size = (int) sizeArr[0];
jint _9175_v6116Size = (int) sizeArr[1];
jint _9176_v6117Size = (int) sizeArr[2];
jint _9179_v6123Size = (int) sizeArr[3];
jint _9180_v6124Dim0 = (int) dim0Arr[0];
jint _9175_v6116Dim0 = (int) dim0Arr[1];
jint _9176_v6117Dim0 = (int) dim0Arr[2];
jint _9179_v6123Dim0 = (int) dim0Arr[3];
double *_9180_v6124 = ((double *) argArr[0]);
double *_9175_v6116 = ((double *) argArr[1]);
double *_9176_v6117 = ((double *) argArr[2]);
double *_9179_v6123 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9180_v6124[(global_id * _9180_v6124Dim0) + _9180_v6124Offset] = ((- _9176_v6117[(((global_id / _9176_v6117Size) * _9176_v6117Dim0) + (global_id % _9176_v6117Size)) + _9176_v6117Offset]) + _9175_v6116[(((global_id / 2) * _9175_v6116Dim0) + (global_id % 2)) + _9175_v6116Offset]) / _9179_v6123[(((global_id / _9179_v6123Size) * _9179_v6123Dim0) + (global_id % _9179_v6123Size)) + _9179_v6123Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
