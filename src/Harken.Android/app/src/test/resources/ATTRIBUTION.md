# ami-es2002a-0-90s.wav

The first 90 seconds of `ES2002a.Mix-Headset` from the **AMI Meeting Corpus**,
downmixed to the shape this app records in (16 kHz, mono, 16-bit PCM). Four
people, a real room, opening a real meeting.

- Source: AMI Meeting Corpus, <https://groups.inf.ed.ac.uk/ami/corpus/>
- Licence: Creative Commons Attribution 4.0 International (CC BY 4.0),
  <https://creativecommons.org/licenses/by/4.0/>
- Attribution: the AMI Meeting Corpus, AMI Consortium.
- Changes made: excerpted to the first 90 seconds and converted to 16 kHz mono
  16-bit PCM. Not otherwise edited.

## Why it is in the repository

`SilenceDetector`'s auto-stop was tuned entirely against synthesised fixtures,
which are about four times louder than real speech and have digital silence
between sentences. Against this recording the shipped rule counted 97% of a live
meeting as silence and would have stopped it at 617 seconds — half the meeting,
unrecoverable. No synthetic fixture in this repository showed that, and none
would have.

`SilenceDetectorRealAudioTest` asserts against this file so the next person to
tune a constant has to answer to real audio.
