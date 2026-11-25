# V2 upgrade plan

## Stage 1 (this build): depth engine, motion upgrade, profiles, layer editor
Done in code, awaiting first compile and on-device test.
- Multi-layer renderer (`gl/LayerRenderer`), pinhole parallax (`engine/DepthModel`), shared-overscan no-gap rule
- Per-layer: depth, H/V multiplier, scale, perspective, smoothing, enable, order
- Motion: One Euro low-pass, per-axis sensitivity, return-to-neutral, 30/60 Hz sensor rate, profiles
  (Natural, Cinematic, Subtle, iOS-style)
- Layer editor with live preview, transparent-PNG layer import, 4-layer procedural demo
- Single-image wallpapers from earlier versions load as a one-layer project (unchanged behaviour)

## Stage 2: AI subject separation + background reconstruction
- Segmentation: MediaPipe Image Segmenter (bundled, offline) or ML Kit Subject Segmentation (needs Google Play services model download)
- Mask refinement (feather, edge cleanup) and brush correction tools (add/erase, undo/redo)
- Background reconstruction: on-device classical inpainting first; optional learned inpainting only if it fits memory

## Stage 3: automatic depth
- Monocular depth (Depth Anything V2 Small, Apache-2.0) to relative depth map, quantised into N layers
- User-adjustable depth map and layer count

## Stage 4: polish and measurements
- Battery/memory measurement on the Nord 3, lifecycle and rotation soak tests
