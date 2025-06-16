#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation437_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5630_v4346Offset = (int) offsetArr[0];
jint _5626_v4338Offset = (int) offsetArr[1];
jint _5629_v4343Offset = (int) offsetArr[2];
jint _5630_v4346Size = (int) sizeArr[0];
jint _5626_v4338Size = (int) sizeArr[1];
jint _5629_v4343Size = (int) sizeArr[2];
jint _5630_v4346Dim0 = (int) dim0Arr[0];
jint _5626_v4338Dim0 = (int) dim0Arr[1];
jint _5629_v4343Dim0 = (int) dim0Arr[2];
double *_5630_v4346 = ((double *) argArr[0]);
double *_5626_v4338 = ((double *) argArr[1]);
double *_5629_v4343 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5630_v4346[global_id + _5630_v4346Offset] = ((- (_5626_v4338[_5626_v4338Offset] / 30.0)) + _5629_v4343[global_id + _5629_v4343Offset + 90]) * ((- (_5626_v4338[_5626_v4338Offset] / 30.0)) + _5629_v4343[global_id + _5629_v4343Offset + 90]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
