#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation363_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _4737_v3758Offset = (int) offsetArr[0];
jint _4700_v3726Offset = (int) offsetArr[1];
jint _4734_v3752Offset = (int) offsetArr[2];
jint _4737_v3758Size = (int) sizeArr[0];
jint _4700_v3726Size = (int) sizeArr[1];
jint _4734_v3752Size = (int) sizeArr[2];
jint _4737_v3758Dim0 = (int) dim0Arr[0];
jint _4700_v3726Dim0 = (int) dim0Arr[1];
jint _4734_v3752Dim0 = (int) dim0Arr[2];
double *_4737_v3758 = ((double *) argArr[0]);
double *_4700_v3726 = ((double *) argArr[1]);
double *_4734_v3752 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_4737_v3758[global_id + _4737_v3758Offset] = (- pow(pow(((_4700_v3726[_4700_v3726Offset] + _4700_v3726[_4700_v3726Offset + 1]) / 2.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_4700_v3726[_4700_v3726Offset] + _4700_v3726[_4700_v3726Offset + 1]) / 2.0) + 1.0E-5, -0.5) * 0.5) * ((_4734_v3752[(global_id * 2) + _4734_v3752Offset + 1] + _4734_v3752[(global_id * 2) + _4734_v3752Offset]) * 0.5));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
