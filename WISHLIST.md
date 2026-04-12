# LGPower Wishlist

## 1. Mute state on volume bar ✅ (in progress)
- Gray out the volume bar when TV is muted
- Make the mute button icon white when muted (active indicator)

## 2. Animated bars
- Smooth height transition when volume/brightness bars update instead of snapping

## 3. Physical volume buttons update the pill
- When hardware volume buttons are pressed, trigger `scheduleVolumeRefresh()` so the bar reflects the change

## 4. Long-press mute → volume slider
- Long-pressing the mute button opens a slider to set exact volume level

## 5. Remember last known volume/brightness
- Persist the last fetched volume and brightness to SharedPreferences
- Show the cached values immediately on open, before the network fetch completes
