#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation98_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1173_v847Offset = (int) offsetArr[0];
jint _1173_v848Offset = (int) offsetArr[1];
jint _1173_v850Offset = (int) offsetArr[2];
jint _1173_v847Size = (int) sizeArr[0];
jint _1173_v848Size = (int) sizeArr[1];
jint _1173_v850Size = (int) sizeArr[2];
jint _1173_v847Dim0 = (int) dim0Arr[0];
jint _1173_v848Dim0 = (int) dim0Arr[1];
jint _1173_v850Dim0 = (int) dim0Arr[2];
double *_1173_v847 = ((double *) argArr[0]);
double *_1173_v848 = ((double *) argArr[1]);
double *_1173_v850 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1173_v847[global_id + _1173_v847Offset] = (_1173_v848[(global_id / 3) + _1173_v848Offset + 2] * _1173_v850[(global_id % 3) + _1173_v850Offset + 3]) + (_1173_v848[(global_id / 3) + _1173_v848Offset + 4] * _1173_v850[(global_id % 3) + _1173_v850Offset + 6]) + (_1173_v848[(global_id / 3) + _1173_v848Offset] * _1173_v850[(global_id % 3) + _1173_v850Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
