// Copyright 2013 Dolphin Emulator Project
// Licensed under GPLv2+
// Refer to the license.txt file included.

#ifdef ANDROID
#include <assert.h>

#include <algorithm>
#include <vector>

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>

#include "AudioCommon/OpenSLESStream.h"
#include "Common/Assert.h"
#include "Common/CommonTypes.h"
#include "Common/Logging/Log.h"
#include "Core/ConfigManager.h"

// engine interfaces
static SLObjectItf engineObject;
static SLEngineItf engineEngine;
static SLObjectItf outputMixObject;

// buffer queue player interfaces
static SLObjectItf bqPlayerObject = nullptr;
static SLPlayItf bqPlayerPlay;
static SLAndroidSimpleBufferQueueItf bqPlayerBufferQueue;
static SLMuteSoloItf bqPlayerMuteSolo;
static SLVolumeItf bqPlayerVolume;
static CMixer *g_mixer;

// Double buffering.
static constexpr int BASE_BUFFER_FRAMES = 192;
static constexpr int MIN_BUFFER_BURSTS = 1;
static constexpr int MAX_BUFFER_BURSTS = 12;
static u32 g_buffer_frames = BASE_BUFFER_FRAMES * 4;
static std::vector<short> buffer[2];
static int nextBuffer = 0;

static int ConfiguredBufferBursts()
{
	return std::min(std::max(SConfig::GetInstance().iAndroidAudioBufferBursts, MIN_BUFFER_BURSTS),
	                MAX_BUFFER_BURSTS);
}

static void bqPlayerCallback(SLAndroidSimpleBufferQueueItf bq, void *context)
{
	assert(bq == bqPlayerBufferQueue);
	assert(nullptr == context);

	short *fillBuffer = buffer[nextBuffer].data();
	g_mixer->Mix(reinterpret_cast<short *>(fillBuffer), g_buffer_frames);

	SLresult result = (*bqPlayerBufferQueue)->Enqueue(
	    bqPlayerBufferQueue, fillBuffer, buffer[nextBuffer].size() * sizeof(short));

	// Comment from sample code:
	// the most likely other result is SL_RESULT_BUFFER_INSUFFICIENT,
	// which for this code example would indicate a programming error
	_assert_msg_(AUDIO, SL_RESULT_SUCCESS == result, "Couldn't enqueue audio stream.");

	nextBuffer ^= 1;
}

bool OpenSLESStream::Start()
{
	SLresult result;
	// create engine
	result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
	assert(SL_RESULT_SUCCESS == result);
	result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
	assert(SL_RESULT_SUCCESS == result);
	result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
	assert(SL_RESULT_SUCCESS == result);
	result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, 0, 0);
	assert(SL_RESULT_SUCCESS == result);
	result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
	assert(SL_RESULT_SUCCESS == result);

	SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
	SLDataFormat_PCM format_pcm = {
		SL_DATAFORMAT_PCM,
		2,
		m_mixer->GetSampleRate() * 1000,
		SL_PCMSAMPLEFORMAT_FIXED_16,
		SL_PCMSAMPLEFORMAT_FIXED_16,
		SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
		SL_BYTEORDER_LITTLEENDIAN
	};

	SLDataSource audioSrc = {&loc_bufq, &format_pcm};

	// configure audio sink
	SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
	SLDataSink audioSnk = {&loc_outmix, nullptr};

	// create audio player
#ifdef SL_ANDROID_KEY_PERFORMANCE_MODE
	const SLInterfaceID ids[3] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME, SL_IID_ANDROIDCONFIGURATION};
	const SLboolean req[3] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE, SL_BOOLEAN_FALSE};
	result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk, 3, ids, req);
#else
	const SLInterfaceID ids[2] = {SL_IID_BUFFERQUEUE, SL_IID_VOLUME};
	const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_FALSE};
	result = (*engineEngine)->CreateAudioPlayer(engineEngine, &bqPlayerObject, &audioSrc, &audioSnk, 2, ids, req);
#endif
	assert(SL_RESULT_SUCCESS == result);

#ifdef SL_ANDROID_KEY_PERFORMANCE_MODE
	SLAndroidConfigurationItf playerConfig = nullptr;
	result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_ANDROIDCONFIGURATION,
	                                         &playerConfig);
	if (result == SL_RESULT_SUCCESS && playerConfig)
	{
		SLuint32 performanceMode = SL_ANDROID_PERFORMANCE_LATENCY;
		(*playerConfig)->SetConfiguration(playerConfig, SL_ANDROID_KEY_PERFORMANCE_MODE,
		                                  &performanceMode, sizeof(performanceMode));
	}
#endif

	result = (*bqPlayerObject)->Realize(bqPlayerObject, SL_BOOLEAN_FALSE);
	assert(SL_RESULT_SUCCESS == result);
	result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_PLAY, &bqPlayerPlay);
	assert(SL_RESULT_SUCCESS == result);
	result = (*bqPlayerObject)->GetInterface(bqPlayerObject, SL_IID_BUFFERQUEUE,
		&bqPlayerBufferQueue);
	assert(SL_RESULT_SUCCESS == result);
	result = (*bqPlayerBufferQueue)->RegisterCallback(bqPlayerBufferQueue, bqPlayerCallback, nullptr);
	assert(SL_RESULT_SUCCESS == result);
	result = (*bqPlayerPlay)->SetPlayState(bqPlayerPlay, SL_PLAYSTATE_PLAYING);
	assert(SL_RESULT_SUCCESS == result);

	int configured_bursts = ConfiguredBufferBursts();
	g_buffer_frames = BASE_BUFFER_FRAMES * configured_bursts;
	buffer[0].assign(g_buffer_frames * 2, 0);
	buffer[1].assign(g_buffer_frames * 2, 0);
	g_mixer = m_mixer.get();
	g_mixer->Mix(reinterpret_cast<short *>(buffer[0].data()), g_buffer_frames);
	g_mixer->Mix(reinterpret_cast<short *>(buffer[1].data()), g_buffer_frames);
	nextBuffer = 0;

	INFO_LOG(AUDIO, "OpenSLES bufferBursts=%d bufferFrames=%u", configured_bursts,
	         g_buffer_frames);

	result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, buffer[0].data(),
	                                         buffer[0].size() * sizeof(short));
	if (SL_RESULT_SUCCESS != result)
		return false;
	result = (*bqPlayerBufferQueue)->Enqueue(bqPlayerBufferQueue, buffer[1].data(),
	                                         buffer[1].size() * sizeof(short));
	if (SL_RESULT_SUCCESS != result)
		return false;

	return true;
}

void OpenSLESStream::Stop()
{
	if (bqPlayerObject != nullptr)
	{
		(*bqPlayerObject)->Destroy(bqPlayerObject);
		bqPlayerObject = nullptr;
		bqPlayerPlay = nullptr;
		bqPlayerBufferQueue = nullptr;
		bqPlayerMuteSolo = nullptr;
		bqPlayerVolume = nullptr;
	}

	if (outputMixObject != nullptr)
	{
		(*outputMixObject)->Destroy(outputMixObject);
		outputMixObject = nullptr;
	}

	if (engineObject != nullptr)
	{
		(*engineObject)->Destroy(engineObject);
		engineObject = nullptr;
		engineEngine = nullptr;
	}
}
#endif
