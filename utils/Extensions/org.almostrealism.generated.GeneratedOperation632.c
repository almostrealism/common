#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation632_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _9990_v6466Offset = (int) offsetArr[0];
jint _9953_v6434Offset = (int) offsetArr[1];
jint _9987_v6460Offset = (int) offsetArr[2];
jint _9990_v6466Size = (int) sizeArr[0];
jint _9953_v6434Size = (int) sizeArr[1];
jint _9987_v6460Size = (int) sizeArr[2];
jint _9990_v6466Dim0 = (int) dim0Arr[0];
jint _9953_v6434Dim0 = (int) dim0Arr[1];
jint _9987_v6460Dim0 = (int) dim0Arr[2];
double *_9990_v6466 = ((double *) argArr[0]);
double *_9953_v6434 = ((double *) argArr[1]);
double *_9987_v6460 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_9990_v6466[global_id + _9990_v6466Offset] = (- pow(pow(((_9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 1] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 2] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 3] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset]) / 4.0) + 1.0E-5, 0.5), -2.0)) * ((pow(((_9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 1] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 2] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset + 3] + _9953_v6434[((global_id / 16) * 4) + _9953_v6434Offset]) / 4.0) + 1.0E-5, -0.5) * 0.5) * ((_9987_v6460[(global_id * 4) + _9987_v6460Offset + 1] + _9987_v6460[(global_id * 4) + _9987_v6460Offset + 2] + _9987_v6460[(global_id * 4) + _9987_v6460Offset + 3] + _9987_v6460[(global_id * 4) + _9987_v6460Offset]) * 0.25));
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
