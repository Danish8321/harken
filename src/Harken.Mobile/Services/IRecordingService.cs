namespace Harken.Mobile.Services;

/// <summary>
/// Cross-platform seam so shared code can start/stop the platform's recording
/// foreground service without referencing platform-specific types directly.
/// </summary>
public interface IRecordingService
{
	/// <summary>
	/// Raised when a recording finishes, however it ended — Stop tapped, silence timeout,
	/// or session cap. The single trigger for uploading, so an auto-stop uploads exactly
	/// like a manual one.
	/// </summary>
	event Action<RecordingCompleted>? Completed;

	/// <summary>Starts capture and returns the recording id the file is named for. The id
	/// is generated here rather than by the backend so a retried upload is recognisably the
	/// same recording (slice-06 Task 7).</summary>
	Guid StartRecording();

	void StopRecording();

	bool IsRecording { get; }

	TimeSpan Elapsed { get; }
}
