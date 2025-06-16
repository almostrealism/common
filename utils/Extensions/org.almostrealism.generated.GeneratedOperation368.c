#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation368_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4753_v3669Offset = (int) offsetArr[0];
jint _4751_v3664Offset = (int) offsetArr[1];
jint _4751_v3665Offset = (int) offsetArr[2];
jint _4752_v3667Offset = (int) offsetArr[3];
jint _4753_v3669Size = (int) sizeArr[0];
jint _4751_v3664Size = (int) sizeArr[1];
jint _4751_v3665Size = (int) sizeArr[2];
jint _4752_v3667Size = (int) sizeArr[3];
jint _4753_v3669Dim0 = (int) dim0Arr[0];
jint _4751_v3664Dim0 = (int) dim0Arr[1];
jint _4751_v3665Dim0 = (int) dim0Arr[2];
jint _4752_v3667Dim0 = (int) dim0Arr[3];
double *_4753_v3669 = ((double *) argArr[0]);
double *_4751_v3664 = ((double *) argArr[1]);
double *_4751_v3665 = ((double *) argArr[2]);
double *_4752_v3667 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4753_v3669[global_id + _4753_v3669Offset] = (_4751_v3664[global_id + _4751_v3664Offset] + _4751_v3665[global_id + _4751_v3665Offset]) * _4752_v3667[(global_id / 2) + _4752_v3667Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
