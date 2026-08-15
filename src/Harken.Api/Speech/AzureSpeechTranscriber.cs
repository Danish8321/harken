using Microsoft.CognitiveServices.Speech;
using Microsoft.CognitiveServices.Speech.Audio;

namespace Harken.Api.Speech;

/// <summary>
/// Azure Speech continuous-recognition wrapper. One instance per Session; the
/// recognizer, audio config and push stream are disposed cleanly on stop
/// (resource-leak guard — ADR-0001).
/// </summary>
public sealed class AzureSpeechTranscriber : ISpeechTranscriber
{
    private readonly PushAudioInputStream _pushStream;
    private readonly AudioConfig _audioConfig;
    private readonly SpeechRecognizer _recognizer;

    private bool _running;
    private bool _disposed;

    public event Action<string>? PartialRecognized;
    public event Action<string>? FinalRecognized;

    public AzureSpeechTranscriber(AzureSpeechOptions options)
    {
        var speechConfig = SpeechConfig.FromSubscription(options.Key, options.Region);

        // 16 kHz, 16-bit, mono PCM — matches the captured audio format.
        var format = AudioStreamFormat.GetWaveFormatPCM(16000, 16, 1);
        _pushStream = AudioInputStream.CreatePushStream(format);
        _audioConfig = AudioConfig.FromStreamInput(_pushStream);
        _recognizer = new SpeechRecognizer(speechConfig, _audioConfig);

        _recognizer.Recognizing += OnRecognizing;
        _recognizer.Recognized += OnRecognized;
    }

    private void OnRecognizing(object? sender, SpeechRecognitionEventArgs e)
    {
        PartialRecognized?.Invoke(e.Result.Text);
    }

    private void OnRecognized(object? sender, SpeechRecognitionEventArgs e)
    {
        if (e.Result.Reason == ResultReason.RecognizedSpeech
            && !string.IsNullOrEmpty(e.Result.Text))
        {
            FinalRecognized?.Invoke(e.Result.Text);
        }
    }

    public async Task StartAsync(CancellationToken ct)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_running)
        {
            return;
        }

        await _recognizer.StartContinuousRecognitionAsync().ConfigureAwait(false);
        _running = true;
    }

    public void PushAudio(ReadOnlyMemory<byte> chunk)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        _pushStream.Write(chunk.ToArray());
    }

    public async Task StopAsync()
    {
        if (!_running)
        {
            return;
        }

        await _recognizer.StopContinuousRecognitionAsync().ConfigureAwait(false);
        _running = false;
        _pushStream.Close();
    }

    public async ValueTask DisposeAsync()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        _recognizer.Recognizing -= OnRecognizing;
        _recognizer.Recognized -= OnRecognized;

        if (_running)
        {
            try
            {
                await _recognizer.StopContinuousRecognitionAsync().ConfigureAwait(false);
            }
            catch
            {
                // Best-effort stop during teardown; disposal must still proceed.
            }
            _running = false;
        }

        _recognizer.Dispose();
        _audioConfig.Dispose();
        _pushStream.Dispose();
    }
}
