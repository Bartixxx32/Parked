# TODO

Ideas and planned work that are not started yet.

## Photo straight from the camera

Status: backlog.

Right now a spot photo can only be picked from the gallery
(`ActivityResultContracts.GetContent` in `HistoryScreen.kt`).

- Add a "Take photo" option next to the gallery picker in the spot editor.
- Use `ActivityResultContracts.TakePicture` with a `FileProvider` URI. Do not
  declare the `CAMERA` permission: the system camera app takes the picture, so
  no runtime permission is needed. Declaring it would force us to request it.
- Store the captured file in the app's internal `files/photos` directory (same
  as picked photos) and reuse the existing save/replace/remove flow in
  `ParkingRepository`.
- Handle cancellation and a missing camera app gracefully with an EN/PL error.
- Update the README feature list and verify the flow on the emulator with both
  locales.
