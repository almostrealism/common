#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation230_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2687_v2533Offset = (int) offsetArr[0];
jint _2687_v2534Offset = (int) offsetArr[1];
jint _2687_v2536Offset = (int) offsetArr[2];
jint _2687_v2533Size = (int) sizeArr[0];
jint _2687_v2534Size = (int) sizeArr[1];
jint _2687_v2536Size = (int) sizeArr[2];
jint _2687_v2533Dim0 = (int) dim0Arr[0];
jint _2687_v2534Dim0 = (int) dim0Arr[1];
jint _2687_v2536Dim0 = (int) dim0Arr[2];
double *_2687_v2533 = ((double *) argArr[0]);
double *_2687_v2534 = ((double *) argArr[1]);
double *_2687_v2536 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2687_v2533[global_id + _2687_v2533Offset] = (_2687_v2534[((global_id / 4) * 2) + _2687_v2534Offset + 1] * _2687_v2536[(global_id % 4) + _2687_v2536Offset + 4]) + (_2687_v2534[((global_id / 4) * 2) + _2687_v2534Offset] * _2687_v2536[(global_id % 4) + _2687_v2536Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
