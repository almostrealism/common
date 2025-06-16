#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation452_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6160_v4517Offset = (int) offsetArr[0];
jint _6123_v4485Offset = (int) offsetArr[1];
jint _6157_v4511Offset = (int) offsetArr[2];
jint _6160_v4517Size = (int) sizeArr[0];
jint _6123_v4485Size = (int) sizeArr[1];
jint _6157_v4511Size = (int) sizeArr[2];
jint _6160_v4517Dim0 = (int) dim0Arr[0];
jint _6123_v4485Dim0 = (int) dim0Arr[1];
jint _6157_v4511Dim0 = (int) dim0Arr[2];
double *_6160_v4517 = ((double *) argArr[0]);
double *_6123_v4485 = ((double *) argArr[1]);
double *_6157_v4511 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6160_v4517[global_id + _6160_v4517Offset] = (- pow(pow(((_6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 8] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 1] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 13] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 2] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 3] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 4] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 15] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 12] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 5] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 6] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 11] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 7] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 14] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 10] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 9] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset]) / 16.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 8] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 1] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 13] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 2] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 3] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 4] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 15] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 12] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 5] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 6] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 11] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 7] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 14] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 10] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset + 9] + _6123_v4485[((global_id / 96) * 16) + _6123_v4485Offset]) / 16.0) + 1.0E-5, -0.5) * 0.5) * ((_6157_v4511[(global_id * 16) + _6157_v4511Offset + 8] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 1] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 13] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 2] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 3] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 4] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 15] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 12] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 5] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 6] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 11] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 7] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 14] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 10] + _6157_v4511[(global_id * 16) + _6157_v4511Offset + 9] + _6157_v4511[(global_id * 16) + _6157_v4511Offset]) * 0.0625));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
