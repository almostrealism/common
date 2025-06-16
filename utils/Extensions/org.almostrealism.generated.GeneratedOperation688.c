#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation688_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10847_v6946Offset = (int) offsetArr[0];
jint _10797_v6916Offset = (int) offsetArr[1];
jint _10842_v6936Offset = (int) offsetArr[2];
jint _10847_v6946Size = (int) sizeArr[0];
jint _10797_v6916Size = (int) sizeArr[1];
jint _10842_v6936Size = (int) sizeArr[2];
jint _10847_v6946Dim0 = (int) dim0Arr[0];
jint _10797_v6916Dim0 = (int) dim0Arr[1];
jint _10842_v6936Dim0 = (int) dim0Arr[2];
double *_10847_v6946 = ((double *) argArr[0]);
double *_10797_v6916 = ((double *) argArr[1]);
double *_10842_v6936 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10847_v6946[global_id + _10847_v6946Offset] = (((_10842_v6936[((global_id % 2) * 2) + _10842_v6936Offset + 1] + _10842_v6936[((global_id % 2) * 2) + _10842_v6936Offset]) * -0.5) + ((((- (global_id % 2)) + (global_id / 2)) == 0) ? 1 : 0)) * pow(pow(((_10797_v6916[_10797_v6916Offset] + _10797_v6916[_10797_v6916Offset + 1]) / 2.0) + 1.0E-5, 0.5), -1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
