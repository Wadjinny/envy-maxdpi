# Envy Max DPI

Prints photos from Android on an HP ENVY 6000-series printer at the Windows driver's
"Max DPI" quality (1200×1200 dpi), without Windows.

## Download

Grab the latest `envy-maxdpi-*.apk` from [Releases](https://github.com/Wadjinny/envy-maxdpi/releases/latest) and install it on your phone (allow installs from unknown sources). Every push to `main` also builds an APK as a [workflow artifact](https://github.com/Wadjinny/envy-maxdpi/actions).

## How it works

The HP Windows driver sends PCL3GUI: RGB rows at 1200 dpi compressed with HP mode 10,
over a raw socket on port 9100. The printer does color conversion and dithering itself.
This project produces the same stream:

```
image ──> fit to A4 at 1200 dpi (bicubic, one row at a time)
      ──> mode 10 compression (closed loop, see Mode10.kt)
      ──> PCL3GUI page header copied from a Windows capture
      ──> TCP port 9100
```

Findings on the ENVY 6030:
- IPP with `printer-resolution=1200dpi` is accepted, but prints at about 600 dpi quality.
- Sending PCL3GUI over IPP (`application/vnd.hp-PCL`) froze the printer. Use port 9100.
- The printer accepts data only as fast as it prints: about 6 minutes per Max DPI A4 page.

## Layout

- `core/src`: shared Kotlin (no Android or desktop dependencies): `Mode10`, `PclJobWriter`,
  `PclReader`, `FittedImage`, `PrinterConnection`.
- `desktop/src`: command-line test tool.
- `android/`: the app (plain Android, no AndroidX). It compiles `core/src` directly.

## Build

Toolchains live in `~/.local/toolchains` (JDK 21, kotlinc, Android SDK, Gradle).

```sh
./build.sh                                   # desktop tool -> build/envy-maxdpi.jar
./run.sh print photo.jpg --host printer.local
./run.sh reencode capture.prn                # check our encoder against a Windows capture

cd android && ./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
```

## App features

- Quality: Max DPI (1200 dpi, PCL3GUI over port 9100) or Best / Normal / Draft (IPP, PWG raster).
- Photos and PDFs (several at once; share or "Open with"); PDF pages render in 128-row bands.
- Color or grayscale, copies, two-sided (all modes), fit / fill, orientation, page range.
- Background printing: foreground service with a progress notification and Cancel; Wi-Fi and CPU
  locks; jobs queue.
- Printer status and ink levels over IPP; Bonjour search when `.local` names don't resolve.
- Printer problems and cancels via HP LEDM (`/DevMgmt/ProductStatusDyn.xml`, `/Jobs/JobList`): during a
  job the app shows "Paused: <reason>" (door, paper, jam…) and resumes; a cancel on the printer's panel
  is detected (Max DPI jobs keep accepting data after a panel cancel, so this is the only signal); a job
  counts as printed only when the printer reports it completed.

## Limits

- A4 plain paper only. Photo paper needs another Windows capture, to copy its media header.
- Two-sided in Max DPI: `&l1S`, long-edge backs sent rotated 180°. Long edge was confirmed on paper;
  short edge follows from the same test sheets but wasn't printed on its own.
- Grayscale in Max DPI converts pixels but keeps the color header, so color ink may still be used.
- The emulator can't resolve `printer.local`; type the printer's IP there.
