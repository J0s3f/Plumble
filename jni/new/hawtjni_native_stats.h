
#ifdef NATIVE_STATS
extern int Native_nativeFunctionCount;
extern int Native_nativeFunctionCallCount[];
extern char* Native_nativeFunctionNames[];
#define Native_NATIVE_ENTER(env, that, func) Native_nativeFunctionCallCount[func]++;
#define Native_NATIVE_EXIT(env, that, func) 
#else
#ifndef Native_NATIVE_ENTER
#define Native_NATIVE_ENTER(env, that, func) 
#endif
#ifndef Native_NATIVE_EXIT
#define Native_NATIVE_EXIT(env, that, func) 
#endif
#endif

typedef enum {
	Native_celt_1decode_FUNC,
	Native_celt_1decode_1float_FUNC,
	Native_celt_1decoder_1create_FUNC,
	Native_celt_1decoder_1destroy_FUNC,
	Native_celt_1encode_FUNC,
	Native_celt_1encoder_1create_FUNC,
	Native_celt_1encoder_1ctl_FUNC,
	Native_celt_1encoder_1destroy_FUNC,
	Native_celt_1mode_1create_FUNC,
	Native_celt_1mode_1destroy_FUNC,
	Native_speex_1echo_1cancellation_FUNC,
	Native_speex_1echo_1capture_FUNC,
	Native_speex_1echo_1ctl_FUNC,
	Native_speex_1echo_1playback_FUNC,
	Native_speex_1echo_1state_1destroy_FUNC,
	Native_speex_1echo_1state_1init_FUNC,
	Native_speex_1resampler_1destroy_FUNC,
	Native_speex_1resampler_1init_FUNC,
	Native_speex_1resampler_1process_1int_FUNC,
} Native_FUNCS;
