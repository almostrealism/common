#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation462_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _6203_v4635Offset = (int) offsetArr[0];
jint _6183_v4604Offset = (int) offsetArr[1];
jint _6194_v4616Offset = (int) offsetArr[2];
jint _6202_v4633Offset = (int) offsetArr[3];
jint _6203_v4635Size = (int) sizeArr[0];
jint _6183_v4604Size = (int) sizeArr[1];
jint _6194_v4616Size = (int) sizeArr[2];
jint _6202_v4633Size = (int) sizeArr[3];
jint _6203_v4635Dim0 = (int) dim0Arr[0];
jint _6183_v4604Dim0 = (int) dim0Arr[1];
jint _6194_v4616Dim0 = (int) dim0Arr[2];
jint _6202_v4633Dim0 = (int) dim0Arr[3];
double *_6203_v4635 = ((double *) argArr[0]);
double *_6183_v4604 = ((double *) argArr[1]);
double *_6194_v4616 = ((double *) argArr[2]);
double *_6202_v4633 = ((double *) argArr[3]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_6203_v4635[global_id + _6203_v4635Offset] = (((- (global_id % 96)) + (global_id / 96)) == 0) ? ((((- ((_6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 8] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 1] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 13] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 2] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 3] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 4] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 15] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 12] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 5] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 6] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 11] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 7] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 14] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 10] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset + 9] + _6183_v4604[((global_id / 1536) * 16) + _6183_v4604Offset]) / 16.0)) + _6183_v4604[(global_id / 96) + _6183_v4604Offset]) / pow(((_6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 8] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 1] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 13] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 2] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 3] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 4] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 15] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 12] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 5] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 6] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 11] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 7] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 14] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 10] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset + 9] + _6194_v4616[((global_id / 1536) * 16) + _6194_v4616Offset]) / 16.0) + 1.0E-5, 0.5)) * _6202_v4633[(global_id / 96) + _6202_v4633Offset]) : 0;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
