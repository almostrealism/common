#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <jni.h>
double M_PI_F = M_PI;
JNIEXPORT void JNICALL Java_org_almostrealism_generated_GeneratedOperation3_apply(JNIEnv *env, jobject obj, jlong commandQueue, jlongArray arg, jintArray offset, jintArray size, jintArray dim0, jint count, jint global_index, jlong global_total, jint global_id) {
jlong *argArr = (*env)->GetLongArrayElements(env, arg, 0);
jint *offsetArr = (*env)->GetIntArrayElements(env, offset, 0);
jint *sizeArr = (*env)->GetIntArrayElements(env, size, 0);
jint *dim0Arr = (*env)->GetIntArrayElements(env, dim0, 0);
jint _89_v62Offset = (int) offsetArr[0];
jint _18_v31Offset = (int) offsetArr[1];
jint _89_v64Offset = (int) offsetArr[2];
jint _89_v62Size = (int) sizeArr[0];
jint _18_v31Size = (int) sizeArr[1];
jint _89_v64Size = (int) sizeArr[2];
jint _89_v62Dim0 = (int) dim0Arr[0];
jint _18_v31Dim0 = (int) dim0Arr[1];
jint _89_v64Dim0 = (int) dim0Arr[2];
double *_89_v62 = ((double *) argArr[0]);
double *_18_v31 = ((double *) argArr[1]);
double *_89_v64 = ((double *) argArr[2]);
for (int global_id = global_index ; global_id < global_total; global_id += 20) {
_89_v62[global_id + _89_v62Offset] = (- (((((((global_id % 31) == 30) ? 30.0 : (((global_id % 31) == 29) ? 29.0 : (((global_id % 31) == 28) ? 28.0 : (((global_id % 31) == 27) ? 27.0 : (((global_id % 31) == 26) ? 26.0 : (((global_id % 31) == 25) ? 25.0 : (((global_id % 31) == 24) ? 24.0 : (((global_id % 31) == 23) ? 23.0 : (((global_id % 31) == 22) ? 22.0 : (((global_id % 31) == 21) ? 21.0 : (((global_id % 31) == 20) ? 20.0 : _89_v64[global_id + _89_v64Offset]))))))))))) + -15.0) == 0) ? (_18_v31[(global_id / 31) + _18_v31Offset] * 4.5351473922902495E-5) : ((sin((((global_id % 31) == 30) ? 47.12388980384689 : (((global_id % 31) == 29) ? 43.982297150257104 : (((global_id % 31) == 28) ? 40.840704496667314 : (((global_id % 31) == 27) ? 37.69911184307752 : (((global_id % 31) == 26) ? 34.55751918948772 : (((global_id % 31) == 25) ? 31.41592653589793 : (((global_id % 31) == 24) ? 28.274333882308138 : (((global_id % 31) == 23) ? 25.132741228718345 : (((global_id % 31) == 22) ? 21.991148575128552 : (((global_id % 31) == 21) ? 18.84955592153876 : (((global_id % 31) == 20) ? 15.707963267948966 : (((global_id % 31) == 19) ? 12.566370614359172 : (((global_id % 31) == 18) ? 9.42477796076938 : (((global_id % 31) == 17) ? 6.283185307179586 : (((global_id % 31) == 16) ? 3.141592653589793 : ((!((global_id % 31) == 15)) ? (((global_id % 31) == 14) ? -3.141592653589793 : (((global_id % 31) == 13) ? -6.283185307179586 : (((global_id % 31) == 12) ? -9.42477796076938 : (((global_id % 31) == 11) ? -12.566370614359172 : (((global_id % 31) == 10) ? -15.707963267948966 : (((global_id % 31) == 9) ? -18.84955592153876 : (((global_id % 31) == 8) ? -21.991148575128552 : (((global_id % 31) == 7) ? -25.132741228718345 : (((global_id % 31) == 6) ? -28.274333882308138 : (((global_id % 31) == 5) ? -31.41592653589793 : (((global_id % 31) == 4) ? -34.55751918948772 : (((global_id % 31) == 3) ? -37.69911184307752 : (((global_id % 31) == 2) ? -40.840704496667314 : (((global_id % 31) == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))) * (_18_v31[(global_id / 31) + _18_v31Offset] * 4.5351473922902495E-5))) / (((global_id % 31) == 30) ? 47.12388980384689 : (((global_id % 31) == 29) ? 43.982297150257104 : (((global_id % 31) == 28) ? 40.840704496667314 : (((global_id % 31) == 27) ? 37.69911184307752 : (((global_id % 31) == 26) ? 34.55751918948772 : (((global_id % 31) == 25) ? 31.41592653589793 : (((global_id % 31) == 24) ? 28.274333882308138 : (((global_id % 31) == 23) ? 25.132741228718345 : (((global_id % 31) == 22) ? 21.991148575128552 : (((global_id % 31) == 21) ? 18.84955592153876 : (((global_id % 31) == 20) ? 15.707963267948966 : (((global_id % 31) == 19) ? 12.566370614359172 : (((global_id % 31) == 18) ? 9.42477796076938 : (((global_id % 31) == 17) ? 6.283185307179586 : (((global_id % 31) == 16) ? 3.141592653589793 : ((!((global_id % 31) == 15)) ? (((global_id % 31) == 14) ? -3.141592653589793 : (((global_id % 31) == 13) ? -6.283185307179586 : (((global_id % 31) == 12) ? -9.42477796076938 : (((global_id % 31) == 11) ? -12.566370614359172 : (((global_id % 31) == 10) ? -15.707963267948966 : (((global_id % 31) == 9) ? -18.84955592153876 : (((global_id % 31) == 8) ? -21.991148575128552 : (((global_id % 31) == 7) ? -25.132741228718345 : (((global_id % 31) == 6) ? -28.274333882308138 : (((global_id % 31) == 5) ? -31.41592653589793 : (((global_id % 31) == 4) ? -34.55751918948772 : (((global_id % 31) == 3) ? -37.69911184307752 : (((global_id % 31) == 2) ? -40.840704496667314 : (((global_id % 31) == 1) ? -43.982297150257104 : -47.12388980384689)))))))))))))) : 0)))))))))))))))))) * ((- (((global_id % 31) == 30) ? 0.46 : (((global_id % 31) == 29) ? 0.4499478963375506 : (((global_id % 31) == 28) ? 0.42023091051559647 : (((global_id % 31) == 27) ? 0.37214781741247577 : (((global_id % 31) == 26) ? 0.3078000789250749 : (((global_id % 31) == 25) ? 0.23000000000000007 : (((global_id % 31) == 24) ? 0.14214781741247573 : (((global_id % 31) == 23) ? 0.048083093103120374 : (((global_id % 31) == 22) ? -0.04808309310312095 : (((global_id % 31) == 21) ? -0.1421478174124759 : (((global_id % 31) == 20) ? -0.2300000000000002 : (_89_v64[global_id + _89_v64Offset + 93] + 0.46))))))))))))) + 0.54))) + ((((((global_id % 31) == 30) ? 30.0 : (((global_id % 31) == 29) ? 29.0 : (((global_id % 31) == 28) ? 28.0 : (((global_id % 31) == 27) ? 27.0 : (((global_id % 31) == 26) ? 26.0 : (((global_id % 31) == 25) ? 25.0 : (((global_id % 31) == 24) ? 24.0 : (((global_id % 31) == 23) ? 23.0 : (((global_id % 31) == 22) ? 22.0 : (((global_id % 31) == 21) ? 21.0 : (((global_id % 31) == 20) ? 20.0 : _89_v64[global_id + _89_v64Offset]))))))))))) + -15.0) == 0) ? 1.0 : 0);
}
(*env)->ReleaseLongArrayElements(env, arg, argArr, 0);
(*env)->ReleaseIntArrayElements(env, offset, offsetArr, 0);
(*env)->ReleaseIntArrayElements(env, size, sizeArr, 0);
(*env)->ReleaseIntArrayElements(env, dim0, dim0Arr, 0);

}
