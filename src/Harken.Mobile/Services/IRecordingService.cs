namespace Harken.Mobile.Services;

/// <summary>
/// Cross-platform seam so shared code can start/stop the platform's recording
/// foreground service without referencing platform-specific types directly.
/// </summary>
public interface IRecordingService
{
	void StartRecording();

	void StopRecording();
}
