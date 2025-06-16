#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation194_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2512_v2305Offset = (int) offsetArr[0];
jint _2510_v2299Offset = (int) offsetArr[1];
jint _2511_v2302Offset = (int) offsetArr[2];
jint _2512_v2305Size = (int) sizeArr[0];
jint _2510_v2299Size = (int) sizeArr[1];
jint _2511_v2302Size = (int) sizeArr[2];
jint _2512_v2305Dim0 = (int) dim0Arr[0];
jint _2510_v2299Dim0 = (int) dim0Arr[1];
jint _2511_v2302Dim0 = (int) dim0Arr[2];
double *_2512_v2305 = ((double *) argArr[0]);
double *_2510_v2299 = ((double *) argArr[1]);
double *_2511_v2302 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2512_v2305[(global_id * _2512_v2305Dim0) + _2512_v2305Offset] = 0.0;
for (int _2512_i = 0; _2512_i < 2;) {
jint k_2512_i = (global_id * 2) + _2512_i;
_2512_v2305[(global_id * _2512_v2305Dim0) + _2512_v2305Offset] = (_2511_v2302[(k_2512_i) + _2511_v2302Offset] * _2510_v2299[_2512_i + _2510_v2299Offset]) + _2512_v2305[(global_id * _2512_v2305Dim0) + _2512_v2305Offset];
_2512_i = _2512_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
