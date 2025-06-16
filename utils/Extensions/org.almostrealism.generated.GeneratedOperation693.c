#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation693_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10877_v7098Offset = (int) offsetArr[0];
jint _10857_v7067Offset = (int) offsetArr[1];
jint _10868_v7079Offset = (int) offsetArr[2];
jint _10876_v7096Offset = (int) offsetArr[3];
jint _10877_v7098Size = (int) sizeArr[0];
jint _10857_v7067Size = (int) sizeArr[1];
jint _10868_v7079Size = (int) sizeArr[2];
jint _10876_v7096Size = (int) sizeArr[3];
jint _10877_v7098Dim0 = (int) dim0Arr[0];
jint _10857_v7067Dim0 = (int) dim0Arr[1];
jint _10868_v7079Dim0 = (int) dim0Arr[2];
jint _10876_v7096Dim0 = (int) dim0Arr[3];
double *_10877_v7098 = ((double *) argArr[0]);
double *_10857_v7067 = ((double *) argArr[1]);
double *_10868_v7079 = ((double *) argArr[2]);
double *_10876_v7096 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10877_v7098[global_id + _10877_v7098Offset] = (((- (global_id % 2)) + (global_id / 2)) == 0) ? ((((- ((_10857_v7067[_10857_v7067Offset] + _10857_v7067[_10857_v7067Offset + 1]) / 2.0)) + _10857_v7067[(global_id / 2) + _10857_v7067Offset]) / pow(((_10868_v7079[_10868_v7079Offset] + _10868_v7079[_10868_v7079Offset + 1]) / 2.0) + 1.0E-5, 0.5)) * _10876_v7096[(global_id / 2) + _10876_v7096Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
