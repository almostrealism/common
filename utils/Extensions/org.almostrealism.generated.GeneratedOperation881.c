#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation881_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _13330_v8723Offset = (int) offsetArr[0];
jint _13325_v8720Offset = (int) offsetArr[1];
jint _13330_v8723Size = (int) sizeArr[0];
jint _13325_v8720Size = (int) sizeArr[1];
jint _13330_v8723Dim0 = (int) dim0Arr[0];
jint _13325_v8720Dim0 = (int) dim0Arr[1];
double *_13330_v8723 = ((double *) argArr[0]);
double *_13325_v8720 = ((double *) argArr[1]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_13330_v8723[global_id + _13330_v8723Offset] = (_13325_v8720[global_id + _13325_v8720Offset + 2] + -0.04434013827749426) / 0.03608775509037007;
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
