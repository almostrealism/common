#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation212_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2624_v2391Offset = (int) offsetArr[0];
jint _2624_v2392Offset = (int) offsetArr[1];
jint _2624_v2391Size = (int) sizeArr[0];
jint _2624_v2392Size = (int) sizeArr[1];
jint _2624_v2391Dim0 = (int) dim0Arr[0];
jint _2624_v2392Dim0 = (int) dim0Arr[1];
double *_2624_v2391 = ((double *) argArr[0]);
double *_2624_v2392 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2624_v2391[(global_id * _2624_v2391Dim0) + _2624_v2391Offset] = 0.0;
for (int _2624_i = 0; _2624_i < 8;) {
jint k_2624_i = (global_id * 8) + _2624_i;
_2624_v2391[(global_id * _2624_v2391Dim0) + _2624_v2391Offset] = _2624_v2392[(k_2624_i) + _2624_v2392Offset] + _2624_v2391[(global_id * _2624_v2391Dim0) + _2624_v2391Offset];
_2624_i = _2624_i + 1;
}
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
