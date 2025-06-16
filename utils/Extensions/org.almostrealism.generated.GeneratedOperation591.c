#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation591_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9147_v6084Offset = (int) offsetArr[0];
jint _9142_v6073Offset = (int) offsetArr[1];
jint _9147_v6084Size = (int) sizeArr[0];
jint _9142_v6073Size = (int) sizeArr[1];
jint _9147_v6084Dim0 = (int) dim0Arr[0];
jint _9142_v6073Dim0 = (int) dim0Arr[1];
double *_9147_v6084 = ((double *) argArr[0]);
double *_9142_v6073 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9147_v6084[global_id + _9147_v6084Offset] = ((- ((_9142_v6073[((global_id / 2) * 2) + _9142_v6073Offset + 1] + _9142_v6073[((global_id / 2) * 2) + _9142_v6073Offset]) / 2.0)) + _9142_v6073[global_id + _9142_v6073Offset]) * ((- ((_9142_v6073[((global_id / 2) * 2) + _9142_v6073Offset + 1] + _9142_v6073[((global_id / 2) * 2) + _9142_v6073Offset]) / 2.0)) + _9142_v6073[global_id + _9142_v6073Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
