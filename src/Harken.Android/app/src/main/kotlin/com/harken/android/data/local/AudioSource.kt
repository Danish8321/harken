package com.harken.android.data.local

// Local session audio source. Previously mirrored src/Harken.Core/AudioSource.cs for the
// (now-removed) backend contract; kept as a plain local enum for on-device sessions.
enum class AudioSource { Microphone, SystemAudio }
