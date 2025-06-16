#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation156_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _2095_v2031Offset = (int) offsetArr[0];
jint _2092_v2027Offset = (int) offsetArr[1];
jint _2095_v2031Size = (int) sizeArr[0];
jint _2092_v2027Size = (int) sizeArr[1];
jint _2095_v2031Dim0 = (int) dim0Arr[0];
jint _2092_v2027Dim0 = (int) dim0Arr[1];
double *_2095_v2031 = ((double *) argArr[0]);
double *_2092_v2027 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_2095_v2031[global_id + _2095_v2031Offset] = (_2092_v2027[(global_id * 4) + _2092_v2027Offset + 1] + _2092_v2027[(global_id * 4) + _2092_v2027Offset + 2] + _2092_v2027[(global_id * 4) + _2092_v2027Offset + 3] + _2092_v2027[(global_id * 4) + _2092_v2027Offset]) * (((global_id / 12) == 2) ? 0.46325086151518013 : (((global_id / 12) == 1) ? 0.12018382548710616 : 0.29553657003430367));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
