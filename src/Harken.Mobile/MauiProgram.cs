using Microsoft.Extensions.Logging;
using Harken.Mobile.Services;

namespace Harken.Mobile;

public static class MauiProgram
{
	public static MauiApp CreateMauiApp()
	{
		var builder = MauiApp.CreateBuilder();
		builder
			.UseMauiApp<App>()
			.ConfigureFonts(fonts =>
			{
				fonts.AddFont("OpenSans-Regular.ttf", "OpenSansRegular");
				fonts.AddFont("OpenSans-Semibold.ttf", "OpenSansSemibold");
			});

#if DEBUG
		builder.Logging.AddDebug();
#endif

		builder.Services.AddSingleton<IRecordingService, AndroidRecordingService>();
		builder.Services.AddSingleton<IAudioCapture, AndroidAudioCapture>();
		builder.Services.AddSingleton<AppSettings>();
		builder.Services.AddTransient<Pages.SettingsPage>();
		builder.Services.AddTransient<Pages.CapturePage>();

		return builder.Build();
	}
}
