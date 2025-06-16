#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation405_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5306_v3874Offset = (int) offsetArr[0];
jint _5331_v3877Offset = (int) offsetArr[1];
jint _5333_v3880Offset = (int) offsetArr[2];
jint _5306_v3874Size = (int) sizeArr[0];
jint _5331_v3877Size = (int) sizeArr[1];
jint _5333_v3880Size = (int) sizeArr[2];
jint _5306_v3874Dim0 = (int) dim0Arr[0];
jint _5331_v3877Dim0 = (int) dim0Arr[1];
jint _5333_v3880Dim0 = (int) dim0Arr[2];
double *_5306_v3874 = ((double *) argArr[0]);
double *_5331_v3877 = ((double *) argArr[1]);
double *_5333_v3880 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5333_v3880[global_id + _5333_v3880Offset] = (- (_5306_v3874[_5306_v3874Offset] * _5331_v3877[global_id + _5331_v3877Offset])) + _5333_v3880[global_id + _5333_v3880Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
