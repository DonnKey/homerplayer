# Changelog - Aesop Player

## General Notes

The oldest changelog entry describes the overall differences between Aesop Player and
its predecessor Homer Player.

I am an "older gentleman". Although I expect to be working on Aesop
for some time to come, it's always possible I suddenly won't be able to do so. If that is
the case, I don't want Aesop to be orphaned if there's someone willing to work on it.
If you are unable to contact me at aesopPlayer@gmail.com and there's no recent activity,
it may be I can no longer work on it. 
If that's the case, and you are willing, please, 
adopt it - take a fork and fix it as needed. I'll contact you if and when I can.
(Be sure to look for, and contact the owner of, any forks that already exist.)

If I'm still working on it, I'll consider all collaboration suggestions seriously.

## Version 1.0.0

This version differs from the Homer Player by Marcin Simonides in a number of ways.

The changes fall into a few groups. The user interface changes were driven
by actual use: testing showed that the buttons were too small for some users to see,
and some users triggered multi-tap accidentally.
* The user interface is changed so the start/stop buttons are nearly full screen.
* The multi-tap anywhere to enter settings is changed to the gear icon multitap/multi-press.
* More status information is shown on the New Books and Playback screens (for caregivers.) 
* Flip to restart.
* Speed and volume setting by dragging or tipping with audio feedback.

A separate group of changes are made for the caregiver in terms of maintaining books 
and generally supporting the User.
* The ability to add and delete books without using a PC, either from the internet, 
On-the-go USB media, or removable SD. Change titles and book grouping.
* Explicit planning for remote access to be able to do the above without touching the 
device.
* Maintenance Mode (and remembering the Kiosk Mode.)
* Make selection of Kiosk mode a radio-button choice rather than bits and pieces.
* Support of Application Pinning and Full Kiosk as distinct modes.
* Ability to set book position anywhere/bulk reset.
* Use audio file metadata where available to provide titles automatically.

Technical changes.
* Increasing the robustness of Simple Kiosk Mode.
* Increasing the robustness of the UI finite state machine in support of 
  face-down-pause / face-up-resume.
* Various bug fixes and (inspection-driven) code cleanup.
* Rework of the motion detector for robustness, flip to restart, and tipping.
* Support for both public and private AudioBooks directories on each physical drive.

Large bodies of the code remain unmodified (or nearly so) from the original.