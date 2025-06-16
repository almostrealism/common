#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation900_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13779_v8920Offset = (int) offsetArr[0];
jint _13742_v8888Offset = (int) offsetArr[1];
jint _13776_v8914Offset = (int) offsetArr[2];
jint _13779_v8920Size = (int) sizeArr[0];
jint _13742_v8888Size = (int) sizeArr[1];
jint _13776_v8914Size = (int) sizeArr[2];
jint _13779_v8920Dim0 = (int) dim0Arr[0];
jint _13742_v8888Dim0 = (int) dim0Arr[1];
jint _13776_v8914Dim0 = (int) dim0Arr[2];
double *_13779_v8920 = ((double *) argArr[0]);
double *_13742_v8888 = ((double *) argArr[1]);
double *_13776_v8914 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13779_v8920[global_id + _13779_v8920Offset] = (- pow(pow(((_13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 1] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 2] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 3] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset]) / 4.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 1] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 2] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset + 3] + _13742_v8888[((global_id / 16) * 4) + _13742_v8888Offset]) / 4.0) + 1.0E-5, -0.5) * 0.5) * ((_13776_v8914[(global_id * 4) + _13776_v8914Offset + 1] + _13776_v8914[(global_id * 4) + _13776_v8914Offset + 2] + _13776_v8914[(global_id * 4) + _13776_v8914Offset + 3] + _13776_v8914[(global_id * 4) + _13776_v8914Offset]) * 0.25));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
