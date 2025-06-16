#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation598_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9220_v6113Offset = (int) offsetArr[0];
jint _9215_v6102Offset = (int) offsetArr[1];
jint _9220_v6113Size = (int) sizeArr[0];
jint _9215_v6102Size = (int) sizeArr[1];
jint _9220_v6113Dim0 = (int) dim0Arr[0];
jint _9215_v6102Dim0 = (int) dim0Arr[1];
double *_9220_v6113 = ((double *) argArr[0]);
double *_9215_v6102 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9220_v6113[global_id + _9220_v6113Offset] = ((- ((_9215_v6102[_9215_v6102Offset] + _9215_v6102[_9215_v6102Offset + 1]) / 2.0)) + _9215_v6102[global_id + _9215_v6102Offset]) * ((- ((_9215_v6102[_9215_v6102Offset] + _9215_v6102[_9215_v6102Offset + 1]) / 2.0)) + _9215_v6102[global_id + _9215_v6102Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
