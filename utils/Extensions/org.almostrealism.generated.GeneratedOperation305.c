#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation305_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _3872_v3228Offset = (int) offsetArr[0];
jint _3835_v3196Offset = (int) offsetArr[1];
jint _3869_v3222Offset = (int) offsetArr[2];
jint _3872_v3228Size = (int) sizeArr[0];
jint _3835_v3196Size = (int) sizeArr[1];
jint _3869_v3222Size = (int) sizeArr[2];
jint _3872_v3228Dim0 = (int) dim0Arr[0];
jint _3835_v3196Dim0 = (int) dim0Arr[1];
jint _3869_v3222Dim0 = (int) dim0Arr[2];
double *_3872_v3228 = ((double *) argArr[0]);
double *_3835_v3196 = ((double *) argArr[1]);
double *_3869_v3222 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_3872_v3228[global_id + _3872_v3228Offset] = (- pow(pow(((_3835_v3196[_3835_v3196Offset] + _3835_v3196[_3835_v3196Offset + 1]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_3835_v3196[_3835_v3196Offset] + _3835_v3196[_3835_v3196Offset + 1]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_3869_v3222[(global_id * 2) + _3869_v3222Offset + 1] + _3869_v3222[(global_id * 2) + _3869_v3222Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
