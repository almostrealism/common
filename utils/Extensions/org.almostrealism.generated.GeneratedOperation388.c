#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation388_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _5284_v3993Offset = (int) offsetArr[0];
jint _5248_v3966Offset = (int) offsetArr[1];
jint _5282_v3989Offset = (int) offsetArr[2];
jint _5284_v3993Size = (int) sizeArr[0];
jint _5248_v3966Size = (int) sizeArr[1];
jint _5282_v3989Size = (int) sizeArr[2];
jint _5284_v3993Dim0 = (int) dim0Arr[0];
jint _5248_v3966Dim0 = (int) dim0Arr[1];
jint _5282_v3989Dim0 = (int) dim0Arr[2];
double *_5284_v3993 = ((double *) argArr[0]);
double *_5248_v3966 = ((double *) argArr[1]);
double *_5282_v3989 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_5284_v3993[global_id + _5284_v3993Offset] = (- pow(pow((_5248_v3966[(global_id / 120) + _5248_v3966Offset] / 30.0) + 1.0E-5, 0.5), -2.0)) * ((pow((_5248_v3966[(global_id / 120) + _5248_v3966Offset] / 30.0) + 1.0E-5, -0.5) * 0.5) * (_5282_v3989[global_id + _5282_v3989Offset] * 0.03333333333333333));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
