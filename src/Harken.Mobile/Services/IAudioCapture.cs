namespace Harken.Mobile.Services;

/// <summary>
/// Cross-platform seam for microphone capture. Raises <see cref="ChunkCaptured"/>
/// with raw PCM bytes (16kHz, 16-bit, mono — matching the backend's expected format)
/// as they become available while capture is running.
/// </summary>
public interface IAudioCapture
{
	event Action<byte[]>? ChunkCaptured;

	void StartCapture();

	void StopCapture();
}
