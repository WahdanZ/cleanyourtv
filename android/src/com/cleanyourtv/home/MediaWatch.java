package com.cleanyourtv.home;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing by itself. Android lets an app see the media sessions (what's playing: its length, where it is, its
 * picture) only while it has a notification listener the user (here: the helper, over adb) allowed. CursorService
 * reads them for the phone's Now playing; no notification is read.
 */
public class MediaWatch extends NotificationListenerService { }
