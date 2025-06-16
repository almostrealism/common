#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation689_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10851_v6893Offset = (int) offsetArr[0];
jint _10784_v6882Offset = (int) offsetArr[1];
jint _10848_v6885Offset = (int) offsetArr[2];
jint _10848_v6886Offset = (int) offsetArr[3];
jint _10850_v6891Offset = (int) offsetArr[4];
jint _10851_v6893Size = (int) sizeArr[0];
jint _10784_v6882Size = (int) sizeArr[1];
jint _10848_v6885Size = (int) sizeArr[2];
jint _10848_v6886Size = (int) sizeArr[3];
jint _10850_v6891Size = (int) sizeArr[4];
jint _10851_v6893Dim0 = (int) dim0Arr[0];
jint _10784_v6882Dim0 = (int) dim0Arr[1];
jint _10848_v6885Dim0 = (int) dim0Arr[2];
jint _10848_v6886Dim0 = (int) dim0Arr[3];
jint _10850_v6891Dim0 = (int) dim0Arr[4];
double *_10851_v6893 = ((double *) argArr[0]);
double *_10784_v6882 = ((double *) argArr[1]);
double *_10848_v6885 = ((double *) argArr[2]);
double *_10848_v6886 = ((double *) argArr[3]);
double *_10850_v6891 = ((double *) argArr[4]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10851_v6893[global_id + _10851_v6893Offset] = (_10784_v6882[(global_id / 2) + _10784_v6882Offset] * (_10848_v6885[global_id + _10848_v6885Offset] + _10848_v6886[global_id + _10848_v6886Offset])) * _10850_v6891[(global_id / 2) + _10850_v6891Offset];
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
