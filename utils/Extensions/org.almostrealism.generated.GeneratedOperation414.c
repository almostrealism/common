#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation414_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5350_v4176Offset = (int) offsetArr[0];
jint _5345_v4168Offset = (int) offsetArr[1];
jint _5346_v4169Offset = (int) offsetArr[2];
jint _5349_v4175Offset = (int) offsetArr[3];
jint _5350_v4176Size = (int) sizeArr[0];
jint _5345_v4168Size = (int) sizeArr[1];
jint _5346_v4169Size = (int) sizeArr[2];
jint _5349_v4175Size = (int) sizeArr[3];
jint _5350_v4176Dim0 = (int) dim0Arr[0];
jint _5345_v4168Dim0 = (int) dim0Arr[1];
jint _5346_v4169Dim0 = (int) dim0Arr[2];
jint _5349_v4175Dim0 = (int) dim0Arr[3];
double *_5350_v4176 = ((double *) argArr[0]);
double *_5345_v4168 = ((double *) argArr[1]);
double *_5346_v4169 = ((double *) argArr[2]);
double *_5349_v4175 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5350_v4176[(global_id * _5350_v4176Dim0) + _5350_v4176Offset] = ((- _5346_v4169[(((global_id / _5346_v4169Size) * _5346_v4169Dim0) + (global_id % _5346_v4169Size)) + _5346_v4169Offset]) + _5345_v4168[(((global_id / 30) * _5345_v4168Dim0) + (global_id % 30)) + _5345_v4168Offset]) / _5349_v4175[(((global_id / _5349_v4175Size) * _5349_v4175Dim0) + (global_id % _5349_v4175Size)) + _5349_v4175Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
