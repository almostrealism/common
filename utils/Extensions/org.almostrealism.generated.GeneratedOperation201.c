#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation201_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2552_v2355Offset = (int) offsetArr[0];
jint _2545_v2343Offset = (int) offsetArr[1];
jint _2552_v2355Size = (int) sizeArr[0];
jint _2545_v2343Size = (int) sizeArr[1];
jint _2552_v2355Dim0 = (int) dim0Arr[0];
jint _2545_v2343Dim0 = (int) dim0Arr[1];
double *_2552_v2355 = ((double *) argArr[0]);
double *_2545_v2343 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2552_v2355[(global_id * _2552_v2355Dim0) + _2552_v2355Offset] = 0.0;
for (int _2552_i = 0; _2552_i < 2;) {
jint k_2552_i = (global_id * 2) + _2552_i;
_2552_v2355[(global_id * _2552_v2355Dim0) + _2552_v2355Offset] = (((((((((((((k_2552_i) % 16) / 8) * 2) + ((((k_2552_i) % 8) / 2) * 4) + (((k_2552_i) / 16) * 16) + ((k_2552_i) % 2)) / 16) * 16) / 4) + ((((k_2552_i) % 16) / 8) * 2) + ((k_2552_i) % 2)) % 4) + (- (((k_2552_i) % 8) / 2))) == 0) ? _2545_v2343[((((((((((((k_2552_i) % 16) / 8) * 2) + ((((k_2552_i) % 8) / 2) * 4) + (((k_2552_i) / 16) * 16) + ((k_2552_i) % 2)) / 16) * 16) / 4) + ((((k_2552_i) % 16) / 8) * 2) + ((k_2552_i) % 2)) / 4) * _2545_v2343Dim0) + ((((((((((k_2552_i) % 16) / 8) * 2) + ((((k_2552_i) % 8) / 2) * 4) + (((k_2552_i) / 16) * 16) + ((k_2552_i) % 2)) / 16) * 16) / 4) + ((k_2552_i) % 2)) % 2)) + _2545_v2343Offset] : 0) + _2552_v2355[(global_id * _2552_v2355Dim0) + _2552_v2355Offset];
_2552_i = _2552_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
