using Android.Media;
using Harken.Mobile.Services;

namespace Harken.Mobile;

/// <summary>
/// Wraps <see cref="AudioRecord"/> to capture microphone audio at 16kHz/16-bit/mono
/// PCM — matching the backend's expected format exactly (same as the console client's
/// NAudio capture and AzureSpeechTranscriber's expected format).
/// </summary>
public class AndroidAudioCapture : IAudioCapture, IDisposable
{
	private const int SampleRate = 16000;
	private const ChannelIn ChannelConfig = ChannelIn.Mono;
	private const Android.Media.Encoding AudioEncoding = Android.Media.Encoding.Pcm16bit;

	private AudioRecord? _audioRecord;
	private Task? _captureTask;
	private volatile bool _isRunning;

	public event Action<byte[]>? ChunkCaptured;

	public void StartCapture()
	{
		if (_isRunning)
			return;

		var minBufferSize = AudioRecord.GetMinBufferSize(SampleRate, ChannelConfig, AudioEncoding);
		if (minBufferSize <= 0)
			throw new InvalidOperationException("AudioRecord.GetMinBufferSize returned an invalid buffer size.");

		// Use a reasonable multiple of the minimum buffer size for the internal
		// AudioRecord buffer, and the same size for each read.
		var bufferSize = minBufferSize * 4;

		_audioRecord = new AudioRecord(
			AudioSource.Mic,
			SampleRate,
			ChannelConfig,
			AudioEncoding,
			bufferSize);

		_audioRecord.StartRecording();
		_isRunning = true;

		_captureTask = Task.Run(() => CaptureLoop(_audioRecord, bufferSize));
	}

	public void StopCapture()
	{
		if (!_isRunning)
			return;

		_isRunning = false;
		_captureTask?.Wait(TimeSpan.FromSeconds(1));
		_captureTask = null;

		try
		{
			_audioRecord?.Stop();
		}
		finally
		{
			_audioRecord?.Release();
			_audioRecord = null;
		}
	}

	public void Dispose()
	{
		StopCapture();
		GC.SuppressFinalize(this);
	}

	private void CaptureLoop(AudioRecord audioRecord, int bufferSize)
	{
		var buffer = new byte[bufferSize];

		while (_isRunning)
		{
			var bytesRead = audioRecord.Read(buffer, 0, buffer.Length);
			if (bytesRead > 0)
			{
				var chunk = new byte[bytesRead];
				Array.Copy(buffer, chunk, bytesRead);
				ChunkCaptured?.Invoke(chunk);
			}
		}
	}
}
