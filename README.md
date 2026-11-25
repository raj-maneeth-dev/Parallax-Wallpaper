# Parallax (V2 Stage 1): depth engine + motion profiles

Gyroscope-driven live wallpaper for Android. This phase delivers: WallpaperService, EGL/OpenGL ES 2.0
render thread, sensor fusion, spring smoothing, calibration, and a single-image parallax renderer,
plus a small test console. Multi-layer depth is Phase 3; the full UI is Phase 4.

**Status: written but not yet compiled.** The authoring sandbox has no Android toolchain. The first
GitHub Actions run is the first compile; paste any errors back.

## Build the APK (no installs)
1. Unzip this project. Create a free GitHub account and a new repository (private is fine).
2. Upload everything *including the hidden `.github` folder* (see tips below).
3. Open the repo's **Actions** tab. "Build APK" runs on every push (about 3-6 min the first time).
4. Open the finished run, then **Artifacts**, download `parallax-debug-apk`, unzip it to get `app-debug.apk`.
5. Copy it to the phone, tap it, allow "install unknown apps" for your file manager or browser.

Upload tips: with git installed:
`git init && git add -A && git commit -m init && git branch -M main && git remote add origin <repo-url> && git push -u origin main`.
With the website uploader, if `.github` is skipped, use **Add file > Create new file**, type
`.github/workflows/build.yml` as the name and paste that file's contents.

## Using it
Open **Parallax**, tap *Set as live wallpaper*, then choose Home / Lock / Both in the system dialog.
The built-in test scene (gradient + grid + rings) makes motion obvious; *Choose image* swaps in a photo.
Tilt and turn the phone. If the shift goes the wrong way, enable **Invert direction**.
*Calibrate* sets the current pose as neutral (the pose is also re-captured whenever the wallpaper
becomes visible, and slowly re-centres by itself).

## Architecture
| Package | Role |
|---|---|
| `service/` | `ParallaxWallpaperService` + Engine: lifecycle only; forwards to the GL thread |
| `gl/WallpaperGlThread` | Render thread: EGL, sensors, springs, Choreographer on-demand frame loop |
| `gl/EglCore`, `gl/ImageQuadRenderer` | EGL14 context/surface; GLES20 textured quad with overscan |
| `gl/ImageSource` | Decodes the imported image to screen-appropriate size (or the test scene) |
| `engine/MotionTracker` | Rotation-vector (or gyro+accel fusion) to tilt, neutral pose, recenter, rotation remap |
| `engine/ComplementaryFilter`, `Spring1D` | Software fusion fallback; critically damped smoothing |
| `data/` | Settings (SharedPreferences) and image import (EXIF-corrected) |
| `ui/` | Phase 2 test console and sensor/display report |

Key decisions: sensors are registered only while the wallpaper is visible; frames are scheduled only
while motion is still settling (fully idle otherwise); overscan is derived from the maximum shift so
edges can never show; tilt is soft-saturated with tanh instead of hard-clamped.

## Known limits (so far)
- Home-screen page scrolling is not used (tilt only).
- Lock-screen animation depends on OxygenOS; to be verified on the device.
- Frame cap is a request; the system decides the actual wallpaper refresh rate.

## V2 Stage 1 additions
- **Motion profiles** (main screen): Natural, Cinematic, Subtle, iOS-style. Each rewrites the motion settings;
  tweak afterwards. The iOS-style preset is a tuned approximation of the iPhone feel, not a copy of Apple's values.
- **Layer editor**: live preview, per-layer depth/H/V/scale/perspective/smoothing, reorder, toggle, delete,
  add transparent PNG layers. "4-layer demo" builds a procedural test scene.
- AI separation, background reconstruction and depth estimation are NOT in this build (Stages 2 and 3).
- Status: written, not yet compiled or device-tested.
