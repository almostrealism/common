#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation469_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6211_v4402Offset = (int) offsetArr[0];
jint _6217_v4405Offset = (int) offsetArr[1];
jint _6219_v4408Offset = (int) offsetArr[2];
jint _6211_v4402Size = (int) sizeArr[0];
jint _6217_v4405Size = (int) sizeArr[1];
jint _6219_v4408Size = (int) sizeArr[2];
jint _6211_v4402Dim0 = (int) dim0Arr[0];
jint _6217_v4405Dim0 = (int) dim0Arr[1];
jint _6219_v4408Dim0 = (int) dim0Arr[2];
double *_6211_v4402 = ((double *) argArr[0]);
double *_6217_v4405 = ((double *) argArr[1]);
double *_6219_v4408 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6219_v4408[global_id + _6219_v4408Offset] = (- (_6211_v4402[_6211_v4402Offset] * _6217_v4405[global_id + _6217_v4405Offset])) + _6219_v4408[global_id + _6219_v4408Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
