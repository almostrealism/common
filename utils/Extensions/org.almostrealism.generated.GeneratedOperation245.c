#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation245_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2753_v2636Offset = (int) offsetArr[0];
jint _2753_v2638Offset = (int) offsetArr[1];
jint _2754_v2640Offset = (int) offsetArr[2];
jint _2753_v2636Size = (int) sizeArr[0];
jint _2753_v2638Size = (int) sizeArr[1];
jint _2754_v2640Size = (int) sizeArr[2];
jint _2753_v2636Dim0 = (int) dim0Arr[0];
jint _2753_v2638Dim0 = (int) dim0Arr[1];
jint _2754_v2640Dim0 = (int) dim0Arr[2];
double *_2753_v2636 = ((double *) argArr[0]);
double *_2753_v2638 = ((double *) argArr[1]);
double *_2754_v2640 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2754_v2640[global_id + _2754_v2640Offset] = pow(_2753_v2636[global_id + _2753_v2636Offset], _2753_v2638[global_id + _2753_v2638Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
