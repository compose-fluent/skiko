Based on Skiko 0.150.1, with kotlin-winrt 0.1.0.

Fixes a rendering issue where canvas transforms accumulated between frames, causing clock content to disappear and briefly reappear when resizing the window. Canvas state is now restored after each frame in both WinUI backends.

Validated with JVM rendering regression checks and release builds for both backends. The window-hosted MinGW sample was not run.
