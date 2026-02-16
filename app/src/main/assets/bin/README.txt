Place yttml binaries here per ABI:

- app/src/main/assets/bin/arm64-v8a/yttml
- app/src/main/assets/bin/armeabi-v7a/yttml
- app/src/main/assets/bin/x86_64/yttml

The app installs the matching binary to:
- <app files dir>/bin/yttml

Place hard-sub ffmpeg binaries here per ABI:

- app/src/main/assets/bin/arm64-v8a/ffmpeg
- app/src/main/assets/bin/armeabi-v7a/ffmpeg
- app/src/main/assets/bin/x86_64/ffmpeg

The app installs the matching binary to:
- <app files dir>/bin/ffmpeg

Expected CLI shape:
- yttml parse <input.srv3> --format ass --save file --output <output.ass>
