# Touch and button audit

## Implemented fixes

- Custom buttons have at least a 44 × 44 point target, a rectangular hit shape and
  an immediate pressed highlight. They do not shrink or move when pressed.
  Disabled controls are visibly dimmed. Native list rows, menus, alerts, pickers
  and navigation bars retain system handling.
- Header/back/search/clear/overflow controls use explicit targets. The Cancel and
  timing ±/Reset targets include the padding inside their button labels.
- Clipped artwork cannot intercept touches beyond its displayed bounds. Decorative
  artwork, gradients, borders and pagination/background geometry do not receive taps.
- Video single/double-tap gestures occupy the video area between visible controls,
  or the full surface when controls are hidden. They are disabled under menus/lock.
  Single and double taps are mutually exclusive, so seeking does not also hide controls.
- Player auto-hide restarts after interactions and waits while a control is pressed,
  the seek slider is being dragged, a menu is open or a file is being imported.
- Play/pause follows the user's command immediately rather than waiting for VLC's
  next state poll; track selection immediately highlights the requested track.
  Playback/seek/rotation commands give brief visible feedback. They are command
  acknowledgements, not a claim that network playback has already recovered.
- Downloads, cancellation, sign-in, export and history changes expose busy state.
  Duplicate download/cancel/auth/history/collection-selection submissions are guarded.
  Errors clear pending state and remain visible. Reset sign-in ignores an old reply.
- Source scanning keeps a consistent busy indicator between batches; retry scanning
  is attached to the picker lifecycle. Search bars keep their height when Clear appears.

## What has been checked

Source inspection covers every Button/Menu/Link and the gesture layers in the
library, collection picker, settings, details, opening overlay and VLC player.
Portable integration fixtures exercise command state and request duplication;
Swift grammar checks cover edited views. This is not a device UI test. Apple
SDKs, native controls, physical hit testing and VLC rendering cannot be exercised
in the Linux workspace. No statement that every button works on iPhone is made.

## Signed iPhone/iPad checklist

For each visible control, tap the centre and near each edge inside its displayed
target. Then tap just outside it. Only the intended target should activate. Test
portrait, manually locked landscape, camera safe areas, an iPad and larger text.

| Area | Controls / expected result |
| --- | --- |
| Sign-in | Connect; Continue for phone/code/password/email; Back to sign in. Immediate busy state, one request, useful error/retry |
| Library header | Back returns to library; Search opens search; Select channels opens picker; profile menu opens and its Choose/Refresh/Settings actions work |
| Home | Choose/Manage/My channels; collection title and tile open that collection; hero Play/Resume starts one opening attempt; More info opens details |
| Tabs | Home/My channels/Search/History activate from the entire visible cell; selected tab updates |
| Search | Clear button works from its whole target; typing does not shift the row height; Retry/More results show progress; tapping a result opens the intended file |
| Posters | Play target matches the clipped image; adjacent posters and their menus do not steal taps; scrolling does not play a file |
| Video menu | Play/resume, start over, download/cancel, Save to Files, mark watched and remove history act on that video; async work is visible |
| Opening/export | Cancel works across the entire label; cancelling then selecting another file cannot cause old playback; export shows preparation before Files picker |
| Source picker | All scope segments, clear search, selection row, Show more/Continue finding sources and Done; selection busy/checked state is visible; dismissal ends scanning |
| Settings/details | Done, refresh, each clear action and its confirmation/cancel, sign-out, project/license links; empty/busy refresh is disabled |
| Player navigation | Down arrow closes during opening, buffering, playback, pause and landscape; controls remain notch-safe |
| Transport | Play/pause changes symbol immediately; ±10 buttons and seek slider work when enabled; video double tap seeks without also hiding controls |
| Player tools | Speed, Tracks, Picture, Rotate and More open/change the intended setting; full padded button targets work; selected values update |
| Tracks | Audio/Subtitles tabs, each track, Off, external file import/cancel and timing ±/Reset; track changes highlight immediately |
| Subtitle size | All five sizes, Back to subtitles, close options; selection remains after reopening; bitmap/styled subtitles may keep authored size |
| Lock/menus | Lock → Unlock; menu close and outside dismissal; taps on menu rows cannot seek/hide video; hidden controls return on a video tap |
| Auto-hide | Hold a button, drag the slider, scroll a menu, import a file; controls remain reachable and hide only after interaction ends |
| System picker | Files export/import confirm and cancel return cleanly; errors are shown |

Repeat with VoiceOver/Switch Control, slow network, interrupted downloads and rapid
taps. Record the installed commit and any failing control, orientation and screen.
