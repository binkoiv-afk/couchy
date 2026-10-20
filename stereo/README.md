# Объём 3D

Android player that turns ordinary 2D video into a stereo pair while it plays.

- Media3 decodes into a SurfaceTexture; everything else happens on the GPU and one worker thread.
- Depth Anything V2 Small (ONNX Runtime, on the CPU) estimates depth on a shrunk copy of the frame
  whenever it is free; the video itself always runs at full rate.
- The coarse depth is upsampled with a joint bilateral filter guided by the full frame, then each
  eye is rendered by backward depth-image-based warping: nearest surface wins, gaps are filled from
  the background.
- Output: SBS for VR viewers, half SBS and top-bottom for 3D TVs, Dubois red/cyan anaglyph.

Built by `.github/workflows/stereo.yml`, which fetches the models and publishes `stereo.apk` as a
`stereo-build-N` pre-release.
