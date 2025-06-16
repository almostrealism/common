#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation633_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9992_v6396Offset = (int) offsetArr[0];
jint _9941_v6381Offset = (int) offsetArr[1];
jint _9991_v6395Offset = (int) offsetArr[2];
jint _9992_v6396Size = (int) sizeArr[0];
jint _9941_v6381Size = (int) sizeArr[1];
jint _9991_v6395Size = (int) sizeArr[2];
jint _9992_v6396Dim0 = (int) dim0Arr[0];
jint _9941_v6381Dim0 = (int) dim0Arr[1];
jint _9991_v6395Dim0 = (int) dim0Arr[2];
double *_9992_v6396 = ((double *) argArr[0]);
double *_9941_v6381 = ((double *) argArr[1]);
double *_9991_v6395 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9992_v6396[global_id + _9992_v6396Offset] = ((- ((_9941_v6381[((global_id / 64) * 4) + _9941_v6381Offset + 1] + _9941_v6381[((global_id / 64) * 4) + _9941_v6381Offset + 2] + _9941_v6381[((global_id / 64) * 4) + _9941_v6381Offset + 3] + _9941_v6381[((global_id / 64) * 4) + _9941_v6381Offset]) / 4.0)) + _9941_v6381[(global_id / 16) + _9941_v6381Offset]) * _9991_v6395[(((global_id / 64) * 16) + (global_id % 16)) + _9991_v6395Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
