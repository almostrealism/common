#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation938_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _14227_v9256Offset = (int) offsetArr[0];
jint _14190_v9224Offset = (int) offsetArr[1];
jint _14224_v9250Offset = (int) offsetArr[2];
jint _14227_v9256Size = (int) sizeArr[0];
jint _14190_v9224Size = (int) sizeArr[1];
jint _14224_v9250Size = (int) sizeArr[2];
jint _14227_v9256Dim0 = (int) dim0Arr[0];
jint _14190_v9224Dim0 = (int) dim0Arr[1];
jint _14224_v9250Dim0 = (int) dim0Arr[2];
double *_14227_v9256 = ((double *) argArr[0]);
double *_14190_v9224 = ((double *) argArr[1]);
double *_14224_v9250 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_14227_v9256[global_id + _14227_v9256Offset] = (- pow(pow(((_14190_v9224[_14190_v9224Offset] + _14190_v9224[_14190_v9224Offset + 1]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_14190_v9224[_14190_v9224Offset] + _14190_v9224[_14190_v9224Offset + 1]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_14224_v9250[(global_id * 2) + _14224_v9250Offset + 1] + _14224_v9250[(global_id * 2) + _14224_v9250Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
