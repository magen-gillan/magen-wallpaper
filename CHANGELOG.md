## v4.0.9

- Fixed wallpaper changes stopping once the app process was killed or frozen: the
  unlock (Change on lock/unlock) trigger is now a manifest-declared receiver that
  the system cold-starts on ACTION_USER_PRESENT, replacing the process-bound
  dynamic registration.
- Completed the rename to **Magen Wallpaper**: old branding removed from launcher
  strings, live-wallpaper labels, notifications, docs, and code identifiers.

