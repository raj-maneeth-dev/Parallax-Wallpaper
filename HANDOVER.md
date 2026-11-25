# HANDOVER: Project Parallax (Android gyroscopic live wallpaper)

Version in this package: **0.4.0 (V2 Stage 1 confirmed + Stage 2 first slice)**. Target device: OnePlus Nord 3 5G. Owner builds via GitHub Actions (no Android Studio).

## 1. Current status (read first)
| Item | State |
|---|---|
| Phase 2 (single-image engine) | Compiled on GitHub Actions and installed by the owner. |
| V2 Stage 1 (layers, motion profiles, editor) | Compiled green on GitHub Actions and tested on the OnePlus Nord 3 by the owner: parallax works, lock screen animates, no crashes or lag. Editor reviewed in code; 0.4.0 stops "Add layer" from resetting depths and adds confirm dialogs for delete/demo. |
| V2 Stage 2 (AI segmentation + background reconstruction) | First slice written in 0.4.0 (`ai/Separator`, `ai/Inpaint`, button in the layer editor). **Not yet compiled or run.** Manual mask tools still not started. |
| V2 Stage 3 (monocular depth, depth-map editing) | **Not started.** |
| Manual mask-correction tools (brush, eraser, undo/redo) | **Not started.** |
| Release APK / signing | Not done. Only `assembleDebug` is built by CI. |

Do not claim anything beyond this table. The sandbox used to author the code has no Android SDK, Gradle or Kotlin compiler and no network, so every build result comes from the owner's GitHub Actions log.

## 2. How to build
1. Put the project contents (the files inside `ParallaxWallpaper/`, not the folder itself) at the root of a GitHub repo.
2. `.github/workflows/build.yml` runs on push to main/master (or manually: Actions > Build APK > Run workflow). JDK 17, Gradle 8.9 via `gradle/actions/setup-gradle`, command `gradle :app:assembleDebug --stacktrace`.
3. Download the `parallax-debug-apk` artifact, unzip, install `app-debug.apk`.
4. If red: open the "Build debug APK" step, copy the `e: file:///...` lines. Kotlin errors name the file and line.

Toolchain: AGP 8.5.2, Kotlin 1.9.24, compileSdk/targetSdk 34, minSdk 26. **No third-party dependencies.** No Compose, no AndroidX: UI is plain `android.app.Activity` with programmatically built views.

## 3. Architecture
Package `dev.parallax.wallpaper`:

| Path | Role |
|---|---|
| `service/ParallaxWallpaperService` | WallpaperService + Engine. Lifecycle only; forwards to `WallpaperGlThread`. Holds the prefs listener as a field (SharedPreferences keeps listeners weakly). |
| `gl/WallpaperGlThread` | One HandlerThread per surface (wallpaper or editor preview). Owns EGL, `LayerRenderer`, `MotionTracker`, one pair of `Spring1D` per layer. Choreographer-driven **on-demand** frames: runs only while visible and still moving. Sensor callbacks arrive on this same thread (no locks). |
| `gl/EglCore` | EGL14 context/window surface wrapper (ES 2.0). |
| `gl/LayerRenderer` | Draws layers back to front. Per layer: shift = tilt x maxShift x depthFactor x axisMultiplier; small dolly zoom; premultiplied-alpha blending (`GL_ONE, GL_ONE_MINUS_SRC_ALPHA`), blend off for the bottom/opaque layer. |
| `gl/ImageSource` | Decodes layer files at screen-appropriate size (power-of-two sample, capped by `GL_MAX_TEXTURE_SIZE`); builds the procedural single-layer test scene. |
| `engine/MotionTracker` | Sensor fusion to tilt. Source chain: `GAME_ROTATION_VECTOR` > `ROTATION_VECTOR` > gyro+accel (`ComplementaryFilter`, Mahony-style) > accel only. Tilt = screen normal expressed in the neutral pose's frame (`ref^T * R`, column 2), two angles, display-rotation remap, slow re-centering (bounds drift), `OneEuroFilter`, `tanh` soft limit. Sign: content shifts opposite to tilt; `invert` flips it. |
| `engine/DepthModel` | `factor(depth, zFar) = Z_NEAR / (zFar + (Z_NEAR - zFar) * depth)`; depth 1 = nearest = factor 1. |
| `engine/Spring1D` | Critically damped spring (frame-time aware). |
| `data/Prefs` | SharedPreferences keys + `ParallaxSettings` snapshot. `Prefs.bump(...)` increments several revision counters in ONE commit. |
| `data/MotionProfile` | Presets (Natural, Cinematic, Subtle, iOS-style) that rewrite the settings. iOS values are tuned by feel, not measured from Apple. |
| `data/LayerModel` | `LayerSpec` (id, name, file, depth, hMul, vMul, scale, perspective, smoothing, enabled, hasAlpha) and `WallpaperProject` (canvas size + layers, index 0 = back). JSON via `org.json`. |
| `data/ProjectStore` | `filesDir/project.json` + `filesDir/layers/*.png`. Load (with legacy single-image fallback `background.jpg`), save, respace depths, add layer from URI (cover-fitted to canvas), 4-layer procedural demo. |
| `data/WallpaperStore` | Single-image import (EXIF-corrected, max 4096 px) which resets the project to one layer. |
| `ui/MainActivity` | Console: set wallpaper, choose image, layer editor, calibrate, profile buttons + iOS preset, motion/depth/performance sliders, device report. |
| `ui/LayerEditorActivity` | Live preview (`ParallaxSurfaceView`) + per-layer cards (enable, reorder, delete, 6 sliders), add layer, auto-space depths, demo. Portrait only. |
| `ui/ParallaxSurfaceView`, `ui/SensorProbe` | In-app preview using the same render thread; hardware/refresh-rate report. |

