#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation532_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _7219_v5281Offset = (int) offsetArr[0];
jint _7219_v5282Offset = (int) offsetArr[1];
jint _7219_v5281Size = (int) sizeArr[0];
jint _7219_v5282Size = (int) sizeArr[1];
jint _7219_v5281Dim0 = (int) dim0Arr[0];
jint _7219_v5282Dim0 = (int) dim0Arr[1];
double *_7219_v5281 = ((double *) argArr[0]);
double *_7219_v5282 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_7219_v5281[global_id + _7219_v5281Offset] = _7219_v5282[(((global_id % 2) * 2) + (global_id / 2)) + _7219_v5282Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
