#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation335_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4286_v3436Offset = (int) offsetArr[0];
jint _4236_v3406Offset = (int) offsetArr[1];
jint _4281_v3426Offset = (int) offsetArr[2];
jint _4286_v3436Size = (int) sizeArr[0];
jint _4236_v3406Size = (int) sizeArr[1];
jint _4281_v3426Size = (int) sizeArr[2];
jint _4286_v3436Dim0 = (int) dim0Arr[0];
jint _4236_v3406Dim0 = (int) dim0Arr[1];
jint _4281_v3426Dim0 = (int) dim0Arr[2];
double *_4286_v3436 = ((double *) argArr[0]);
double *_4236_v3406 = ((double *) argArr[1]);
double *_4281_v3426 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4286_v3436[global_id + _4286_v3436Offset] = (((_4281_v3426[((global_id % 4) * 4) + _4281_v3426Offset + 1] + _4281_v3426[((global_id % 4) * 4) + _4281_v3426Offset + 2] + _4281_v3426[((global_id % 4) * 4) + _4281_v3426Offset + 3] + _4281_v3426[((global_id % 4) * 4) + _4281_v3426Offset]) * -0.25) + ((((- (global_id % 4)) + (global_id / 4)) == 0) ? 1 : 0)) * pow(pow(((_4236_v3406[_4236_v3406Offset] + _4236_v3406[_4236_v3406Offset + 1] + _4236_v3406[_4236_v3406Offset + 2] + _4236_v3406[_4236_v3406Offset + 3]) / 4.0) + 1.0E-5, 0.5), -1.0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
