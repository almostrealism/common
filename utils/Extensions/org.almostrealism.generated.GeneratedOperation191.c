#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation191_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2452_v2248Offset = (int) offsetArr[0];
jint _2455_v2253Offset = (int) offsetArr[1];
jint _2452_v2248Size = (int) sizeArr[0];
jint _2455_v2253Size = (int) sizeArr[1];
jint _2452_v2248Dim0 = (int) dim0Arr[0];
jint _2455_v2253Dim0 = (int) dim0Arr[1];
double *_2452_v2248 = ((double *) argArr[0]);
double *_2455_v2253 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2455_v2253[global_id + _2455_v2253Offset] = ((_2452_v2248[(global_id * 10) + _2452_v2248Offset + 9] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 8] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 1] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 2] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 3] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 4] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 5] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 6] + _2452_v2248[(global_id * 10) + _2452_v2248Offset + 7] + _2452_v2248[(global_id * 10) + _2452_v2248Offset]) * -2.0) + _2455_v2253[global_id + _2455_v2253Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
