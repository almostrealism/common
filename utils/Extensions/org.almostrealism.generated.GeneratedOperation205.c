#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation205_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2567_v2357Offset = (int) offsetArr[0];
jint _2567_v2358Offset = (int) offsetArr[1];
jint _2567_v2357Size = (int) sizeArr[0];
jint _2567_v2358Size = (int) sizeArr[1];
jint _2567_v2357Dim0 = (int) dim0Arr[0];
jint _2567_v2358Dim0 = (int) dim0Arr[1];
double *_2567_v2357 = ((double *) argArr[0]);
double *_2567_v2358 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2567_v2357[(global_id * _2567_v2357Dim0) + _2567_v2357Offset] = 0.0;
for (int _2567_i = 0; _2567_i < 2;) {
jint k_2567_i = (global_id * 2) + _2567_i;
_2567_v2357[(global_id * _2567_v2357Dim0) + _2567_v2357Offset] = _2567_v2358[((k_2567_i) % 16) + _2567_v2358Offset] + _2567_v2357[(global_id * _2567_v2357Dim0) + _2567_v2357Offset];
_2567_i = _2567_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
