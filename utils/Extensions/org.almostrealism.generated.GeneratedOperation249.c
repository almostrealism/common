#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation249_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2813_v2659Offset = (int) offsetArr[0];
jint _2813_v2661Offset = (int) offsetArr[1];
jint _2816_v2666Offset = (int) offsetArr[2];
jint _2823_v2679Offset = (int) offsetArr[3];
jint _2813_v2659Size = (int) sizeArr[0];
jint _2813_v2661Size = (int) sizeArr[1];
jint _2816_v2666Size = (int) sizeArr[2];
jint _2823_v2679Size = (int) sizeArr[3];
jint _2813_v2659Dim0 = (int) dim0Arr[0];
jint _2813_v2661Dim0 = (int) dim0Arr[1];
jint _2816_v2666Dim0 = (int) dim0Arr[2];
jint _2823_v2679Dim0 = (int) dim0Arr[3];
double *_2813_v2659 = ((double *) argArr[0]);
double *_2813_v2661 = ((double *) argArr[1]);
double *_2816_v2666 = ((double *) argArr[2]);
double *_2823_v2679 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2823_v2679[global_id + _2823_v2679Offset] = (1.0 / pow((_2816_v2666[_2816_v2666Offset] / 768.0) + 1.0E-5, 0.5)) * (_2813_v2659[global_id + _2813_v2659Offset] * _2813_v2661[global_id + _2813_v2661Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
