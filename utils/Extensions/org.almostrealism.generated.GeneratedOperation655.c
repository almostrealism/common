#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation655_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10176_v6697Offset = (int) offsetArr[0];
jint _10171_v6686Offset = (int) offsetArr[1];
jint _10176_v6697Size = (int) sizeArr[0];
jint _10171_v6686Size = (int) sizeArr[1];
jint _10176_v6697Dim0 = (int) dim0Arr[0];
jint _10171_v6686Dim0 = (int) dim0Arr[1];
double *_10176_v6697 = ((double *) argArr[0]);
double *_10171_v6686 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10176_v6697[global_id + _10176_v6697Offset] = ((- ((_10171_v6686[_10171_v6686Offset + 4] + _10171_v6686[_10171_v6686Offset + 5] + _10171_v6686[_10171_v6686Offset + 6] + _10171_v6686[_10171_v6686Offset + 7]) / 4.0)) + _10171_v6686[global_id + _10171_v6686Offset + 4]) * ((- ((_10171_v6686[_10171_v6686Offset + 4] + _10171_v6686[_10171_v6686Offset + 5] + _10171_v6686[_10171_v6686Offset + 6] + _10171_v6686[_10171_v6686Offset + 7]) / 4.0)) + _10171_v6686[global_id + _10171_v6686Offset + 4]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
