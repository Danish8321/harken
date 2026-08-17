namespace Harken.Mobile.Services;

/// <summary>
/// Cross-platform seam so shared code can start/stop the platform's recording
/// foreground service without referencing platform-specific types directly.
/// </summary>
public interface IRecordingService
{
	/// <summary>Starts capture and returns the recording id the file is named for. The id
	/// is generated here rather than by the backend so a retried upload is recognisably the
	/// same recording (slice-06 Task 7).</summary>
	Guid StartRecording();

	void StopRecording();

	bool IsRecording { get; }

	/// <summary>Path being written right now; null when not recording.</summary>
	string? CurrentFilePath { get; }

	/// <summary>Path of the last finished recording, whatever ended it. Outlives the
	/// service so the page can still upload after capture has stopped.</summary>
	string? LastCompletedFilePath { get; }

	TimeSpan Elapsed { get; }
}
