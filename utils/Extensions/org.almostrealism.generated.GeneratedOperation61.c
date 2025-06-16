#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation61_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _886_v620Offset = (int) offsetArr[0];
jint _916_v629Offset = (int) offsetArr[1];
jint _929_v639Offset = (int) offsetArr[2];
jint _886_v620Size = (int) sizeArr[0];
jint _916_v629Size = (int) sizeArr[1];
jint _929_v639Size = (int) sizeArr[2];
jint _886_v620Dim0 = (int) dim0Arr[0];
jint _916_v629Dim0 = (int) dim0Arr[1];
jint _929_v639Dim0 = (int) dim0Arr[2];
double *_886_v620 = ((double *) argArr[0]);
double *_916_v629 = ((double *) argArr[1]);
double *_929_v639 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_929_v639[global_id + _929_v639Offset] = ((sin(_886_v620[global_id + _886_v620Offset] / 0.0022727272727272726)) * 0.9) + ((sin(_916_v629[global_id + _916_v629Offset] / 0.0017026203326920128)) * 0.6);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
