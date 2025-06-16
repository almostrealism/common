#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation425_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5509_v4263Offset = (int) offsetArr[0];
jint _5504_v4260Offset = (int) offsetArr[1];
jint _5509_v4263Size = (int) sizeArr[0];
jint _5504_v4260Size = (int) sizeArr[1];
jint _5509_v4263Dim0 = (int) dim0Arr[0];
jint _5504_v4260Dim0 = (int) dim0Arr[1];
double *_5509_v4263 = ((double *) argArr[0]);
double *_5504_v4260 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5509_v4263[global_id + _5509_v4263Offset] = (_5504_v4260[global_id + _5504_v4260Offset + 30] + -0.05325623509354686) / 0.030445137702890656;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
