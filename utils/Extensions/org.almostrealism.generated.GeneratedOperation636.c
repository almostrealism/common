#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation636_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _10003_v6430Offset = (int) offsetArr[0];
jint _9953_v6400Offset = (int) offsetArr[1];
jint _9998_v6420Offset = (int) offsetArr[2];
jint _10003_v6430Size = (int) sizeArr[0];
jint _9953_v6400Size = (int) sizeArr[1];
jint _9998_v6420Size = (int) sizeArr[2];
jint _10003_v6430Dim0 = (int) dim0Arr[0];
jint _9953_v6400Dim0 = (int) dim0Arr[1];
jint _9998_v6420Dim0 = (int) dim0Arr[2];
double *_10003_v6430 = ((double *) argArr[0]);
double *_9953_v6400 = ((double *) argArr[1]);
double *_9998_v6420 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_10003_v6430[global_id + _10003_v6430Offset] = pow(pow(((_9953_v6400[((global_id / 64) * 4) + _9953_v6400Offset + 1] + _9953_v6400[((global_id / 64) * 4) + _9953_v6400Offset + 2] + _9953_v6400[((global_id / 64) * 4) + _9953_v6400Offset + 3] + _9953_v6400[((global_id / 64) * 4) + _9953_v6400Offset]) / 4.0) + 1.0E-5, 0.5), -1.0) * (((_9998_v6420[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9998_v6420Offset + 1] + _9998_v6420[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9998_v6420Offset + 2] + _9998_v6420[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9998_v6420Offset + 3] + _9998_v6420[((((global_id / 64) * 16) + (global_id % 16)) * 4) + _9998_v6420Offset]) * -0.25) + ((((- (global_id % 16)) + (global_id / 16)) == 0) ? 1 : 0));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