### Key invariants (do not break)
- **All layers share one canvas and one base zoom** computed from the largest possible displacement (`fMax`). This keeps layers aligned at the neutral pose and guarantees no edge gaps. Dynamic and user scale may only enlarge (>= 1).
- **Revision counters:** `IMAGE_REVISION` bump = structural change (reload textures); `PARAMS_REVISION` only = numeric tweak (cheap `updateSpecs`). A structural save bumps both in one commit; the render thread checks image revision first.
- Layer file paths in `LayerSpec.file` are relative to `filesDir` (`background.jpg` or `layers/<id>.png`).
- Sensors register only while visible (`onVisibilityChanged`); no frames when settled.
- Preview and wallpaper must keep using the same `WallpaperGlThread`.

### Stage 2 (0.4.0)
- `ai/Separator`: picks photo (EXIF-corrected, longest side <= 2400) > MediaPipe ImageSegmenter (confidence masks) > smoothstep soft alpha > Subject PNG + Background PNG > `ProjectStore.replaceWithBitmaps` (replaces the project; back = background, front = subject). Subject covering <1% or >90% is reported as a failure.
- `ai/Inpaint`: dilated hole, push-pull pyramid fill at <=1024 px, blended back at full size. Smooth and blurry by design; no learned inpainting.
- Models are NOT in the repo: the workflow downloads `selfie_segmenter.tflite` and `deeplab_v3.tflite` into `app/src/main/assets` before the build (URLs unverified; the step fails loudly on a bad URL).
- Dependency: `com.google.mediapipe:tasks-vision:0.10.14`, `arm64-v8a` only, `.tflite` stored uncompressed, `android.useAndroidX=true`.
- Unverified assumptions: output mask meaning (1 mask = subject probability, >1 masks = index 0 is background), mask size equals input size (resampled anyway), memory use at 2400 px.

## 4. Settings keys (SharedPreferences `parallax_prefs`)
`motion_enabled, invert, tilt_range_deg, shift_pct, smooth_ms, recenter_sec, fps, sens, h_sens, v_sens, perspective, depth_contrast (zFar), jitter_hz, sensor_hz, profile, calibrate_token, image_revision, params_revision`. Floats are stored as floats (fps and sensor_hz too; read with `getFloat().toInt()`).

## 5. Lessons / gotchas
- **Kotlin block comments nest.** Never write `/*` inside a comment (e.g. `layers/*.png`); it opens a nested comment and produces "Unclosed comment". Strings like `"image/*"` are fine.
- A bracket-balance check does not catch this; check comment nesting depth.
- `Choreographer` needs a Looper thread; it is fetched on the GL handler thread at startup.
- `SharedPreferences` listeners are weakly held: keep a strong field.
- Surface destruction must be synchronous (`postAndWait`) because the Surface dies after `onSurfaceDestroyed` returns.

## 6. Known limitations / unverified
- Sign convention (shift direction) is untested on hardware; the UI has Invert.
- Lock-screen animation on OxygenOS is unverified; Android requires the user to confirm applying a live wallpaper and choose Home/Lock/Both.
- AOD will not run the wallpaper. Home-screen page scrolling is not used.
- Frame cap is a request via `Surface.setFrameRate` (API 30+); the system decides the real wallpaper refresh rate.
- No battery or memory measurements exist yet. Default layers are decoded to ARGB_8888 (about 14 MB per screen-sized layer).
- Per-layer image import fits by center-crop to the canvas; there is no manual layer positioning yet.

## 7. Roadmap
**Stage 2: AI separation + reconstruction**
- Segmentation candidates (researched, none implemented): MediaPipe Image Segmenter (offline; DeepLab-v3 covers a fixed set of classes incl. person/cat/dog, selfie model for people) or ML Kit Subject Segmentation (beta, unbundled: needs Google Play services and a model download). Prefer MediaPipe for offline use; verify real quality on the device before choosing.
- Mask refinement: feather/edge cleanup, soft masks. Background reconstruction: classical on-device inpainting first (edge-aware fill of the removed region, slightly dilated mask); learned inpainting only if memory allows. Provide a graceful fallback + manual correction.
- Output: write subject cutout (PNG with alpha) and reconstructed background as layers via `ProjectStore`.
- Manual tools: brush add/erase, edge refine, undo/redo, layer visibility.
- Will add dependencies (MediaPipe tasks-vision, model asset) and needs a `minSdk` / ABI size check.

**Stage 3: depth**
- Depth Anything V2 **Small** only (Apache-2.0; Base/Large/Giant are CC-BY-NC-4.0, not allowed commercially). Needs a mobile-converted model (TFLite/ONNX) that has not been selected or tested. Quantise the relative depth map into N layers; user-adjustable.

**Stage 4:** measure battery/memory on the Nord 3; lifecycle and rotation soak tests; release signing.

## 8. Working agreement with the owner
- Owner: Raj; builds only through GitHub Actions; uploads files via the GitHub web UI (zip contents, not the folder itself).
- Work in stages; do not claim a feature works until the owner has built and tested it. Say plainly what is unverified.
- Keep changes incremental; do not rewrite the app or remove working features; avoid new dependencies unless needed (Stage 2/3 need them).
