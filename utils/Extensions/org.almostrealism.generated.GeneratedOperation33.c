#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation33_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _705_v459Offset = (int) offsetArr[0];
jint _637_v432Offset = (int) offsetArr[1];
jint _705_v461Offset = (int) offsetArr[2];
jint _705_v459Size = (int) sizeArr[0];
jint _637_v432Size = (int) sizeArr[1];
jint _705_v461Size = (int) sizeArr[2];
jint _705_v459Dim0 = (int) dim0Arr[0];
jint _637_v432Dim0 = (int) dim0Arr[1];
jint _705_v461Dim0 = (int) dim0Arr[2];
double *_705_v459 = ((double *) argArr[0]);
double *_637_v432 = ((double *) argArr[1]);
double *_705_v461 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_705_v459[global_id + _705_v459Offset] = ((((((global_id % 31) == 30) ? 30.0 : (((global_id % 31) == 29) ? 29.0 : (((global_id % 31) == 28) ? 28.0 : (((global_id % 31) == 27) ? 27.0 : (((global_id % 31) == 26) ? 26.0 : (((global_id % 31) == 25) ? 25.0 : (((global_id % 31) == 24) ? 24.0 : (((global_id % 31) == 23) ? 23.0 : (((global_id % 31) == 22) ? 22.0 : (((global_id % 31) == 21) ? 21.0 : (((global_id % 31) == 20) ? 20.0 : _705_v461[global_id + _705_v461Offset]))))))))))) + -15.0) == 0) ? (_637_v432[(global_id / 31) + _637_v432Offset] * 4.5351473922902495E-5) : ((sin((((global_id % 31) == 30) ? 47.12388980384689 : (((global_id % 31) == 29) ? 43.982297150257104 : (((global_id % 31) == 28) ? 40.840704496667314 : (((global_id % 31) == 27) ? 37.69911184307752 : (((global_id % 31) == 26) ? 34.55751918948772 : (((global_id % 31) == 25) ? 31.41592653589793 : (((global_id % 31) == 24) ? 28.274333882308138 : (((global_id % 31) == 23) ? 25.132741228718345 : (((global_id % 31) == 22) ? 21.991148575128552 : (((global_id % 31) == 21) ? 18.84955592153876 : (((global_id % 31) == 20) ? 15.707963267948966 : (((global_id % 31) == 19) ? 12.566370614359172 : (((global_id % 31) == 18) ? 9.42477796076938 : (((global_id % 31) == 17) ? 6.283185307179586 : (((global_id % 31) == 16) ? 3.141592653589793 : ((!((global_id % 31) == 15)) ? (((global_id % 31) == 14) ? -3.141592653589793 : (((global_id % 31) == 13) ? -6.283185307179586 : (((global_id % 31) == 12) ? -9.42477796076938 : (((global_id % 31) == 11) ? -12.566370614359172 : (((global_id % 31) == 10) ? -15.707963267948966 : (((global_id % 31) == 9) ? -18.84955592153876 : (((global_id % 31) == 8) ? -21.991148575128552 : (((global_id % 31) == 7) ? -25.132741228718345 : (((global_id % 31) == 6) ? -28.274333882308138 : (((global_id % 31) == 5) ? -31.41592653589793 : (((global_id % 31) == 4) ? -34.55751918948772 : (((global_id % 31) == 3) ? -37.69911184307752 : (((global_id % 31) == 2) ? -40.840704496667314 : (((global_id % 31) == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))) * (_637_v432[(global_id / 31) + _637_v432Offset] * 4.5351473922902495E-5))) / (((global_id % 31) == 30) ? 47.12388980384689 : (((global_id % 31) == 29) ? 43.982297150257104 : (((global_id % 31) == 28) ? 40.840704496667314 : (((global_id % 31) == 27) ? 37.69911184307752 : (((global_id % 31) == 26) ? 34.55751918948772 : (((global_id % 31) == 25) ? 31.41592653589793 : (((global_id % 31) == 24) ? 28.274333882308138 : (((global_id % 31) == 23) ? 25.132741228718345 : (((global_id % 31) == 22) ? 21.991148575128552 : (((global_id % 31) == 21) ? 18.84955592153876 : (((global_id % 31) == 20) ? 15.707963267948966 : (((global_id % 31) == 19) ? 12.566370614359172 : (((global_id % 31) == 18) ? 9.42477796076938 : (((global_id % 31) == 17) ? 6.283185307179586 : (((global_id % 31) == 16) ? 3.141592653589793 : ((!((global_id % 31) == 15)) ? (((global_id % 31) == 14) ? -3.141592653589793 : (((global_id % 31) == 13) ? -6.283185307179586 : (((global_id % 31) == 12) ? -9.42477796076938 : (((global_id % 31) == 11) ? -12.566370614359172 : (((global_id % 31) == 10) ? -15.707963267948966 : (((global_id % 31) == 9) ? -18.84955592153876 : (((global_id % 31) == 8) ? -21.991148575128552 : (((global_id % 31) == 7) ? -25.132741228718345 : (((global_id % 31) == 6) ? -28.274333882308138 : (((global_id % 31) == 5) ? -31.41592653589793 : (((global_id % 31) == 4) ? -34.55751918948772 : (((global_id % 31) == 3) ? -37.69911184307752 : (((global_id % 31) == 2) ? -40.840704496667314 : (((global_id % 31) == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))))) * ((- (((global_id % 31) == 30) ? 0.46 : (((global_id % 31) == 29) ? 0.4499478963375506 : (((global_id % 31) == 28) ? 0.42023091051559647 : (((global_id % 31) == 27) ? 0.37214781741247577 : (((global_id % 31) == 26) ? 0.3078000789250749 : (((global_id % 31) == 25) ? 0.23000000000000007 : (((global_id % 31) == 24) ? 0.14214781741247573 : (((global_id % 31) == 23) ? 0.048083093103120374 : (((global_id % 31) == 22) ? -0.04808309310312095 : (((global_id % 31) == 21) ? -0.1421478174124759 : (((global_id % 31) == 20) ? -0.2300000000000002 : (_705_v461[global_id + _705_v461Offset + 93] + 0.46))))))))))))) + 0.54);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
