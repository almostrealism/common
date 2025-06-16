#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation418_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5437_v4209Offset = (int) offsetArr[0];
jint _5437_v4210Offset = (int) offsetArr[1];
jint _5437_v4212Offset = (int) offsetArr[2];
jint _5437_v4209Size = (int) sizeArr[0];
jint _5437_v4210Size = (int) sizeArr[1];
jint _5437_v4212Size = (int) sizeArr[2];
jint _5437_v4209Dim0 = (int) dim0Arr[0];
jint _5437_v4210Dim0 = (int) dim0Arr[1];
jint _5437_v4212Dim0 = (int) dim0Arr[2];
double *_5437_v4209 = ((double *) argArr[0]);
double *_5437_v4210 = ((double *) argArr[1]);
double *_5437_v4212 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5437_v4209[global_id + _5437_v4209Offset] = _5437_v4210[global_id + _5437_v4210Offset] * _5437_v4212[global_id + _5437_v4212Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
