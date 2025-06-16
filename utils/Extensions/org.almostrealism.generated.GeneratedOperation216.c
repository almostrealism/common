#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation216_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2638_v2435Offset = (int) offsetArr[0];
jint _2639_v2438Offset = (int) offsetArr[1];
jint _2641_v2443Offset = (int) offsetArr[2];
jint _2638_v2435Size = (int) sizeArr[0];
jint _2639_v2438Size = (int) sizeArr[1];
jint _2641_v2443Size = (int) sizeArr[2];
jint _2638_v2435Dim0 = (int) dim0Arr[0];
jint _2639_v2438Dim0 = (int) dim0Arr[1];
jint _2641_v2443Dim0 = (int) dim0Arr[2];
double *_2638_v2435 = ((double *) argArr[0]);
double *_2639_v2438 = ((double *) argArr[1]);
double *_2641_v2443 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2641_v2443[global_id + _2641_v2443Offset] = (_2638_v2435[((global_id * 8) % 8) + _2638_v2435Offset] * _2639_v2438[(global_id * 8) + _2639_v2438Offset]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 1] * _2638_v2435[_2638_v2435Offset + 1]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 2] * _2638_v2435[_2638_v2435Offset + 2]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 3] * _2638_v2435[_2638_v2435Offset + 3]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 4] * _2638_v2435[_2638_v2435Offset + 4]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 5] * _2638_v2435[_2638_v2435Offset + 5]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 6] * _2638_v2435[_2638_v2435Offset + 6]) + (_2639_v2438[(global_id * 8) + _2639_v2438Offset + 7] * _2638_v2435[_2638_v2435Offset + 7]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
