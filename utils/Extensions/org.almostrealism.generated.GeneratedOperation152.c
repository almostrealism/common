#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation152_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2048_v2001Offset = (int) offsetArr[0];
jint _2050_v2003Offset = (int) offsetArr[1];
jint _2050_v2004Offset = (int) offsetArr[2];
jint _2048_v2001Size = (int) sizeArr[0];
jint _2050_v2003Size = (int) sizeArr[1];
jint _2050_v2004Size = (int) sizeArr[2];
jint _2048_v2001Dim0 = (int) dim0Arr[0];
jint _2050_v2003Dim0 = (int) dim0Arr[1];
jint _2050_v2004Dim0 = (int) dim0Arr[2];
double *_2048_v2001 = ((double *) argArr[0]);
double *_2050_v2003 = ((double *) argArr[1]);
double *_2050_v2004 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2048_v2001[_2048_v2001Offset] = 0.7;
_2048_v2001[_2048_v2001Offset + 1] = 1.0;
_2050_v2003[(global_id * _2050_v2003Dim0) + _2050_v2003Offset] = _2050_v2004[(((int) (floor(_2048_v2001[(global_id * _2048_v2001Dim0) + _2048_v2001Offset] * 2.0) * 2.0)) + (global_id * _2050_v2004Dim0)) + _2050_v2004Offset];
_2050_v2003[(global_id * _2050_v2003Dim0) + _2050_v2003Offset + 1] = _2050_v2004[(((int) ((floor(_2048_v2001[(global_id * _2048_v2001Dim0) + _2048_v2001Offset] * 2.0) * 2.0) + 1.0)) + (global_id * _2050_v2004Dim0)) + _2050_v2004Offset];

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
