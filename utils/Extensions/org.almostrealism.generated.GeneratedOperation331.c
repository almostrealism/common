#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation331_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4273_v3472Offset = (int) offsetArr[0];
jint _4236_v3440Offset = (int) offsetArr[1];
jint _4270_v3466Offset = (int) offsetArr[2];
jint _4273_v3472Size = (int) sizeArr[0];
jint _4236_v3440Size = (int) sizeArr[1];
jint _4270_v3466Size = (int) sizeArr[2];
jint _4273_v3472Dim0 = (int) dim0Arr[0];
jint _4236_v3440Dim0 = (int) dim0Arr[1];
jint _4270_v3466Dim0 = (int) dim0Arr[2];
double *_4273_v3472 = ((double *) argArr[0]);
double *_4236_v3440 = ((double *) argArr[1]);
double *_4270_v3466 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4273_v3472[global_id + _4273_v3472Offset] = (- pow(pow(((_4236_v3440[_4236_v3440Offset] + _4236_v3440[_4236_v3440Offset + 1] + _4236_v3440[_4236_v3440Offset + 2] + _4236_v3440[_4236_v3440Offset + 3]) / 4.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_4236_v3440[_4236_v3440Offset] + _4236_v3440[_4236_v3440Offset + 1] + _4236_v3440[_4236_v3440Offset + 2] + _4236_v3440[_4236_v3440Offset + 3]) / 4.0) + 1.0E-5, -0.5) * 0.5) * ((_4270_v3466[(global_id * 4) + _4270_v3466Offset + 1] + _4270_v3466[(global_id * 4) + _4270_v3466Offset + 2] + _4270_v3466[(global_id * 4) + _4270_v3466Offset + 3] + _4270_v3466[(global_id * 4) + _4270_v3466Offset]) * 0.25));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
