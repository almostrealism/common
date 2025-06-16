#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation817_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _12542_v8214Offset = (int) offsetArr[0];
jint _12538_v8206Offset = (int) offsetArr[1];
jint _12541_v8211Offset = (int) offsetArr[2];
jint _12542_v8214Size = (int) sizeArr[0];
jint _12538_v8206Size = (int) sizeArr[1];
jint _12541_v8211Size = (int) sizeArr[2];
jint _12542_v8214Dim0 = (int) dim0Arr[0];
jint _12538_v8206Dim0 = (int) dim0Arr[1];
jint _12541_v8211Dim0 = (int) dim0Arr[2];
double *_12542_v8214 = ((double *) argArr[0]);
double *_12538_v8206 = ((double *) argArr[1]);
double *_12541_v8211 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_12542_v8214[global_id + _12542_v8214Offset] = ((- (_12538_v8206[(global_id / 30) + _12538_v8206Offset] / 30.0)) + _12541_v8211[global_id + _12541_v8211Offset]) * ((- (_12538_v8206[(global_id / 30) + _12538_v8206Offset] / 30.0)) + _12541_v8211[global_id + _12541_v8211Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
