#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation193_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2509_v2296Offset = (int) offsetArr[0];
jint _2504_v2284Offset = (int) offsetArr[1];
jint _2509_v2296Size = (int) sizeArr[0];
jint _2504_v2284Size = (int) sizeArr[1];
jint _2509_v2296Dim0 = (int) dim0Arr[0];
jint _2504_v2284Dim0 = (int) dim0Arr[1];
double *_2509_v2296 = ((double *) argArr[0]);
double *_2504_v2284 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2509_v2296[(global_id * _2509_v2296Dim0) + _2509_v2296Offset] = _2504_v2284[global_id + _2504_v2284Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
