#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation207_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2583_v2379Offset = (int) offsetArr[0];
jint _2569_v2373Offset = (int) offsetArr[1];
jint _2583_v2379Size = (int) sizeArr[0];
jint _2569_v2373Size = (int) sizeArr[1];
jint _2583_v2379Dim0 = (int) dim0Arr[0];
jint _2569_v2373Dim0 = (int) dim0Arr[1];
double *_2583_v2379 = ((double *) argArr[0]);
double *_2569_v2373 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2583_v2379[(global_id * _2583_v2379Dim0) + _2583_v2379Offset] = (((- (((global_id % 9) * 9) + ((global_id / 9) * 3) + (global_id % 3))) + ((global_id % 9) * 10)) == 0) ? _2569_v2373[(global_id % 3) + _2569_v2373Offset] : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
