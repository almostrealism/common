#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation187_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2359_v2220Offset = (int) offsetArr[0];
jint _2413_v2242Offset = (int) offsetArr[1];
jint _2359_v2220Size = (int) sizeArr[0];
jint _2413_v2242Size = (int) sizeArr[1];
jint _2359_v2220Dim0 = (int) dim0Arr[0];
jint _2413_v2242Dim0 = (int) dim0Arr[1];
double *_2359_v2220 = ((double *) argArr[0]);
double *_2413_v2242 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2413_v2242[global_id + _2413_v2242Offset] = ((((((global_id / 3) * 3) == 3) ? (_2359_v2220[(global_id % 3) + _2359_v2220Offset] * 0.005) : 0) + ((((global_id / 3) * 3) == 6) ? (_2359_v2220[(global_id % 3) + _2359_v2220Offset] * 5.0E-4) : 0) + ((((global_id / 3) * 3) == 0) ? (_2359_v2220[(global_id % 3) + _2359_v2220Offset] * 0.05) : 0)) * -2.0) + _2413_v2242[global_id + _2413_v2242Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
