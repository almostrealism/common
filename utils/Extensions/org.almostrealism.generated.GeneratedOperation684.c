#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation684_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10834_v6982Offset = (int) offsetArr[0];
jint _10797_v6950Offset = (int) offsetArr[1];
jint _10831_v6976Offset = (int) offsetArr[2];
jint _10834_v6982Size = (int) sizeArr[0];
jint _10797_v6950Size = (int) sizeArr[1];
jint _10831_v6976Size = (int) sizeArr[2];
jint _10834_v6982Dim0 = (int) dim0Arr[0];
jint _10797_v6950Dim0 = (int) dim0Arr[1];
jint _10831_v6976Dim0 = (int) dim0Arr[2];
double *_10834_v6982 = ((double *) argArr[0]);
double *_10797_v6950 = ((double *) argArr[1]);
double *_10831_v6976 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10834_v6982[global_id + _10834_v6982Offset] = (- pow(pow(((_10797_v6950[_10797_v6950Offset] + _10797_v6950[_10797_v6950Offset + 1]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_10797_v6950[_10797_v6950Offset] + _10797_v6950[_10797_v6950Offset + 1]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_10831_v6976[(global_id * 2) + _10831_v6976Offset + 1] + _10831_v6976[(global_id * 2) + _10831_v6976Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
