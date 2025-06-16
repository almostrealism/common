#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation41_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _782_v532Offset = (int) offsetArr[0];
jint _780_v529Offset = (int) offsetArr[1];
jint _783_v533Offset = (int) offsetArr[2];
jint _782_v532Size = (int) sizeArr[0];
jint _780_v529Size = (int) sizeArr[1];
jint _783_v533Size = (int) sizeArr[2];
jint _782_v532Dim0 = (int) dim0Arr[0];
jint _780_v529Dim0 = (int) dim0Arr[1];
jint _783_v533Dim0 = (int) dim0Arr[2];
double *_782_v532 = ((double *) argArr[0]);
double *_780_v529 = ((double *) argArr[1]);
double *_783_v533 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_782_v532[_782_v532Offset] = _780_v529[_780_v529Offset + 1];
_782_v532[_782_v532Offset + 1] = 30.0;
_783_v533[(((int) _783_v533[_783_v533Offset + 1]) * 2) + _783_v533Offset] = _782_v532[(global_id * _782_v532Dim0) + _782_v532Offset];
_783_v533[(((int) _783_v533[_783_v533Offset + 1]) * 2) + _783_v533Offset + 1] = _782_v532[(global_id * _782_v532Dim0) + _782_v532Offset + 1];
_783_v533[_783_v533Offset + 1] = _783_v533[_783_v533Offset + 1] + 1.0;

}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
