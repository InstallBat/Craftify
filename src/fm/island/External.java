package fm.island;

import android.content.*;
import android.media.session.*;
import android.os.*;
import android.service.notification.NotificationListenerService;
import java.util.*;

public final class External extends NotificationListenerService {
    static List<MediaController> sessions(Context context) {
        try {
            List<MediaController> controllers = new ArrayList<>(context.getSystemService(MediaSessionManager.class)
                .getActiveSessions(new ComponentName(context,External.class)));
            controllers.removeIf(c -> c.getPackageName().equals(context.getPackageName()));
            return controllers;
        } catch (SecurityException e) { return Collections.emptyList(); }
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token,Store.Meter> meters = new HashMap<>();
    private final Runnable watch = new Runnable() {
        @Override public void run() {
            Set<MediaSession.Token> live = new HashSet<>();
            for (MediaController c : sessions(External.this)) {
                live.add(c.getSessionToken());
                android.media.MediaMetadata data = c.getMetadata();
                PlaybackState state = c.getPlaybackState();
                if (data == null || state == null) continue;
                String title = data.getDescription().getTitle() == null ? "" : data.getDescription().getTitle().toString();
                String artist = Track.clean(data.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST));
                String key = c.getPackageName()+"\n"+artist+"\n"+title;
                meters.computeIfAbsent(c.getSessionToken(), k -> new Store.Meter(External.this))
                    .sample(key,title,artist,state.getState() == PlaybackState.STATE_PLAYING);
            }
            meters.keySet().retainAll(live);
            handler.postDelayed(this,1000);
        }
    };
    @Override public void onListenerConnected() { handler.removeCallbacks(watch); handler.post(watch); }
    @Override public void onListenerDisconnected() { handler.removeCallbacks(watch); meters.clear(); }
    @Override public void onDestroy() { handler.removeCallbacks(watch); super.onDestroy(); }
}

