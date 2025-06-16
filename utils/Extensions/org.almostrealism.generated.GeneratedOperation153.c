#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation153_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2059_v2012Offset = (int) offsetArr[0];
jint _2053_v2008Offset = (int) offsetArr[1];
jint _2054_v2009Offset = (int) offsetArr[2];
jint _2056_v2006Offset = (int) offsetArr[3];
jint _2059_v2012Size = (int) sizeArr[0];
jint _2053_v2008Size = (int) sizeArr[1];
jint _2054_v2009Size = (int) sizeArr[2];
jint _2056_v2006Size = (int) sizeArr[3];
jint _2059_v2012Dim0 = (int) dim0Arr[0];
jint _2053_v2008Dim0 = (int) dim0Arr[1];
jint _2054_v2009Dim0 = (int) dim0Arr[2];
jint _2056_v2006Dim0 = (int) dim0Arr[3];
double *_2059_v2012 = ((double *) argArr[0]);
double *_2053_v2008 = ((double *) argArr[1]);
double *_2054_v2009 = ((double *) argArr[2]);
double *_2056_v2006 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2059_v2012[(global_id * _2059_v2012Dim0) + _2059_v2012Offset] = (- (_2053_v2008[(global_id * _2053_v2008Dim0) + _2053_v2008Offset] * _2054_v2009[(global_id * _2054_v2009Dim0) + _2054_v2009Offset])) + _2056_v2006[(global_id * _2056_v2006Dim0) + _2056_v2006Offset];
_2059_v2012[(global_id * _2059_v2012Dim0) + _2059_v2012Offset + 1] = 1.0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
