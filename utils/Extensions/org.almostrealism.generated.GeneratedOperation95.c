#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation95_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _1170_v832Offset = (int) offsetArr[0];
jint _1170_v833Offset = (int) offsetArr[1];
jint _1170_v835Offset = (int) offsetArr[2];
jint _1170_v832Size = (int) sizeArr[0];
jint _1170_v833Size = (int) sizeArr[1];
jint _1170_v835Size = (int) sizeArr[2];
jint _1170_v832Dim0 = (int) dim0Arr[0];
jint _1170_v833Dim0 = (int) dim0Arr[1];
jint _1170_v835Dim0 = (int) dim0Arr[2];
double *_1170_v832 = ((double *) argArr[0]);
double *_1170_v833 = ((double *) argArr[1]);
double *_1170_v835 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_1170_v832[global_id + _1170_v832Offset] = (_1170_v835[(((global_id % 6) * 4) + ((global_id / 36) * 24)) + _1170_v835Offset] * _1170_v833[((global_id / 6) * 4) + _1170_v833Offset]) + (_1170_v833[((global_id / 6) * 4) + _1170_v833Offset + 1] * _1170_v835[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 1) + _1170_v835Offset]) + (_1170_v833[((global_id / 6) * 4) + _1170_v833Offset + 2] * _1170_v835[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 2) + _1170_v835Offset]) + (_1170_v833[((global_id / 6) * 4) + _1170_v833Offset + 3] * _1170_v835[(((global_id % 6) * 4) + ((global_id / 36) * 24) + 3) + _1170_v835Offset]);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
